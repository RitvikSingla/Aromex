---
title: "[M14] Quantity-tracked inventory — stock counted, not serialised"
labels: []
---

## 📖 Story / Why

A phone shop sells cases, chargers, screen protectors and cables alongside phones. Today Aromex can
**sell** one — as a custom line, free text and a price, which reaches the invoice and the ledger
correctly — but it cannot **track** one. There is no stock level, no cost, and therefore no margin:
the owner is guessing at the profit on everything that isn't a handset.

This ticket adds `TrackingMode.QUANTITY` next to the existing `SERIALIZED`, so a product can be
counted rather than individually identified.

**This is a retrofit into a live, working system, and the bar is that nothing about phones changes.**
Every existing product is `SERIALIZED`, there is no migration, and a serialised sale must behave
byte-for-byte as it does today. Read the "What will silently break" section before writing any code —
it is the reason this ticket is long.

## 🧭 Context

### The groundwork is already in place — this was planned for

- **`Product.trackingMode`** already exists (`sharedLogic/.../model/TrackingMode.kt`) with
  `SERIALIZED | QUANTITY | VARIANT | SERVICE`, round-trips on Desktop, Android and iOS, and
  `firestore.rules` already rejects unknown values (there's a test asserting `"PHONE"` is refused).
  Nothing branches on it yet — every path assumes `SERIALIZED`.
- **Naming is already generic** — `products`, `serials`, `sales`. Nothing needs renaming.
- **`SaleLineInput` is a sealed interface** (`InventoryLineInput` | `CustomLineInput`) — the intended
  extension point. Adding a third variant is additive.
- **The invoice already understands quantity**: the Bill Engine template computes `qty × rate`. It is
  the Cloud Function that hardcodes `qty: 1` — `firebase/functions/src/syncWorker.ts:824-825`.
- **Humble Ledger needs no change.** It posts *amounts*, not units. Revenue, COGS and inventory
  relief are all money figures already.

### The costing decision — weighted average (decided by the manager)

Serialised stock needs no costing policy: each `Serial` carries its own `cost`, so selling that phone
books that cost. Quantity units are **fungible**, so "what did this one cost" has no answer until you
choose a policy. The manager chose **weighted average**:

```
Buy 100 @ $2.00  →  onHand 100,  avgCost $2.00
Buy 100 @ $3.00  →  onHand 200,  avgCost $2.50     (400 + 300) / 200
Sell 50          →  COGS 50 × $2.50 = $125.00
                    onHand 150,  avgCost $2.50     (a sale never moves the average)
```

Store two denormalised numbers on the product — `onHand` (integer) and `avgCost` (decimal string) —
**plus an append-only `stockMovements` log** so the numbers can be audited and rebuilt if they ever
drift. The log is the truth; the two fields are the fast path.

Reversing a *receipt* removes its quantity and its value from the running totals:

```
avgCost' = (onHand × avgCost − qtyReversed × costReversed) / (onHand − qtyReversed)
```

Reversing a *sale* (a void) restores the quantity and leaves `avgCost` untouched, because the sale
never changed it. Guard the divide: if `onHand − qtyReversed` is zero, `avgCost` resets to `"0"`.

### ⚠️ What will silently break — read this first

Kotlin's `when` over a sealed interface is exhaustive: add a variant and the compiler lists every
place to update. **`filterIsInstance` is not.** It compiles fine and quietly drops the new type,
which here means a quantity line that is sold, invoiced and charged for but contributes **no COGS** —
understating cost and overstating profit, with nothing failing.

Every one of these must be revisited by hand:

| File | Line | What it does today |
|---|---|---|
| `sharedLogic/.../usecase/SaleCalculator.kt` | 93 | **COGS total** — the dangerous one |
| `desktopApp/.../ui/sales/SalesViewModel.kt` | 294 | serial ids in the cart |
| `desktopApp/.../ui/sales/SalesViewModel.kt` | 333 | find a cart line by serial |
| `desktopApp/.../ui/sales/SalesViewModel.kt` | 848 | resolved lines for the use case |
| `desktopApp/.../ui/sales/SalesViewModel.kt` | 906 | the unpriced-line guard |
| `desktopApp/.../ui/sales/SalesViewModel.kt` | 1059 | cost by serial id |
| `androidApp/.../ui/sales/SalesViewModel.kt` | 233, 628, 679, 811 | the same four |
| `desktopApp/src/test/.../SalesViewModelTest.kt` | 670 | test helper |

Search for `filterIsInstance<CartLine.Inventory>` and `filterIsInstance<SaleLineInput.` before you
believe the list is complete, and prefer converting each to an exhaustive `when` so the compiler
guards it next time.

**iOS:** SKIE surfaces a Kotlin sealed interface as a Swift enum, so an exhaustive `switch` in Swift
*will* fail to compile when the case is added — that's the good outcome. Any Swift code using
`if case` or `as?` filtering has the same silent-skip hazard as `filterIsInstance`.

### ⚠️ The concurrency hazard that does not exist today

Serialised stock is concurrency-safe by accident: each phone is its own document, so two cashiers
selling the same IMEI collide naturally on that document.

A quantity product is **one document with a number on it**. Two tills selling the last 3 units at the
same moment will both read `onHand = 3`, both write `0`, and both succeed — overselling with no error
and a corrupted average.

**Every stock movement must be a Firestore transaction** that re-reads the product inside the
transaction and writes `onHand`, `avgCost` and the movement entry together. This is not optional and
it is not covered by the "block overselling" rule, which is a *read-time* check.

### Decisions already made by the manager — build to these

| Decision | Answer |
|---|---|
| Costing | **Weighted average**, with an append-only `stockMovements` log |
| Overselling | **Blocked** — a sale cannot exceed `onHand`; correcting a wrong count is a stock adjustment, which leaves a trace |
| Units | **Single unit only** ("each"). No pack sizes, no conversions. Buy a box of 50 → record 50. |
| Design | **No new design** — match the existing Add Inventory, item picker and Stock History screens and `AromexTheme` |
| Platforms | All three — a counter sale happens on a phone as readily as at the desk |

### What must not change

- Every existing product is `SERIALIZED` and there is **no migration**. A product with no
  `trackingMode` on the wire already defaults to `SERIALIZED` — keep that.
- Serialised purchase, sale, void, per-unit delete and batch reversal must behave exactly as today.
  The regression bar is the existing test suites passing untouched.
- `Serial` documents are **not** created for quantity products. Anything that joins a sale line to a
  serial must tolerate their absence rather than assuming one exists.

## 🔑 Access & prerequisites

- **Firebase project access** to the dev project `aromex-june-2026` (Firestore, Functions, emulators).
  Ask the manager to add your Google account.
- **A test login with `inventory: manage` and `sales: manage`** — from the manager via the password
  manager, never from the repo.
- The service-account key at `firebase/secrets/aromex-june-2026-sa.json` is git-ignored — **never
  commit it or paste it anywhere.**
- Node 20 + `firebase-tools` (functions + `npm run test:rules`), JDK 21, Android Studio, and **Xcode**
  — iOS must compile.
- **A second device or a second emulator**, so the concurrency case above can actually be exercised
  rather than reasoned about.

## ✅ Scope / What to build

### 1. Shared model + costing (`commonMain`) — do this first, it is the whole ticket
- [ ] `Product` gains `onHand: Int = 0` and `avgCost: String = "0"`, both meaningless for
      `SERIALIZED` (leave them at defaults there — do not start maintaining them for phones).
- [ ] `StockMovement` model — product id, kind (`RECEIPT` / `SALE` / `RETURN` / `ADJUSTMENT` /
      `REVERSAL`), quantity (signed), unit cost, resulting `onHand` and `avgCost`, source id
      (purchase/sale id), actor, timestamp.
- [ ] A **pure** `WeightedAverage` object implementing receive / issue / reverse-receipt /
      reverse-issue, all on decimal strings via `Money` — **never `Double`**. Unit-test it hard,
      including the divide-by-zero guard and a receipt reversal that empties stock.
- [ ] `SaleLineInput.QuantityLineInput(productId, quantity, unitPrice, lineDiscount)`.
- [ ] `SaleRecordLine.Quantity(productId, label, quantity, unitPrice, lineDiscount, netPrice, cost)`
      where `cost` is the *extended* cost (`quantity × avgCost` at sale time), snapshotted.
- [ ] `ResolvedSaleLine` — either add a quantity variant or give it a nullable `serialId` plus a
      `quantity`. Prefer a sealed split so nothing can read a serial id that isn't there.
- [ ] `SaleCalculator` — include quantity lines in the subtotal **and in `cogsTotal`**.
- [ ] `RecordSaleUseCase` — handle the new variant in all five branch sites; block a sale whose
      quantity exceeds `onHand` with a typed, translatable error.

### 2. Repositories + Firestore
- [ ] `InventoryRepository`: `receiveQuantity(productId, qty, unitCost, purchaseId)`,
      `issueQuantity(...)`, `adjustQuantity(productId, newOnHand, reason)`, `reverseReceipt(...)` —
      **each inside a Firestore transaction** that re-reads the product (see the concurrency note).
- [ ] Implement on Desktop (Admin SDK), Android and iOS (client SDK). The transaction is per-platform
      but the arithmetic comes from the shared `WeightedAverage` — do not reimplement it three times.
- [ ] `stockMovements` collection + rules: read for `inventory: view`, **create only with
      `createdBy == request.auth.uid`, and `update`/`delete` closed to everyone** — the same
      append-only shape as `companySettingsChanges`.
- [ ] Rules for the new `Product` fields: writable only by `inventory: manage`; `onHand` must be an
      integer `>= 0`.

### 3. Sales flow
- [ ] The item picker offers quantity products with a quantity input; serialised products keep the
      exact IMEI-picking flow they have now.
- [ ] The cart shows `3 × Tempered glass` rather than one row per unit.
- [ ] Selling more than `onHand` is refused with a clear message naming the product and what's left.
- [ ] **Void restores stock** — a voided sale returns the quantity and writes a `REVERSAL` movement.

### 4. Purchases, invoice and reversal
- [ ] Add Inventory accepts a quantity product: pick the product, enter quantity and unit cost. The
      purchase, the party balance and the HL posting all work exactly as they do for phones — only
      the stock side differs.
- [ ] `firebase/functions/src/syncWorker.ts:824` — send the line's real quantity for quantity lines;
      **keep `qty: 1` for serialised lines** so phone invoices are unchanged.
- [ ] **Batch reversal** (`firebase/functions/src/purchaseReversal.ts`): `assertBatchIsWhole` counts
      unit documents, which don't exist for a quantity receipt. For those, the equivalent check is
      `onHand >= receivedQty` — you can only reverse a receipt you still hold in full. Refuse with a
      clear reason otherwise, and reverse both quantity *and* value.
- [ ] Stock History shows quantity receipts alongside serialised batches.

### 5. UI — Desktop, Android, iOS
- [ ] Creating a product asks for its tracking mode. **The mode is locked once any stock movement
      exists** for that product — switching a counted product to serialised (or back) would orphan
      either the count or the serials. Show it read-only with a one-line reason after that.
- [ ] The inventory list shows on-hand and average cost for quantity products, and the existing
      per-unit view for serialised ones. Never show an empty serial list for a quantity product.
- [ ] A **stock adjustment** action (`inventory: manage`) to correct a count, requiring a reason,
      writing an `ADJUSTMENT` movement.

## 🎯 Acceptance Criteria

**Nothing about phones changes**
- [ ] Every existing test passes unmodified: `:sharedLogic:jvmTest`, `:desktopApp:test`,
      `:androidApp:compileDebugKotlin`, `xcodebuild` for iOS, functions and rules suites.
- [ ] A serialised sale produces an identical sale document, invoice payload and set of HL postings
      to the ones it produces today — diff a before/after payload to prove it.
- [ ] A product with no `trackingMode` on the wire still reads as `SERIALIZED`.

**The new mode works end to end**
- [ ] Receiving 100 @ $2.00 then 100 @ $3.00 leaves `onHand 200`, `avgCost 2.50`.
- [ ] Selling 50 of those books **COGS $125.00** — verified in the sale document *and* in the HL
      posting, not just in the UI.
- [ ] The invoice shows `50 ×` for that line, and still `1 ×` for a phone line.
- [ ] Selling more than `onHand` is refused on all three platforms.
- [ ] Voiding a quantity sale restores `onHand` and writes a `REVERSAL` movement.
- [ ] Reversing a quantity receipt restores the previous `avgCost` exactly, and is refused when the
      stock is no longer wholly on hand.
- [ ] Every movement appears in `stockMovements`, and no client can edit or delete one.

**The failure modes are actually handled**
- [ ] **Two concurrent sales of the last unit: exactly one succeeds.** Exercise this with two
      devices/emulators — this is the criterion most likely to be quietly skipped.
- [ ] A quantity product never has `Serial` documents, and no screen shows an empty serial list for one.
- [ ] Tracking mode cannot be changed once a stock movement exists.
- [ ] `onHand` can never go negative through any path, including reversals.
- [ ] No money value anywhere in this work is a `Double` — decimal strings via `Money` throughout.

## 🖼️ UI standards

- [ ] **No new design** — match the existing Add Inventory, item picker, inventory list and Stock
      History screens, and `AromexTheme` tokens. Reuse the shared components; no one-off colors/sizes.
- [ ] **Light and dark themes**, both verified.
- [ ] **Native components** — Compose Material, SwiftUI/HIG, Compose-Desktop equivalents. If something
      can't be done natively, say so and take the closest native approach.
- [ ] **Edge-to-edge with correct safe areas** on mobile — nothing under the status bar, notch, home
      indicator or Android gesture/nav bar.
- [ ] **Responsive** — small phone → tablet, both orientations; desktop resizable to the app's 420dp
      minimum with a layout that reflows.
- [ ] **Keyboard** — a **numeric keypad** for quantity and cost, with a Done accessory where the pad
      has no return key; **Next** between fields; the focused field stays visible.
- [ ] **Correct truncation** — long product names ellipsize rather than clipping or pushing layout.
- [ ] **Loading, empty, error and disabled states** everywhere; the oversell refusal is an inline
      message, never a raw error dump.
- [ ] **State preserved** across rotation, process death and desktop resize — a half-filled receive
      form must survive.
- [ ] **Accessibility** — labels on every control, logical focus order, dynamic type without breaking
      layout, ~48dp/44pt touch targets, WCAG AA contrast.
- [ ] **No hardcoded user-facing strings** — everything through `Strings` / `EnglishStrings`.
- [ ] Follow `/kmp-arch`: shared model + costing in `commonMain`, native UI and ViewModels per
      platform, **no business logic in the UI**.

## 🚫 Out of scope

- `VARIANT` and `SERVICE` tracking modes — leave the enum values unimplemented as they are now.
- Pack sizes / units of measure / conversions.
- FIFO or specific-identification costing.
- Low-stock alerts, reorder points, stocktake sessions.
- Migrating any existing product from serialised to quantity.
- Barcode scanning for quantity items (the scanner stays IMEI-oriented).
- Multi-location stock — `Serial.location` exists but per-location quantity is a separate problem.

## 🔗 Dependencies

None blocking. Best sequenced **after [#113](https://github.com/Humble-Coders/Aromex-KMP/issues/113)
(audit trail)** so stock adjustments are captured by the general trail rather than needing their own;
if it ships first, the `stockMovements` log stands alone and #113 should later fold it in.

## 🔗 References

- `sharedLogic/.../model/TrackingMode.kt` — the enum, already in place
- `sharedLogic/.../model/Product.kt`, `Serial.kt` — today's serialised shape
- `sharedLogic/.../model/SaleLineInput.kt`, `SaleRecord.kt` — the sealed interfaces to extend
- `sharedLogic/.../usecase/SaleCalculator.kt:93` — the COGS filter that must not miss the new type
- `sharedLogic/.../usecase/RecordSaleUseCase.kt` — the five branch sites
- `firebase/functions/src/syncWorker.ts:824` — the hardcoded `qty: 1`
- `firebase/functions/src/purchaseReversal.ts:223` — `assertBatchIsWhole`
- `firebase/firestore.rules` — `companySettingsChanges` is the append-only pattern for `stockMovements`
- `sharedLogic/.../util/Money.kt` — decimal-string arithmetic; the only money maths allowed
- `CLAUDE.md` / `/kmp-arch` — architecture rules

## 🚀 Kickoff prompt

```
/start-ticket <#>
```
