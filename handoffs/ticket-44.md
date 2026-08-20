# Handoff — Ticket #44

**Ticket:** #44 — [M4] Inventory — T2: Firebase spine (security rules + composite indexes + race-safe transaction contract)

## Summary
This ticket supplies the **guardrails and contract** for M4 inventory — no app code, no server, no Cloud Function (M4 is Firebase-only). Extended `firebase/firestore.rules` with four new `match` blocks — `products`, `serials`, `attributes`, `imeiIndex` — gated on the caller's `inventory` permission (`view`→read, `manage`→write), reusing the existing `hasPermission()` / `belongsToThisCompany()` helpers. These rules are the **mobile backstop**; they exist to *permit* the atomic client-side add-stock transaction (create SKU + serial + imeiIndex all-or-nothing) for a `manage` user and *deny* everyone else — the transaction itself is built in T3/T4. Added the composite indexes the inventory queries need (`firebase/firestore.indexes.json`, previously empty). Added an `@firebase/rules-unit-testing` emulator suite (`npm run test:rules`, **30 passing tests**) that proves the rules against a real Firestore emulator, and documented the collections + the empty-vocabulary (no-seed) decision.

## Files changed
**Config / rules (`firebase/`)**
- `firestore.rules` (+93/−0 net additions) — Added `products`, `serials`, `attributes`, `imeiIndex` match blocks. Products/serials are history-bearing → `delete: false` (archive via `isActive`); `imeiIndex` allows `create + delete` (the in-stock guard is released on sold/archive) and enforces `doc id == imei`; write-validation on `trackingMode`/`status`/`condition`/attribute `type` enums, money-as-string, and `productId == doc id` (the atomic-skuKey guarantee). Header comment now points at the `docs/SCHEMA.md` Part 2 transaction contract so T3/T4 implement identical steps.
- `firestore.indexes.json` — Five composite indexes: `serials`(productId,status,isActive), `serials`(status,isActive), `serials`(location.attributeId,status,isActive), `attributes`(type,isActive), `attributes`(type,parentId).
- `firebase.json` — Added the Firestore emulator config (port 8080, UI off, singleProjectMode) so the rules tests can run.
- `package.json` — Added dev deps `@firebase/rules-unit-testing`, `firebase`, `vitest`; scripts `test:rules` and `deploy:indexes`.
- `README.md` — Documents the inventory rules, the composite indexes, the single-field auto-index note, the no-seed decision, and how to run the emulator tests.

**Tests (`firebase/`)**
- `tests/inventory.rules.test.ts` (+311) — Emulator rules suite (23 tests).
- `vitest.config.ts` — Scopes vitest to `tests/` so it doesn't pick up the `functions/` codebase test.

*(`firebase/package-lock.json` also changed — the pinned dependency tree for the new dev deps.)*

## How to test
From `firebase/`:
```bash
cd firebase
npm install
npm run test:rules      # boots the Firestore emulator (needs Java) and runs the 23 rules tests
```
Expected: `Test Files 1 passed (1) · Tests 23 passed (23)`. The `PERMISSION_DENIED` lines in stderr are the **expected** `assertFails` denials, not failures.

Deploy (run from `firebase/`, requires Firebase CLI auth to the test project):
```bash
cd firebase
npm run deploy:rules:dry                                  # dry-run compile check
firebase deploy --only firestore:rules,firestore:indexes # actual deploy
```

## Acceptance criteria
- ✅ **`firestore.rules` + `firestore.indexes.json` deploy cleanly** — rules compile successfully and indexes are valid (verified: the rules load in the emulator and all 23 tests pass; `indexes.json` is well-formed). ⚠️ See "Open questions" — the *authoritative* deploy of these files to the live project still needs to be run from `firebase/` (an earlier deploy was accidentally run from the repo root against stale duplicate files; those duplicates have been removed).
- ✅ **Rules gate all four inventory collections on `inventory`** (view→read, manage→write) and permit the atomic add-stock transaction for manage while denying view/none — proven by `tests/inventory.rules.test.ts` ("add-stock atomic write" describe block: manage succeeds, view + none fail).
- ✅ **`imeiIndex` allows create + delete for manage and enforces `doc id == imei`** — proven by the `imeiIndex` describe block (create with matching id succeeds, mismatch fails, manage delete succeeds, view delete fails).
- ✅ **Composite indexes exist** for `observeInStockSerials`, per-SKU drill-in, and vocab/model-by-brand queries — the five indexes in `firestore.indexes.json`.
- ✅ **No Cloud Function / gateway / HL introduced** — the diff touches only rules, indexes, and test tooling; the transaction contract is documented and referenced from the rules header.
- ✅ **Vocabularies are not seeded** — no seed data added; the empty-start decision is recorded in the rules comment on `attributes`, in `firebase/README.md`, and here.

## Review hardening (post-review, same PR)
Manager review surfaced that the `update` rules were looser than `create`. Tightened both, all rule-enforceable at the backstop:
- **`serials` update** (`firestore.rules`) — `serialId`, `productId`, and **`imei` are now immutable** on update; only lifecycle/`condition`/`cost`/`location` may change. This closes the serial↔imeiIndex desync path a direct `imei` edit could open.
- **`products` update** (`firestore.rules`) — `productId`, `trackingMode`, and the SKU-defining **`attributes` map are now immutable** (via `diff().affectedKeys().hasAny([...])`); only `defaultSellingPrice` + `isActive` are editable. A product can no longer hold attributes that contradict its own skuKey.
- **Decision — IMEI correction = void + re-add (Option A, chosen by PO).** An IMEI is never edited in place; a mistyped one is corrected by voiding the unit (archive + release its `imeiIndex`) and re-adding a fresh unit. The rules header and `docs/SCHEMA.md` (serials write-path) were updated to drop the old in-place "CORRECT IMEI" step. 7 new emulator tests cover the immutability guards (30 total).

## Deviations / decisions
- **`products (isActive)` and `serials.imei` single-field indexes were NOT added to `firestore.indexes.json`.** The ticket listed `products (isActive ASC)` under composite indexes, but Firestore **auto-creates single-field indexes** and rejects a one-field entry in the composite `indexes` array on deploy. They are documented as auto-indexed (same as the ticket already notes for `imeiIndex`) rather than declared. No query is left unindexed by this.
- **Verification via an automated emulator harness** (`@firebase/rules-unit-testing` + `vitest`) rather than manual Rules Playground clicks — chosen for reproducibility and so the proof lives in the repo. This adds three dev dependencies to `firebase/package.json`. `@firebase/rules-unit-testing` was pinned to `^4.0.1` (not the older `3.x`) because 4.x is the line compatible with `firebase@11`.
- **Duplicate root Firebase config removed.** An earlier `firebase init`/`deploy` from the repo root created root-level `firestore.rules` / `firestore.indexes.json` / `firebase.json` / `.firebaserc` (stale copies without the inventory work). These were deleted so the canonical config stays solely in `firebase/` (the location #7 established). They were untracked and are not part of this branch.

## Open questions / follow-ups
- **Re-deploy the tightened rules.** The rules were deployed once from `firebase/` (clean), but the review-hardening changes above landed after that — run `firebase deploy --only firestore:rules` from `firebase/` again to make them live. (Indexes are unchanged.)
- **Transaction implementation is T3 (mobile) / T4 (desktop)** — this ticket only guarantees the rules + indexes permit it. The abort-on-duplicate-IMEI path is *demonstrated* in the test suite but *implemented* in T3/T4. Per the Option A decision, T3/T4 implement IMEI correction as **void + re-add**, not an in-place `imei` edit.
