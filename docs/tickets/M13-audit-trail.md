---
title: "[M13] Audit trail — who changed what, when, and from what to what"
labels: []
---

## 📖 Story / Why

Once #112 lands, a shop has more than one pair of hands in the app. The owner will want to answer
questions that have no answer today: *who deleted that phone from stock?* *who changed the customer's
balance?* *who edited this product's price last Tuesday?*

Right now the app records **who created** a sale, a purchase or a money entry — but nothing at all
about **edits and deletions**, which is precisely where a problem would hide. `updateEntity`,
`archiveEntity`, `updateProduct`, `archiveProduct`, `archiveSerial`, `setSerialStatus`, `saveRule`,
`archiveRule`, `updateTaxNumber` and `updatePhones` all leave no trace whatsoever: no actor, no
before, no after.

The one exception is company settings, which already has a proper append-only log. **This ticket
generalises that pattern to everything else** — and does it server-side, so the record can't be
skipped by the person being recorded.

## 🧭 Context

### The precedent to generalise — read this first

`companySettingsChanges/{changeId}` already is a small, correct audit trail. Its rules
(`firebase/firestore.rules`) are the shape to copy:

```
match /companySettingsChanges/{changeId} {
  allow get, list: if isAdmin() && belongsToThisCompany();
  allow create: if isAdmin() && belongsToThisCompany()
    && request.resource.data.changedBy == request.auth.uid   // can't log under someone else's name
    && request.resource.data.field is string
    && request.resource.data.newValue is string;
  allow update, delete: if false;                            // append-only, for everyone, forever
}
```

and its model is `sharedLogic/.../model/CompanySettingsChange.kt` — `field` / `oldValue` / `newValue`
/ `changedBy` / `changedByName` / `changedAt`, with `field` stored in plain words rather than derived
so a later property rename can't rewrite history. **Keep that idea.**

### The crux: a Firestore trigger does not know who did it

The manager chose a **server-written** trail: a Cloud Function trigger fires on every write to the
watched collections and writes the entry itself, so a modified client cannot silently skip it.

The catch that will cost you a day if you don't plan for it: **Firestore triggers carry no auth
context.** `onDocumentWritten` receives the before and after snapshots and nothing about the caller.
The actor must therefore be *on the document*, and pinned by rules so it can't be forged:

- `createdBy` — already written on sales, purchases and money entries, and **being pinned to
  `request.auth.uid` by rules in #112**.
- `updatedBy` — **does not exist yet**. Every mutable collection needs it, written on every update
  and pinned the same way.

Without both, the trail either can't name anyone or can be made to name the wrong person.

### System writes will flood the log unless you exclude them

The Cloud Functions themselves write to these collections constantly: `syncWorker` stamps
`syncStatus`, `hlSyncedAt`, `invoiceNumber`, `invoiceUrl`, `hlSaleId`, `hlInvoiceId` on every sale as
it posts to Humble Ledger; the reconcile sweep does it again. Those writes will fire your trigger.

If you log them, the owner's audit screen fills with entries nobody made, and the real ones become
impossible to find. **Diff the before/after and drop the entry when every changed field is a
system/sync field.** Keep that exclusion list in one named constant, not scattered through the
triggers.

### What already exists — don't duplicate it

- **Humble Ledger already holds an immutable record of every financial posting**, with `actorRef`
  carrying the uid that caused it. Don't re-derive money math into the audit log; log the *app*
  action and let the ledger remain the authority on the money. See
  `firebase/functions/src/syncWorker.ts`.
- **Sales voids** already record `voidRequestedBy`, `voidReason`, `voidedAt`.
- **Batch reversals** already record their own state (`firebase/functions/src/purchaseReversal.ts`).
- The app **archives rather than hard-deletes** for contacts, products and serials, so a before/after
  diff is available. The one true removal is the per-unit delete on the inventory view — for that,
  the trigger's `before` snapshot is the *only* surviving record, so it must capture the whole
  document, not a diff.

### Decisions already made by the manager — build to these

| Decision | Answer |
|---|---|
| Trust model | **Server-written by Firestore triggers.** Append-only; no client may ever write an entry. |
| Coverage | **Every data change** — sales, voids, purchases, batch reversals, money entries and reversals, contacts, inventory (products, units, status, archives, deletes), commission rules, settings, staff/permissions. **No read logging.** |
| Who can read it | **Admins only** — no new permission scope |
| Viewer | **All three platforms** — Desktop, Android and iOS |

Note on the viewer: capture happens everywhere regardless of platform. The manager chose to put the
*screen* on all three deliberately. Build the shared filter/query logic once in `commonMain` and keep
the three UIs thin; a dense table is the hard part on a phone, so design the mobile view as a list of
cards rather than a squeezed grid.

### Retention

Keep everything for now — a shop generates on the order of hundreds of entries a week, which is
nothing at Firestore's scale. Do **not** build a purge or an export in this ticket; if it ever
matters, it's a separate decision about legal retention. Say so in the code rather than silently
assuming forever.

## 🔑 Access & prerequisites

- **Firebase project access** to the dev project `aromex-june-2026` (Firestore, Functions, and the
  emulator suite for rules tests). Ask the manager to add your Google account.
- **An admin test login** and **at least one non-admin staff login** — the second one is what makes
  this testable at all, since a trail with one actor proves nothing. Create them via #112 once it
  lands; get credentials from the manager via the password manager, never from the repo.
- The service-account key at `firebase/secrets/aromex-june-2026-sa.json` is git-ignored — **never
  commit it or paste it anywhere.**
- Node 20 + `firebase-tools` (functions tests and `npm run test:rules`), JDK 21, Android Studio, and
  **Xcode** — iOS must compile.

## ✅ Scope / What to build

### 1. Rules — make the actor honest, and the log untouchable
- [ ] Add `updatedBy` pinned to `request.auth.uid` on update for every mutable collection:
      `entities`, `products`, `serials`, `commissionRules`, `sales`, `purchases`, `moneyEntries`.
- [ ] New `auditLog/{entryId}`: `get, list` for admins of this company; **`create`, `update` and
      `delete` all `if false`.** The trail is written only by the Admin SDK, which bypasses rules —
      no client, not even an admin, may write or alter an entry.
- [ ] Rules tests in `firebase/tests/`: a client cannot create an audit entry; an admin cannot update
      or delete one; a non-admin cannot read them; a user cannot set `updatedBy` to another uid.

### 2. Cloud Functions — the trail itself (`firebase/functions/src/audit.ts`)
- [ ] One `onDocumentWritten` trigger per watched collection, all delegating to a single shared
      `recordAudit(before, after, collection)` so the logic exists once.
- [ ] Classify the change as `CREATED` / `UPDATED` / `DELETED` from the presence of the snapshots.
- [ ] Diff before/after into a list of changed fields with old and new values, rendered as display
      strings the way `SettingsAudit.diff` already does for settings.
- [ ] **Drop the entry entirely when every changed field is a system/sync field** (see Context) —
      one named exclusion constant, unit-tested.
- [ ] For a hard delete, store the whole `before` document, since nothing else will survive.
- [ ] Resolve `updatedBy`/`createdBy` to a display name from `users/{uid}` and **store the name on
      the entry** — a uid is unreadable a year later, and the person may since have been deactivated.
      Fall back to the uid when the user document is gone.
- [ ] **Never log a secret.** #112's staff creation carries an initial password; audit entries must
      redact any field named like a password/token/key. Unit-test that redaction explicitly.
- [ ] `firebase/functions/src/audit.test.ts` following `statement.test.ts`'s shape: create/update/
      delete classification, the system-field exclusion, name resolution and its fallback, redaction,
      and a bulk write (see below).
- [ ] **Bulk writes:** adding stock writes many serial documents at once, which fires the trigger once
      per document. Group them into one entry per batch where a batch id exists on the document, so
      adding 50 phones reads as one action and not fifty. Where no batch id exists, log individually.

### 3. Shared logic (`commonMain`)
- [ ] `AuditEntry` model — id, action, collection, record id, a human label for the record, the
      changed fields with old/new, actor uid, actor display name, timestamp.
- [ ] `AuditFilter` — by actor, by date range, by action, by area (sales / inventory / money /
      contacts / settings / staff) — and a repository contract that pages it.
- [ ] `ObserveAuditLogUseCase` gated on the caller being an admin, throwing
      `PermissionDeniedException` exactly as the existing use cases do.
- [ ] Unit tests for the filter/paging logic.

### 4. UI — Desktop, Android, iOS
- [ ] An **Activity / Audit** screen, admin-only, hidden entirely for everyone else on all three
      platforms.
- [ ] A reverse-chronological list: when, who, what happened, and which record.
- [ ] Filters: date range, person, area, action — reusing the existing toolbar controls
      (`SearchBox`, `DateRangeChip`, `ToolbarChip`) so it matches Money and Stock History.
- [ ] Tapping an entry shows the field-level detail: **what it was → what it became**.
- [ ] Paging with a "Load more" affordance, matching the statement and stock-history screens. Never
      silently cap the list.
- [ ] Empty, loading and error states; on mobile use cards rather than a squeezed table.

## 🎯 Acceptance Criteria

- [ ] A staff member edits a contact on a phone; the owner sees an entry naming **that person**, the
      field, and the before/after — without the phone having written the entry itself.
- [ ] A staff member deletes a phone from inventory; the entry preserves the whole deleted record.
- [ ] Recording a sale produces **one** entry, not one per subsequent sync write — the HL sync
      stamping `syncStatus` / `invoiceNumber` / `hlSaleId` adds nothing to the log.
- [ ] Adding a batch of 50 phones produces one entry, not fifty.
- [ ] No client can create, edit or delete an audit entry — including an admin — proven by rules tests.
- [ ] A non-admin cannot read the audit log on any platform, and never sees the screen.
- [ ] An entry created by a user who has since been deactivated still shows their name.
- [ ] No audit entry ever contains a password, token or key, proven by a unit test.
- [ ] A user cannot cause an entry attributed to a different user (`updatedBy` pinning), proven by a
      rules test.
- [ ] All suites green: `:sharedLogic:jvmTest`, `:desktopApp:test`, `:androidApp:compileDebugKotlin`,
      an **`xcodebuild`** pass for iOS, `firebase/functions` tests, and `firebase` rules tests.
- [ ] Every UI standard below is met on all three platforms.

## 🖼️ UI standards

- [ ] **No new design is provided** — build against the existing app: the Money and Stock History
      table/toolbar patterns, and `AromexTheme` tokens. Reuse the shared components; no one-off
      colors or sizes.
- [ ] **Light and dark themes** — every color from a token defined in both; verify in both.
- [ ] **Native components** — Compose Material on Android, SwiftUI/HIG on iOS, Compose-Desktop
      equivalents. If the design can't be done natively, say so and take the closest native approach.
- [ ] **Edge-to-edge with correct safe areas** on mobile — nothing under the status bar, notch, home
      indicator or Android gesture/nav bar.
- [ ] **Responsive** — small phone → tablet, both orientations; desktop resizable down to the app's
      420dp minimum with a layout that reflows (follow the `BoxWithConstraints` breakpoint already in
      the Contacts top bar).
- [ ] **Correct truncation** — long values and names ellipsize cleanly rather than clipping or
      pushing the layout. A long before/after value wraps in the detail view, not the list row.
- [ ] **Loading, empty, error and disabled states** for the list and every filter; errors inline,
      never a raw dump.
- [ ] **State preserved** across rotation, process death and desktop resize — filters and scroll
      position survive.
- [ ] **Accessibility** — labels on every control, logical focus order, dynamic type without breaking
      layout, ~48dp/44pt touch targets, WCAG AA contrast.
- [ ] **No hardcoded user-facing strings** — everything through `Strings` / `EnglishStrings`.
- [ ] Follow `/kmp-arch`: shared model/use cases in `commonMain`, native UI and ViewModels per
      platform, **no business logic in the UI**.

## 🚫 Out of scope

- **Read logging** — who *viewed* what. Explicitly excluded by the manager.
- Retention policy, purging, or archiving old entries.
- Exporting the trail (CSV/PDF) or emailing it.
- Alerting on suspicious activity.
- A separate `audit` permission scope — admins only for now.
- Reconstructing history for changes made *before* this ticket ships; the trail starts empty and
  the screen should say so rather than implying nothing ever happened.

## 🔗 Dependencies

- **#112 (Staff & permissions) must land first.** It pins `createdBy`, and it creates the second
  actor without which none of this is testable.

## 🔗 References

- `firebase/firestore.rules` — the `companySettingsChanges` block is the pattern to generalise
- `sharedLogic/.../model/CompanySettingsChange.kt` — the existing entry model
- `sharedLogic/.../usecase/CompanySettingsUseCases.kt` — `SettingsAudit.diff`, the existing differ
- `desktopApp/.../data/BackendCompanySettingsRepository.kt` — how the settings log is written today
- `firebase/functions/src/syncWorker.ts` — the system/sync writes to exclude, and HL's `actorRef`
- `firebase/functions/src/statement.ts` — Cloud Function + test shape to follow
- `CLAUDE.md` / `/kmp-arch` — architecture rules

## 🚀 Kickoff prompt

```
/start-ticket <#>
```
