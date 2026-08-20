> Milestone M9 · **Desktop** · shared + CF + UI · Owner-approved 2026-07-31

## 📖 Story / Why

Shops pay people for bringing stock in — a runner who sources phones, a partner who supplies a
branch. The arrangement is standing and boringly regular: *"whenever phones land at Shop A, Rajesh
gets $5 each."* Today that's remembered in someone's head and settled from memory, which is exactly
the kind of debt that turns into an argument six months later.

This makes the arrangement a **rule the system applies**, so the obligation is recorded the moment
the stock is, and the person's balance is simply true.

## 🎯 What you're building

Two pieces. **Everything downstream already exists** — once a commission is recorded, the payee's
balance shows in Contacts, each commission appears on their statement, and paying them is an
ordinary Money-screen entry (Cash → payee). Do not build any of that again.

### 1. Commission rules (admin-only)

A list where the standing arrangement is written in plain terms:

| Location | Payee | Rate |
|---|---|---|
| Shop A | Rajesh | $5.00 per phone |
| Shop A | Priya | 2% of cost |

- **Several payees per location** — every matching rule fires independently.
- **Two rate kinds**: a fixed amount per unit, or a percentage of that unit's cost.
- Add / edit / switch off. **Switching a rule off never touches commission already earned** —
  what's owed is owed.
- **Admin-only.** A rule silently creates money owed on every future intake; that's a settings-grade
  act, not a daily one. Cashiers still see and confirm each commission at intake.

### 2. Commission at intake

The Add-Inventory save dialog (#58) grows a commission section, shown **only when a rule matches**:

```
Commission
  Rajesh — 12 phones at Shop A × $5.00            = $60.00
     ◉ Add to what I owe      ○ Pay now  [Cash ▾]
  Priya  — 2% of $14,400.00 cost at Shop A        = $288.00
     ◉ Add to what I owe      ○ Pay now  [Cash ▾]
```

- One block per payee, each decided **separately**.
- Default is **Add to what I owe** — nothing leaves the till unless someone chooses it.
- The amount is **editable for this batch**, and any line can be **skipped entirely**.
- A batch spanning two locations groups its units by location first, so each rule sees only its own
  location's count and cost.

## 🧠 The things that make this correct

**Commission is written in the same transaction as the stock.** Extend
`addStockBatchWithPurchase` rather than writing commissions afterwards. The invariant from #58 —
never in-stock inventory without its purchase record — extends here: **never stock without its
commission obligation.** A second write that can fail independently would eventually leave phones
in the system and a forgotten debt, which is the precise failure this feature exists to prevent.

**The rule proposes; the person saving decides.** Never post a commission the user didn't see. A
standing rule that silently creates debt is the failure mode to design against.

**Earned on arrival only.** Commission fires when a unit is first added, never when it moves between
locations later — otherwise stock could be shuffled back and forth to mint commission. The intake
path is the only trigger; `updateSerial`/`setSerialStatus` must not compute commission.

**Money stays strings.** Both rate kinds are already covered by `Money.multiplyRate(amount, rate)`,
which is general half-up decimal multiplication: `multiplyRate("5.00", "12")` for per-unit, and
`multiplyRate("14400.00", "0.02")` for percent. Do not add floating-point maths.

**Percent rules depend on entered cost.** A unit costed at zero earns zero percent-commission. That's
correct behaviour, not a bug — but surface the computed figure in the dialog so it's never a surprise.

## 🗂️ Data

```
commissionRules/{ruleId}                     // admin-managed standing arrangement
  ruleId
  locationAttributeId: "<attributeId>"       // an AttributeType.LOCATION attribute
  payeeEntityId:       "<entityId>"          // the party who earns it
  rateKind:            "PER_UNIT" | "PERCENT_OF_COST"
  rate:                "5.00" | "0.02"       // decimal STRING; percent as a fraction
  isActive:            true
  createdBy, createdAt, updatedAt
```

```
commissions/{commissionId}                   // one payee's commission from one batch
  commissionId
  payeeEntityId, locationAttributeId
  ruleId:        "<ruleId>" | null           // null when the amount was hand-edited
  unitCount:     12
  basisAmount:   "14400.00"                  // the cost the percent applied to (percent rules)
  amount:        "60.00"                     // decimal STRING, what's owed
  paidNow:       { method: "CASH" | "BANK" } | null   // null = accrue only
  sourceBatchId: "<the add-stock batch/purchase id>"
  createdBy, createdAt, updatedAt
  syncStatus:      "PENDING" | "SYNCED" | "FAILED"    // CF-owned from here down
  hlTransactionId, hlSyncedAt, hlSyncError
```

Rules + indexes + `docs/SCHEMA.md` as usual. A client may only create `PENDING` and may never write
`syncStatus` or any `hl*` field — mirror the `moneyEntries` block.

## 💰 How it posts (nothing new in the books)

A commission is a **cost to the business** and **money owed to that person** until paid. That is
exactly the netting shape already in use for buying stock on credit:

| Step | HL call | Effect |
|---|---|---|
| Accrue | `POST /customer-purchases` with a `Commission` EXPENSE account | DR Commission expense · CR payee → their balance moves in their favour |
| Pay now (optional) | `POST /customer-payouts` | DR payee · CR Cash/Bank → nets their balance back down |

Resolve the `Commission` account with `getOrCreateAccount(..., 'Commission', 'EXPENSE')`, the same
way `Inventory` is resolved in `syncPurchase`. Idempotent on a deterministic
`sourceId` (`commission_<commissionId>[:payout]`), like every other posting.

## ✅ Scope

- `sharedLogic`: `CommissionRule` + `RateKind` + `CommissionInput`/`Commission` models;
  `CommissionRuleRepository`; `CommissionCalculator` (pure: batch + rules → per-payee lines);
  use cases — `ObserveCommissionRulesUseCase`, `SaveCommissionRuleUseCase` (admin),
  `ArchiveCommissionRuleUseCase` (admin).
- `InventoryRepository.addStockBatchWithPurchase` extended to take the commission lines and write
  them in the **same transaction**.
- `firebase/functions`: `onCommissionWrite` + `syncCommission` (accrue, then optional payout);
  reconcile-sweep coverage; rules + index; SCHEMA.
- `desktopApp`: commission rules screen (admin-gated); the commission section in the existing
  Add-Inventory save dialog; ViewModel wiring.
- Tests: calculator (both rate kinds, multi-location, multi-payee, zero cost, no match);
  CF routing + idempotent replay; VM tests for edit/skip/pay-now; permission gating.

## 🖼️ UI standards (Definition of Done)

- Theme tokens, light + dark. Reuse the field shell, dropdown and table patterns from the Money
  screen (`ui/money/MoneyFields.kt`) — do not invent a fourth dropdown.
- The rules list is a table in the same ledger style: hairlines, zebra, fixed row height.
- The intake block shows **how the figure was reached** (`12 × $5.00`, `2% of $14,400.00`), never a
  bare total.
- Every string via i18n. Money via `MoneyFormat`.
- An amount edited by hand is visibly marked as overridden, so a reviewer can tell it wasn't the rule.

## 🎯 Acceptance Criteria

1. An admin can create, edit and switch off a rule; a non-admin cannot reach the screen and the
   use cases reject them.
2. Two active rules on one location both produce a line at intake, each independently decidable.
3. A per-unit rule computes `count × rate`; a percent rule computes `percent × summed cost` for that
   location's units only. Verify both against a hand-worked example and paste the numbers.
4. A batch spanning two locations produces the right lines for each, with no cross-contamination of
   counts or costs.
5. **Accrue** moves the payee's HL balance by exactly the commission and posts no cash. **Pay now**
   additionally moves Cash/Bank and nets the payee's balance back. Verify both against `GET /ledger`.
6. `GET /reports/integrity` is `ok: true` with zero warnings after a mixed batch.
7. **Replaying the CF for the same commission does not double-post.** Test it.
8. The commission doc and the stock are written in **one** transaction — a forced failure writes
   neither. Prove it.
9. Skipping a line writes no commission; editing an amount writes the edited figure and marks it
   overridden.
10. Switching a rule off leaves previously earned commission untouched and stops future intakes
    matching it.
11. Moving a unit between locations after intake creates **no** commission.
12. The payee's balance in Contacts and their statement both reflect the commission with no extra
    work — confirm, don't rebuild.
13. Grep new code for `toDouble()`/`Double(`/`Float` — must return nothing.
14. Light + dark; `:desktopApp:test`, `:sharedLogic:jvmTest`, functions tests green.

## 🚫 Out of scope

- **Clawback.** A returned or voided phone does not reverse its commission in v1 — deciding what
  happens when the payee has already been paid deserves its own conversation.
- Rules keyed on anything but location (brand, model, supplier), tiered/banded rates, per-payee caps.
- Commission on sales rather than intake. Phones. Reporting or payout runs — the Money screen
  already settles what's owed.

## 🔗 Dependencies

- #58 (Add-Inventory purchase-at-save) — the dialog and the atomic write this extends.
- Entities (payees) and `AttributeType.LOCATION` — both shipped.
- M8 money movement — how a commission gets paid later. Already merged.

## 📚 References

- `sharedLogic/.../repository/InventoryRepository.kt` → `addStockBatchWithPurchase` (the transaction)
- `sharedLogic/.../model/NewUnit.kt` — each unit already carries `cost` and `location`
- `firebase/functions/src/syncWorker.ts` → `syncPurchase` (the accrual template, incl.
  `getOrCreateAccount` for the expense account) and `syncMoneyEntry` (the routing/idempotency shape)
- `desktopApp/.../ui/money/MoneyFields.kt` — field shell + overlapping dropdown to reuse
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json`

## 🤖 Kickoff prompt

> Read this ticket. Build commission-on-intake: an admin-only rules list (location → payee → fixed
> per-unit or percent-of-cost, several payees per location), and a commission section in the existing
> Add-Inventory save dialog that shows each computed figure and lets the user accrue it or pay it
> now. Write the commission in the **same transaction** as the stock — never stock without its
> obligation. Accrue via `/customer-purchases` against a `Commission` expense account, pay via
> `/customer-payouts`, both idempotent. Commission is earned on arrival only, never on a later move.
