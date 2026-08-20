---
title: "[M12] Staff & permissions — the owner adds staff, sets what they can do, and deactivates them"
labels: []
---

## 📖 Story / Why

Today Aromex has exactly one kind of user: the owner, created by running a script
(`firebase/scripts/setup-project.ts`) against the company's Firebase project. There is no way for a
shop owner to give their cashier a login, let alone decide what that cashier may see.

That is the last thing standing between Aromex and a real shop floor. A distributor with two staff
wants the person at the counter to ring up sales without seeing supplier balances, the accountant to
read the ledgers without touching inventory, and a departed employee's access gone the same day.

The good news: **the hard half is already built and enforced.** The permission model, the rules, and
the four layers of checking all exist and are tested. What's missing is the screen, three repository
methods, and one Cloud Function. This ticket is mostly wiring, plus two security gaps that must be
closed before staff exist at all.

## 🧭 Context

### What already exists — do not rebuild any of this

**The permission model** (`sharedLogic/.../model/Permissions.kt`) — eleven scopes, each
`NONE` / `VIEW` / `MANAGE`:

`sales` · `purchases` · `inventory` · `transactions` · `profiles` · `balances` · `reports` ·
`statistics` · `histories` · `ledgers` · `settings`

plus `userMgmt: Boolean`, and a role (`UserRole.ADMIN` / `MEMBER`) in
`sharedLogic/.../model/UserSession.kt`.

**Enforcement, in four layers** — all already written:
1. UI hides controls (`canManage` flags on the ViewModels)
2. Use cases throw `PermissionDeniedException` (see `SaveBuyerPhoneUseCase`, `SaveEntityUseCase`)
3. `firebase/firestore.rules` re-checks via `hasPermission(feature, level)`
4. Cloud Functions re-check server-side (see `assertCanViewProfiles` in `firebase/functions/src/statement.ts`)

**The rules already permit exactly this feature** (`firebase/firestore.rules`, `users/{uid}` block):
- `get`: own doc, or any admin · `list`: admin only
- `create` / `update`: admin only, with a field-level guard so a member cannot set `role == "admin"`
  or `userMgmt == true`, and email is immutable after creation
- `delete`: **`if false`** — deactivate via `isActive`, never delete

### The part that decides this ticket's shape: custom claims

`firestore.rules` does **not** read the user document to decide who you are. It reads the **Firebase
Auth token**:

```
function isAdmin()              { return isSignedIn() && request.auth.token.admin == true; }
function belongsToThisCompany() { return ... request.auth.token.hlCompanyId == <the company's id>; }
```

Every single rule requires `belongsToThisCompany()`. So a staff member with a `users/{uid}` document
but **no `hlCompanyId` claim can do nothing at all** — they will sign in and hit permission-denied
everywhere, which looks exactly like a broken app.

Only the Firebase Admin SDK can set custom claims, and a client cannot create an Auth user. Therefore
**creating a staff member must be a Cloud Function** that does all three steps together:

1. `auth.createUser({ email, password, displayName })`
2. `auth.setCustomUserClaims(uid, { hlCompanyId, ...(admin ? { admin: true } : {}) })`
3. write `users/{uid}` with role, permissions, `isActive: true`

`firebase/scripts/setup-project.ts` steps 2–4 already do exactly this for the owner — **read it
first; the Cloud Function is that logic, gated on the caller being an admin.**

**Claims only refresh when the ID token does** (on sign-in, or roughly hourly). Promoting someone to
admin, or removing their access, will not take effect in their running app until then. Handle this
explicitly — see Scope.

### Login starts at the gateway, not at Firebase — the Central Directory step

*(Added after the blocker found during the build. This was missing from the first draft.)*

Signing in does **not** begin with Firebase. The app's first call is `POST /resolve-company` on the
gateway, which maps the email to a company through the Central Directory's `emailIndex`. Creating a
staff member entirely inside the client's Firebase project therefore leaves them **locked out before
the password step on every platform**: `/resolve-company` returns no company and the app raises
`UnknownEmail`.

This is a PRD requirement, not an extra:

> **PRD §7.2** — "User creation runs through a Cloud Function … it also writes the new user's
> **email→company mapping** into the Central Directory."

It only went unnoticed because the sole user until now — the owner — is registered by the gateway's
own `register-company.ts` during onboarding.

**The gateway side is already done** (`aromex-gateway`, branch
`fix/email-index-by-project-id`): `POST /admin/email-index` now accepts **either** `companyId` (as
before) **or** `projectId`, resolved via the existing `findCompanyByProjectId()`. A Cloud Function
knows its `projectId`, so that's the form it uses. **It needs a gateway deploy before staff creation
will work end to end — confirm with the manager that it has shipped.**

What this ticket must add on the Aromex side:

- `createStaffUser` calls `POST /admin/email-index` with `{ email, projectId }` and the admin token
  immediately after provisioning the user. The Cloud Function **already** holds that token and
  already calls the gateway this way — `getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId)`
  in `syncWorker.ts` is the same shape.
- Call it on **both** the fresh-create and the adopt-an-existing-Auth-user paths, so re-running
  repairs a half-finished creation. The gateway upserts on `(emailHash, companyId)`, so repeating it
  is safe.
- **If the directory write fails, fail the whole operation loudly**, with a message saying the
  account exists but isn't registered yet and to try again. Reporting success here would recreate
  exactly this bug — an owner who thinks the person is ready, and a person who cannot sign in.
- **Deactivation leaves the directory entry alone** (manager's decision). Disabling the Auth user
  already blocks sign-in and `isActive=false` is enforced at login; the app even has an
  "account is disabled" message. Reactivation then needs no gateway call, and no delete endpoint has
  to be built.
- A staff email that already exists at another company is **not** a conflict: `emailIndex` maps an
  email to a *list* of companies and the app already has a "choose workspace" screen.

### Two security gaps to close in this ticket

Both are harmless today, because there is only one user. Both become real the moment staff exist.

1. **A deactivated admin keeps user-management powers.** `hasPermission()` checks
   `userPerms().isActive == true` before the admin bypass, so deactivation does block feature access.
   But `isAdmin()` alone — which is what guards the `users/{uid}` create/update rules — never checks
   `isActive`. A deactivated admin can therefore still create users and grant themselves anything.
2. **`createdBy` is never pinned.** It is written by the client on sales, purchases and money entries,
   and the string `createdBy` does not appear anywhere in `firestore.rules`. Any user can write
   someone else's uid into it. The audit-trail ticket that follows this one is built on `createdBy`,
   so it has to be trustworthy before that work starts.

### Decisions already made by the manager — build to these

| Decision | Answer |
|---|---|
| Platforms | **All three** — Desktop, Android and iOS |
| First login | **The owner sets an initial password** and tells the staff member. No email invite. |
| Permissions UX | **Presets (Cashier / Manager / Accountant / Custom) with an advanced per-scope override** |
| Design | **No new design** — build against the existing app (Settings screen, desktop table patterns, `AromexTheme`) |

Note on platforms: the manager chose all three deliberately. Build **shared logic first** (use cases +
repository contract in `commonMain`), then the three UIs against it, so the rules and the Cloud
Function are exercised once rather than three times.

### Preset matrix

The presets are a starting point the owner can then adjust; "Custom" appears automatically when they
change anything. `userMgmt` is **false** in every preset — only an admin manages staff.

| Scope | Cashier | Manager | Accountant |
|---|---|---|---|
| sales | manage | manage | view |
| purchases | none | manage | view |
| inventory | view | manage | view |
| transactions | none | manage | manage |
| profiles | view | manage | view |
| balances | none | manage | view |
| reports | none | view | view |
| statistics | none | view | view |
| histories | view | manage | view |
| ledgers | none | manage | view |
| settings | none | none | none |

An **admin** bypasses per-scope permissions entirely (`hasPermission` short-circuits on `isAdmin()`),
so the screen must say so plainly when the owner promotes someone.

### Platform notes

- **Desktop has no Firebase client SDK.** It reaches Firestore through the Admin SDK and calls
  callables over their plain HTTPS endpoint using the callable protocol
  (`{"data": …}` in, `{"result": …}` out, Firebase ID token in `Authorization`). Copy the pattern in
  `desktopApp/.../data/BackendStatementPdfRepository.kt` — do not invent a new one.
- **Android / iOS** use the Firebase client SDK's callable support, as in their own
  `BackendStatementPdfRepository`.
- `UserRepository` (`sharedLogic/.../repository/UserRepository.kt`) currently exposes only
  `getUserProfile` and `getCompanyProfile`. `UserProfile` already carries
  `uid / email / displayName / role / permissions / isActive` — reuse it; do not define a parallel model.

## 🔑 Access & prerequisites

- **Firebase project access** to the dev project `aromex-june-2026` (Firestore, Auth, Functions).
  Ask the manager to add your Google account.
- **A service-account key** already lives at `firebase/secrets/aromex-june-2026-sa.json` for local
  scripts. It is git-ignored — **never commit it, never paste it into an issue or PR.**
- **An admin test login** for the dev project — ask the manager via a secure channel (password
  manager), never in the repo or a ticket.
- **A throwaway email domain for test staff** (e.g. `*.test`) so you can create and deactivate users
  freely without touching real inboxes.
- Standard toolchain: JDK 21, Android Studio, **Xcode** (iOS must compile — see Acceptance Criteria),
  Node 20 + `firebase-tools` for the functions and rules suites.
- **Confirm with the manager that the gateway change has been deployed** (`aromex-gateway`, branch
  `fix/email-index-by-project-id`). Until it ships, `/admin/email-index` rejects the `projectId`
  form and staff creation cannot complete.

## ✅ Scope / What to build

### 1. Rules — close the two gaps first
- [ ] `isAdmin()` must also require the caller's user doc to have `isActive == true`, so a
      deactivated admin loses user management immediately.
- [ ] Pin `createdBy` on create for `sales`, `purchases` and `moneyEntries`:
      `request.resource.data.createdBy == request.auth.uid`.
- [ ] Extend `firebase/tests/*.rules.test.ts` to cover both: a deactivated admin is refused a
      `users/{uid}` write, and a user cannot create a sale stamped with someone else's uid.

### 2. Cloud Functions (`firebase/functions/src/staff.ts`, registered in `index.ts`)
- [ ] `createStaffUser` (callable) — assert the caller is an **active admin** of this company, then
      create the Auth user, set custom claims (`hlCompanyId`, plus `admin: true` only when the new
      user is an admin), and write `users/{uid}`. Idempotent on email: if the Auth user already
      exists, adopt it rather than failing, and never overwrite an existing `users/{uid}`.
- [ ] `updateStaffAccess` (callable) — role and/or `isActive` changes. On role change, re-set the
      claims. On deactivate, also `auth.updateUser(uid, { disabled: true })` — otherwise the person
      can still sign in and read their own document; on reactivate, undo it.
- [ ] Both must refuse an admin acting on **themselves** for `isActive` and for removing their own
      admin role (no self-lockout, and it mirrors the existing "no self-deactivation" rule).
- [ ] **Register the email in the Central Directory** — `createStaffUser` POSTs
      `{ email, projectId }` to the gateway's `/admin/email-index` with the admin token, on both the
      create and adopt paths. **Fail the whole call loudly if this fails** (see Context); without it
      the person cannot sign in at all.
- [ ] Unit tests in `firebase/functions/src/staff.test.ts` following `statement.test.ts`'s shape
      (fake Firestore, mocked Auth) — permission gate, self-lockout refusal, claim contents,
      idempotent re-create.

### 3. Shared logic (`commonMain`)
- [ ] Extend `UserRepository`: `observeStaff(): Flow<List<UserProfile>>` (or a suspend list if a live
      feed is awkward on Desktop), `createStaff(...)`, `updateStaffAccess(...)`,
      `updatePermissions(uid, Permissions)`.
- [ ] `StaffPresets` — the matrix above as pure data, plus `presetFor(Permissions): Preset?` so the
      UI can show "Custom" when the levels match no preset. Unit-test both directions.
- [ ] Use cases enforcing `userMgmt || role == ADMIN` before any write, throwing
      `PermissionDeniedException` exactly as the existing use cases do.
- [ ] Validation: non-blank display name, well-formed email, password minimum 8 characters — as a
      pure shared function so all three platforms reject identically.

### 4. UI — Desktop, Android, iOS
- [ ] A **Staff** section, reachable from Settings, listing each person with name, email, role, a
      preset/Custom label, and active state. Hidden entirely for a user without `userMgmt`/admin.
- [ ] **Add staff**: name, email, initial password (with a Generate button), role, preset picker, and
      an expandable Advanced panel with all eleven scopes.
- [ ] **Edit staff**: change preset/permissions/role, deactivate and reactivate. Email is immutable —
      show it read-only and say why.
- [ ] After the owner sets the initial password, show it once with an explicit "copy this and give it
      to them — it won't be shown again" affordance.
- [ ] Promoting someone to admin requires a confirmation that states plainly that an admin bypasses
      every per-scope permission.
- [ ] Deactivating requires confirmation and explains that their history is kept.
- [ ] Tell the person doing the promoting/demoting that **the change reaches that user's app when
      they next sign in** — and force-refresh the token for the *current* user where applicable.

## 🎯 Acceptance Criteria

- [ ] An owner can create a staff member on Desktop, Android **and** iOS; the person signs in with
      the initial password immediately and lands in the app with their permissions applied.
- [ ] That sign-in is verified **from a signed-out app**, so `/resolve-company` is genuinely
      exercised — the blocker this ticket was amended for is invisible if you only test a session
      that is already signed in.
- [ ] If the Central Directory write fails, staff creation reports a clear failure rather than
      success, and re-running it completes the registration.
- [ ] A Cashier-preset staff member can ring up a sale and **cannot** open supplier balances,
      settings, or the staff screen itself — verified in the running app, not only by unit test.
- [ ] A staff member without `userMgmt` never sees the Staff section on any platform.
- [ ] Changing someone's permissions takes effect for them after they sign out and back in, and the
      UI told the owner that would be required.
- [ ] A deactivated staff member cannot sign in, and a deactivated **admin** can no longer create or
      edit users (the gap fixed in Scope 1) — both covered by rules tests.
- [ ] A user cannot record a sale stamped with another user's `createdBy` — covered by a rules test.
- [ ] An admin cannot deactivate themselves or remove their own admin role, on any platform.
- [ ] Re-running create with an email that already has an Auth user does not fail and does not
      overwrite that person's existing permissions.
- [ ] `users/{uid}` is never deleted by any path.
- [ ] No secret, password or service-account key is committed, logged, or included in an error message.
- [ ] All suites green: `:sharedLogic:jvmTest`, `:desktopApp:test`, `:androidApp:compileDebugKotlin`,
      **an `xcodebuild` pass for iOS**, `firebase/functions` tests, and `firebase` rules tests.
- [ ] Every UI standard below is met on all three platforms.

## 🖼️ UI standards

- [ ] **No new design is provided for this ticket** — build against the existing app: the Settings
      screen, the desktop table/toolbar patterns already in `MoneyScreen` / `StockHistoryScreen`, and
      `AromexTheme` tokens. Reuse and extend the shared components; no one-off colors or sizes.
- [ ] **Light and dark themes** — every color from a theme token defined in both; verify in both.
- [ ] **Native components** — Compose Material on Android, SwiftUI/HIG on iOS, Compose-Desktop
      equivalents. Don't hand-roll what the platform provides. If something can't be done natively,
      say so and proceed with the closest native approach.
- [ ] **Edge-to-edge with correct safe areas** on mobile — nothing under the status bar, notch, home
      indicator, or Android gesture/nav bar.
- [ ] **Responsive** — small phone → tablet, both orientations; desktop resizable with a sensible
      minimum and a layout that reflows (the app's minimum window is 420dp wide — the Contacts top bar
      already has a `BoxWithConstraints` breakpoint for this; follow it).
- [ ] **Correct truncation** — long names and emails ellipsize cleanly rather than clipping or
      pushing the layout.
- [ ] **Keyboard**: email keyboard for email, secure entry for the password, autocapitalize off for
      email/password and on for names; **Next** moves between fields and **Done** submits; the focused
      field stays visible above the keyboard.
- [ ] **Loading, empty, error and disabled states** for the list and both forms; controls disabled
      with progress shown during async work; errors surfaced inline, never as a raw dump.
- [ ] **State preserved** across rotation, process death and desktop resize — a half-filled Add Staff
      form must not be lost.
- [ ] **Accessibility** — labels on every control, logical focus order, dynamic type / font scaling
      without breaking layout, ~48dp/44pt touch targets, WCAG AA contrast.
- [ ] **No hardcoded user-facing strings** — everything through `Strings` / `EnglishStrings`.
- [ ] Follow `/kmp-arch`: shared model/use cases in `commonMain`, native UI and ViewModels per
      platform, **no business logic in the UI**.

## 🚫 Out of scope

- **The audit trail** — a separate follow-up ticket. This one only makes `createdBy` trustworthy.
- Self-service password reset or "forgot password" for staff; email invites.
- Deleting users (the rules forbid it by design — deactivate instead).
- Editing a staff member's email after creation.
- Multi-company users, or moving a user between companies.
- The gateway change itself — already implemented on `aromex-gateway`
  `fix/email-index-by-project-id`; this ticket only calls the endpoint.
- Changing the eleven scopes themselves, or what any of them gates.

## 🔗 Dependencies

None blocking. Scope 1 (rules) should land before or with Scope 2, since the Cloud Function relies on
the tightened `isAdmin()`.

## 🔗 References

- `sharedLogic/src/commonMain/kotlin/com/humblesolutions/aromex/model/Permissions.kt` — the scopes
- `sharedLogic/src/commonMain/kotlin/com/humblesolutions/aromex/model/UserSession.kt` — role + session
- `sharedLogic/src/commonMain/kotlin/com/humblesolutions/aromex/repository/UserRepository.kt` — `UserProfile`
- `firebase/firestore.rules` — the `users/{uid}` block, `isAdmin()`, `belongsToThisCompany()`, `hasPermission()`
- `firebase/scripts/setup-project.ts` — **the reference implementation** for create-user + claims + doc
- `firebase/functions/src/statement.ts` — the callable pattern and its server-side permission gate
- `desktopApp/src/main/kotlin/com/humblesolutions/aromex/data/BackendStatementPdfRepository.kt` —
  how Desktop calls a callable without the Firebase client SDK
- `docs/PRD.md` §7.2 and §7.4 — the Central Directory requirement and its schema
- `aromex-gateway` `src/routes/provisioning.ts` — `/admin/email-index`, both identifier forms
- `firebase/functions/src/syncWorker.ts` — `getHlToken(...)`, the existing gateway call pattern
- `CLAUDE.md` / `/kmp-arch` — architecture rules

## 🚀 Kickoff prompt

```
/start-ticket <#>
```
