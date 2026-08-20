# Handoff — Ticket #97

**Ticket:** #97 — [M9] Commission on intake — pay a party per phone added to a location

## Summary
Shops can now record a **standing arrangement** — *whenever phones land at a location, a party earns
per phone* — as a rule the system enforces, so the obligation is recorded the moment the stock is.
Two pieces ship: an **admin-only Commission rules** list (location → payee → fixed-per-unit or
percent-of-cost, several payees per location, on/off switch, inline add of a new payee or location),
and a **commission section in the existing Add-Inventory save dialog** that appears only when a rule
matches — one block per payee showing *how the figure was reached*, each decided separately as **Add
to balance** (accrue) or **Give now** (a Cash + Bank split like the supplier UI). Every commission
is written **in the same Firestore transaction as the stock and its purchase** (extending the #58
invariant: never stock without its commission obligation), then posted to Humble Ledger by a new
`onCommissionWrite` Cloud Function — accrue via `/customer-purchases` against a `Commission` EXPENSE
account, then a `/customer-payouts` per non-zero cash/bank leg — all idempotent on deterministic
`sourceId`s. Money stays decimal strings throughout; commission is **earned on arrival only**, never
on a later location move. Per the requester, this was built on **all three platforms** (Desktop,
Android, iOS), not Desktop-only as the ticket originally scoped.

## Files changed

### sharedLogic (shared Kotlin)
- `model/RateKind.kt` **(new)** — `PER_UNIT | PERCENT_OF_COST` with wire helpers.
- `model/CommissionRule.kt` **(new)** — `CommissionRule` + `CommissionRuleInput` (admin-managed rule).
- `model/CommissionLine.kt` **(new)** — a rule's *proposed* line at intake (shows how it was reached).
- `model/Commission.kt` **(new)** — `Commission` + `CommissionInput`; carries `amount` (accrued) plus
  `paidCash`/`paidBank` decimal-string give-now split (no `PayoutMethod` — replaced by cash/bank).
- `usecase/CommissionCalculator.kt` **(new)** — pure `(units + active rules) → per-payee lines`,
  grouped by location first (no cross-contamination); per-unit `count × rate`, percent `rate × Σcost`
  via `Money.multiplyRate` — zero floats.
- `repository/CommissionRuleRepository.kt` **(new)** — Firestore contract (observe/save/archive).
- `repository/CommissionRuleObserve.kt` **(new)** — `commissionRulesCallbackFlow` adapter so iOS can
  feed a native listener into the shared observe path (mirrors `entitiesCallbackFlow`).
- `usecase/CommissionRuleUseCases.kt` **(new)** — `requireAdmin` gate; `ObserveCommissionRulesUseCase`
  (admin, management screen), `ObserveActiveCommissionRulesUseCase` (inventory-gated, intake read),
  `SaveCommissionRuleUseCase`/`ArchiveCommissionRuleUseCase` (admin) with rate validation.
- `repository/InventoryRepository.kt` — `addStockBatchWithPurchase(...)` extended with a
  `commissions: List<CommissionInput>` param, written in the same transaction; KDoc invariant updated.
- `usecase/RecordInventoryPurchaseUseCase.kt` — takes + validates the commission lines (positive
  amount, real payee/location, `paidCash + paidBank ≤ amount`), normalises blank splits to `"0"`.
- `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — commission rule-screen + intake strings (incl.
  `commission_accrue`="Add to balance", `commission_pay_now`="Give now", cash/bank field labels,
  `commission_giving_now`, `commission_left_on_balance`, `commission_give_exceeds`, `commission_section_supplier`).
- `commonTest/.../InventoryTestFakes.kt` — fake repo captures `commissions` on the batch call.
- `commonTest/.../CommissionCalculatorTest.kt` **(new)** — both rate kinds, multi-payee, multi-location
  no-cross-contamination, zero-cost, no-match, with hand-worked numbers (`12×$5=$60`, `2% of $14,400=$288`).
- `commonTest/.../CommissionRuleUseCasesTest.kt` **(new)** — admin gate on save/archive/observe;
  intake read allowed for non-admin with inventory access; rate validation.
- `commonTest/.../RecordInventoryPurchaseUseCaseTest.kt` — commissions threaded into the single atomic
  call; non-positive amount and give-now-exceeds rejected (nothing written).

### firebase/functions (server)
- `syncWorker.ts` — `CommissionData` type, `commissionSourceId(accrue|payout_cash|payout_bank)`, and
  `syncCommission`: resolve payee HL id, get-or-create `Commission` EXPENSE account, accrue the full
  amount, then a `/customer-payouts` per non-zero cash/bank leg — self-heal + idempotent, PENDING→SYNCED/FAILED.
- `index.ts` — `onCommissionWrite` trigger (`commissions/{id}`, retry:true) + commission coverage in
  the `reconcileEntities` sweep.
- `commission.test.ts` **(new)** — accrue-only vs give-now split, one-method-only, idempotent replay
  (AC7), FAILED-on-error; `commissionSourceId` determinism.

### firebase (rules / schema)
- `firestore.rules` — `commissionRules/{id}` (write admin-only, read `inventory` view so a cashier can
  see proposed commission at intake) and `commissions/{id}` (create `inventory` manage, PENDING-only,
  CF owns sync fields), including `paidCash`/`paidBank` string checks.
- `SCHEMA.md` — documents both collections, the give-now split, and the HL idempotency keys.

### desktopApp
- `data/BackendInventoryRepository.kt` — writes `commissions/{id}` (incl. `paidCash`/`paidBank`) inside
  the stock+purchase transaction; `commissionData` helper + `COMMISSIONS` constant.
- `data/BackendCommissionRuleRepository.kt` **(new)** — Admin-SDK impl (observe/save/archive/close).
- `ui/inventory/AddStockViewModel.kt` — active-rules stream, `CommissionDecision` (give-now + cash/bank
  + `givenNow`/`leftOnBalance`/`giveExceedsAmount`), decision setters, `buildCommissionInputs`,
  numeric-only sanitiser, `commissionGiveExceeds` confirm-guard.
- `ui/inventory/CommissionRulesViewModel.kt` **(new)** — admin-gated rules VM; `setRuleActive` (on/off),
  inline `addLocationInline`/`addPayeeInline`, percent↔fraction conversion.
- `ui/inventory/CommissionRulesScreen.kt` **(new)** — ledger-style table with an on/off Switch per row,
  add/edit dialog (dropdowns with add-new), height-capped + scrollable when the window is short.
- `ui/inventory/InventoryScreen.kt` — Commission section in the purchase dialog (SUPPLIER vs Commission
  headers, per-payee blocks, Add-to-balance/Give-now, cash+bank split with "to give"/"giving now"/"left
  on balance"), dialog height-capped + body-scroll on short windows.
- `ui/components/DesktopNavSidebar.kt` — admin-only Commission nav item + `COMMISSION_RULES` section.
- `navigation/AromexApp.kt` + `ui/{entities,money,sales}/*Screen.kt` — thread `onNavigateToCommissionRules`
  and host the new screen/VM (bind + dispose).
- `test/.../CommissionConversionTest.kt` **(new)** — percent↔fraction round-trip + decimal sanitiser + percentLabel.

### androidApp
- `data/BackendInventoryRepository.kt` — commission writes in the batch transaction + `commissionData`.
- `data/BackendCommissionRuleRepository.kt` **(new)** — Firestore-KTX impl.
- `ui/inventory/AddStockViewModel.kt` — mirrors Desktop VM wiring (give-now split, guards, sanitiser).
- `ui/inventory/CommissionRulesViewModel.kt` **(new)** + `CommissionRulesScreen.kt` **(new)** — bare
  admin screen with Switch toggle + inline add.
- `ui/inventory/InventoryScreen.kt` — commission section in the save dialog (SUPPLIER header,
  Add-to-balance/Give-now cash+bank, leftover line).
- `ui/home/HomeScreen.kt`, `navigation/{Route,AromexApp}.kt` — admin-only Home entry + route.

### iosApp
- `repository/BackendInventoryRepository.swift` — commission writes in the batch transaction + `commissionData`.
- `repository/BackendCommissionRuleRepository.swift` **(new)** — native-SDK impl (SKIE Flow adapter + `__`-suspend).
- `viewmodel/AddStockViewModel.swift` — active-rules stream, `CommissionDecisionState` (give-now split,
  `leftOnBalance`, exceed guard), setters, `buildCommissionInputs`, sanitiser.
- `viewmodel/CommissionRulesViewModel.swift` **(new)** + `ui/CommissionRulesView.swift` **(new)** — bare
  admin screen with a Toggle per row + inline add.
- `ui/InventoryView.swift` — commission section in the purchase dialog (SUPPLIER header, Add-to-balance/
  Give-now cash+bank, leftover line).
- `ui/HomeView.swift` — admin-only Commission entry (full-screen cover).
- `iosApp.xcodeproj/project.pbxproj` — file-system-synchronised group picked up the new Swift files.

## How to test

**Automated (all green at handoff):**
- `./gradlew :sharedLogic:jvmTest` — calculator (hand-worked numbers), use-case gates, atomic-write threading.
- `./gradlew :desktopApp:test` — percent↔fraction conversion + sanitiser.
- `./gradlew :androidApp:compileDebugKotlin` — Android compiles.
- `cd firebase/functions && npx tsc --noEmit && npx vitest run` — 95 tests incl. `commission.test.ts`.
- iOS: `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -configuration Debug -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO` → **BUILD SUCCEEDED**.

**Manual (Desktop, admin user):**
1. Open **Commission** (sidebar, admin only). Add two rules on one location — one `$5 per phone`, one
   `2% of cost` — with different payees. Toggle a rule off/on with the row Switch; add a brand-new payee
   and a brand-new location from the dialog dropdowns.
2. Add inventory to that location (a batch summing to a known cost). At save, the dialog shows a
   **Commission** block per payee under a labelled **SUPPLIER** block. Confirm the figures
   (`count × rate`, `% of Σcost`).
3. Pick **Give now** on one line → Cash + Bank fields appear with **To give**, **Giving now**, and
   **Left on balance** (owed − given); over-giving is blocked. Leave the other **Add to balance**.
4. Save → in HL (`GET /ledger`) the payee balances move by the accrued amount; a give-now line also
   posts the cash/bank payout and nets down. Confirm the payee balance/statement in Contacts reflects it.
5. Shrink the desktop window until the dialog exceeds the height — the body scrolls (header/footer pinned).

## Acceptance criteria
1. Admin can create/edit/switch off a rule; non-admin can't reach it and the use cases reject them — **met** (`CommissionRuleUseCasesTest`, admin-gated nav/screen; Switch now toggles on/off both ways).
2. Two active rules on one location both produce an independently-decidable line — **met** (`CommissionCalculatorTest.twoRulesOneLocation_bothFire`; per-block decisions).
3. Per-unit = `count × rate`, percent = `% × Σcost` for that location — **met**, numbers verified (`$5×12=$60.00`, `2% of $14,400.00=$288.00`).
4. Batch spanning two locations → correct lines per location, no cross-contamination — **met** (`twoLocations_noCrossContamination`).
5. Accrue moves payee balance and posts no cash; give-now also moves Cash/Bank and nets down — **met** in code (`syncCommission`); **needs live `GET /ledger` confirmation** by the reviewer.
6. `GET /reports/integrity` ok with zero warnings after a mixed batch — **needs live confirmation** (netting mirrors the verified purchase path).
7. Replaying the CF does not double-post — **met** (`commission.test.ts` replay test; deterministic `sourceId`s).
8. Commission + stock written in one transaction; a forced failure writes neither — **met** at the seam (single `addStockBatchWithPurchase` txn; `RecordInventoryPurchaseUseCaseTest`); full Firestore rollback is the platform transaction guarantee (not emulator-tested here).
9. Skipping writes no commission; editing marks it overridden and writes the edited figure — **met** (`buildCommissionInputs` drops skipped, nulls `ruleId` on override).
10. Switching a rule off leaves earned commission untouched and stops future matches — **met** (archive/save only flip `isActive`; the calculator takes active rules only).
11. Moving a unit between locations after intake creates no commission — **met** (only the intake path computes commission; `updateSerial`/`setSerialStatus` untouched).
12. Payee balance in Contacts + statement reflect the commission with no extra work — **confirm, don't rebuild** — downstream unchanged; **needs live confirmation**.
13. No `toDouble()`/`Double(`/`Float` in new money code — **met** (grep clean; only Compose layout weights remain).
14. Light + dark; `:desktopApp:test`, `:sharedLogic:jvmTest`, functions tests green — **met** (theme tokens throughout; all suites pass).

## Deviations / decisions
- **All three platforms**, not Desktop-only (requester's call) — Android + iOS ship the same logic with bare UI.
- **`paidNow: {method}` → `paidCash`/`paidBank`** (requester change): give-now is a Cash + Bank split
  (partial allowed, `≤ amount`), so HL posts a payout per non-zero leg — mirroring an inventory purchase —
  instead of a single-method payout. The full amount is always accrued; the split nets the balance down.
- **Rule read is `inventory` view, not admin.** A cashier must see proposed commission at intake even
  though only an admin edits rules; the admin-only management screen is gated in-app.
- **Inline add of a payee** reuses `AddSupplierInlineUseCase` (creates a name-only party); roles can be
  refined in Profiles later. Locations use `AddAttributeUseCase(LOCATION)`.
- **No composite Firestore index** — every commission query is single-field equality (auto-indexed), like `purchases`.

## Open questions / follow-ups
- AC5/AC6/AC12 need a **live HL confirmation** (`GET /ledger`, `/reports/integrity`, Contacts statement) —
  not runnable in this environment; the netting mirrors the already-verified purchase path.
- **Clawback is out of scope (v1):** a voided/returned phone does not reverse its commission.
- Deploy already run against `aromex-june-2026`: rules + `onCommissionWrite` + `reconcileEntities`
  (targeted, no other functions touched). Pre-existing warnings surfaced — **Node 20 runtime**
  (decommissions 2026-10-30) and an outdated `firebase-functions` — worth a maintenance ticket.
