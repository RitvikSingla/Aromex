# [M1] Android login — email-discovery sign-in (Central Directory + Firebase Auth)

> **Platform: Android first** — iOS & Desktop come later, once Android is stable.
> ⚠️ **Follow the `/kmp-arch` skill strictly** — it is the authority on this KMP app's layering,
> naming, and method conventions. Run it before planning and do not deviate. `CLAUDE.md` is the
> short version; `/kmp-arch` is the full rulebook.
> Milestone: **M1 — Auth & Onboarding**.

## 📖 Story / Why
This is the **first screen of the actual app** and the front door to everything else. A user opens
Aromex, enters their **email + password**, and lands inside their company's workspace. Because every
client company has its **own Firebase project**, the app can't know which backend to talk to until it
asks our gateway *"who owns this email?"* — this is **email-based workspace discovery** (no company
code), exactly like Slack / Google Workspace.

The gateway (built + deployed in M1-01/03/04) and the per-client Firebase project + security rules
(M1-07) already exist and are live. This ticket builds the **Android client side of login** on top of
them: resolve the company, sign in to the right Firebase, load the user, and land on a placeholder home.

## 🧭 Context
**The login flow (PRD §7.1):**
1. User enters **email + password**.
2. App → gateway **`POST /resolve-company`** with the email → the matching company's **public Firebase
   config** (`companyId` + `firebaseConfig`). Unknown email → empty list. >1 company → "choose your company".
3. App **initializes a Firebase app instance at runtime** from that config and **signs in with Firebase
   Auth** (email + password) → Firebase user + ID token.
4. App **reads `users/{uid}`** in that project → confirms `isActive`, captures `role` + `permissions` +
   `companyId` / `hlCompanyId` into an in-memory **session**.
5. App lands on a **placeholder authenticated Home** showing the signed-in email + role + **Sign out**.

**Live endpoints / backends you build against:**
- Gateway base URL: **`http://68.183.86.89/gateway/`** (public, not a secret).
  - `POST /gateway/resolve-company` — body `{"email":"..."}` → `{"companies":[{"companyId":"...","firebaseConfig":{...}}]}`.
    Unknown/malformed email → `{"companies":[]}` (HTTP 200, by design — no enumeration signal).
- Test Firebase project: **`aromex-june-2026`** (Auth + Firestore), with the first-admin user from M1-07.

**Two technical realities to get right:**
- **Dynamic Firebase init (no bundled `google-services.json`).** This app is multi-tenant: **no single
  client is baked in at build time.** You must initialize a **named / secondary `FirebaseApp`** at runtime
  from the `firebaseConfig` the gateway returns (`FirebaseOptions.Builder().setApiKey(...).setApplicationId(appId).setProjectId(...)...`),
  then get `FirebaseAuth` / `FirebaseFirestore` **from that app instance** — never the default instance.
- **Gateway is HTTP-only for now** (HTTPS / "Phase B" is pending a domain). Android blocks cleartext by
  default. For the **dev build only**, add a `network_security_config.xml` that permits cleartext **to
  `68.183.86.89` only**, with a clear `TODO` to remove it once the gateway is on HTTPS. Note: the
  **password never transits the gateway** (only the email does); Firebase Auth + Firestore talk to Google
  over HTTPS regardless — so credentials stay safe. The cleartext exception covers only the
  email-resolution call during integration testing.

## 🏛️ Architecture — follow `/kmp-arch` exactly
Build in this order (per `/kmp-arch` + `CLAUDE.md`), all repository methods `suspend`. **No `expect`/`actual`,
no DI framework (manual DI), no UI in `sharedUI`, no platform/Firebase imports in `sharedLogic`:**
- **`sharedLogic/model`** — e.g. `ResolvedCompany` (companyId + Firebase config fields), `UserSession`
  (uid, email, displayName, role, permissions, companyId, hlCompanyId, isActive), permission types, a
  `LoginError` sealed type.
- **`sharedLogic/repository` (interfaces only)** — `CompanyDirectoryRepository.resolveCompanies(email)`,
  `AuthRepository.signIn(config, email, password)` / `signOut()` / `currentUid()`,
  `UserRepository.getUserProfile(uid)`.
- **`sharedLogic/usecase`** — `LoginUseCase` (resolve → sign in → load profile → check `isActive` →
  return `UserSession`), `RestoreSessionUseCase` (already signed in → rebuild session), `LogoutUseCase`.
  Depend **only** on the interfaces.
- **`androidApp` repo impls** — gateway HTTP client (`/resolve-company`), dynamic-config Firebase Auth
  sign-in, `users/{uid}` Firestore read.
- **`androidApp` `LoginViewModel`** — `StateFlow<LoginUiState>`, manual DI.
- **`androidApp` UI** — `LoginScreen` (Compose) + placeholder `HomeScreen`; navigation between them.

## 🔑 Access & prerequisites
> PM provides via secure channel — ask before starting. Don't commit anything.
- **Test login — provisioned & verified ✅:** `ansh.bajaj2611@gmail.com` is set up as an admin in
  `aromex-june-2026`. It resolves via the gateway (`aromextest`), signs in with email + password, and has
  a conforming `users/{uid}` doc (`role: admin`, all permissions `manage`, `isActive: true`). End-to-end
  resolve + sign-in were confirmed working. **PM sends the password via one-time-secret** (rotate on first login).
- Gateway base URL (`http://68.183.86.89/gateway/`) — in this ticket, not a secret.
- **No service-account keys, no HL credentials needed** — client login uses only the public Firebase
  config + the Firebase Auth client SDK.

## ✅ Scope / What to build
- [ ] Shared `model` + `repository` interfaces + `usecase` for the login flow (per `/kmp-arch`; zero
      platform/Firebase imports in `sharedLogic`).
- [ ] Android repo impls: gateway `/resolve-company` client; dynamic-config Firebase Auth sign-in;
      `users/{uid}` Firestore read.
- [ ] `LoginScreen`: email + password fields, sign-in button, loading state, inline error display.
- [ ] **Multi-company chooser**: if `/resolve-company` returns >1 company, show a "choose your company"
      screen before sign-in.
- [ ] **Inactive guard**: if `users/{uid}.isActive == false`, sign the user back out and show
      "account disabled" — do not enter.
- [ ] Placeholder **Home** screen (signed-in email + role + **Sign out**).
- [ ] **Session persistence**: on launch, if Firebase already has a signed-in user, rebuild the session
      and route straight to Home; **Sign out** returns to Login.
- [ ] Dev-only `network_security_config.xml` permitting cleartext to the gateway host, with a removal `TODO`.

## 🎯 Acceptance Criteria
- [ ] Strictly follows `/kmp-arch` layering: shared model/interfaces/use-case have **no** platform or
      Firebase imports; Android implements the interfaces; ViewModel uses `StateFlow` + manual DI; UI lives
      only in `androidApp`; nothing added to `sharedUI`; no `expect`/`actual`.
- [ ] Known email + correct password → resolves company via gateway → signs into the **correct** Firebase
      project → reads `users/{uid}` → lands on Home showing the signed-in email + role.
- [ ] Email mapped to 2+ companies → "choose your company" screen appears before sign-in.
- [ ] `isActive: false` user is blocked (signed out, clear message), never reaches Home.
- [ ] Clear, distinct error messages for: **unknown email** (no workspace), **wrong password / invalid
      credentials**, **network / gateway unreachable**, and **Firebase sign-in failure**.
- [ ] Loading indicator during the async flow; sign-in button disabled while in flight.
- [ ] Already-signed-in user skips Login on launch and lands on Home; Sign out returns to Login.
- [ ] Firebase is initialized **dynamically from the resolved config** (a named/secondary `FirebaseApp`);
      no client-specific `google-services.json` is committed.
- [ ] No secrets committed; cleartext HTTP is limited to the gateway host in a **dev-only** network-security
      config with a `TODO` to remove once HTTPS lands.
- [ ] Verified end-to-end on an Android emulator/device against the **live gateway** + **`aromex-june-2026`**
      with the PM-provided test login.

## 🚫 Out of scope
- **iOS & Desktop login** — do after Android is stable (separate tickets).
- **HL token brokering** (`POST /hl-token`) — fetched later, when the first money operation needs it
  (M1-05). Login does **not** call it.
- **Permission enforcement / screen gating** — capture `permissions` into the session, but don't gate
  features yet (separate ticket).
- **Real home/dashboard** — placeholder only.
- **`lastLoginAt` write** — current Firestore rules make `users/{uid}` admin-write-only, so the client
  can't update it; defer to a Cloud Function or a rules carve-out.
- **Password reset / "forgot password"** — separate ticket.
- **Gateway HTTPS (Phase B)** — infra follow-up; this ticket just isolates the dev-only cleartext exception.

## 🔗 Dependencies
- Builds on **M1-01/03/04** (gateway live at `http://68.183.86.89/gateway/`) and **M1-07** (per-client
  Firebase + rules + first admin in `aromex-june-2026`). All merged.

## 📚 References
- `docs/PRD.md` §7.1 (login flow), §7.2–7.3 (users / permissions schema).
- `CLAUDE.md` — architecture, "Two backends", auth rules. **`/kmp-arch` skill — the authority on layering/methods.**
- Gateway contracts: `handoffs/ticket-1.md`, `handoffs/ticket-3.md`, `handoffs/ticket-4.md` (endpoints,
  response shapes, public URL).
- `firebase/SCHEMA.md` (`users/{uid}` shape) and `firebase/firestore.rules` (what the client may read/write).

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
