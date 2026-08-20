> **Platforms: Android · iOS · Desktop** — matches Add-Inventory's own scope (#51/#52), since
> inventory added from any platform needs to reach Humble Ledger the same way. *(Flag this if
> you actually want desktop-only for v1 — it wasn't explicitly narrowed like the Browse ticket
> was.)* Touches **shared logic + Firebase Functions + the Add-Inventory review screen**.
> ⚠️ **Follow `/kmp-arch`** — the purchase-record model/use case is shared; the dialog is native
> UI per platform; the HL calls live in a Cloud Function (HL credentials never touch the device,
> same reason Entities already work this way). Milestone: **M4 — Inventory + Scanner**.

## 📖 Story / Why
Right now, adding inventory writes to Firestore but **Humble Ledger never learns a purchase
happened** — no asset increase, no record of who it was bought from or whether it's been paid
for. This ticket closes that gap: when a batch is added, the shop can (optionally) say who it
was bought from and how much was paid on the spot, and that gets posted to HL as a real
purchase — **inventory booked as an asset, not an immediate expense** — so that later, when a
Sales module sells that unit, HL already knows what it cost and can recognise profit correctly.

This was designed through direct discussion with the PM, including a review of Humble Ledger's
actual API (`ledger.humblesolutions.in/docs`) — the decisions below are deliberate, not
assumptions; read them before re-deriving a different design.

## 🎯 The flow
1. Cashier completes Add-Inventory batch entry + review exactly as today (#51/#52) — **unchanged**.
2. On hitting the final **Save/Confirm** on the review screen, **before the inventory write
   commits**, a dialog appears with three fields, all optional and pre-filled with safe defaults:
   - **Bought from** — a searchable dropdown across **all Entities** (not filtered to
     `SUPPLIER` role — see decision below), with supplier-role entities **sorted to the top**.
     Add-new-inline supported (same pattern as other dropdowns in the app). **Defaults to a
     pre-selected "Unspecified Supplier"** entry (see below).
   - **Cash paid now** — a money field, defaults to 0/blank.
   - **Bank paid now** — a money field, defaults to 0/blank.
   - **Validation:** `cash + bank` must not exceed the batch's actual total cost (the sum of
     each reviewed unit's real cost, including any per-unit overrides from the review screen) —
     a clear inline error, not a silent clamp.
3. **Leaving every field at its default and confirming is a fully valid, first-class path** —
   it's the "skip" case, achieved by doing nothing rather than a separate button. It still posts
   to HL (against "Unspecified Supplier", nothing paid) — there is **no way to add inventory
   with zero HL record**, because that would create inventory the books never learn was
   acquired (an unrecoverable gap, not a shortcut).
4. Confirming the dialog (in any state, including all-defaults) lets the **existing inventory
   write proceed exactly as it does today** — same race-safe transaction, same speed, completely
   unaffected by anything in this ticket. **Additionally**, a small purchase-record gets written
   for background HL sync.
5. **Dismissing the dialog any other way (Escape, click-away) behaves identically to confirming
   at all-defaults** — the bookkeeping question must never block or cancel the actual inventory
   save.

## 🔁 Background HL sync (mirrors the M3 entity dual-write spine — same pattern, don't reinvent)
- The purchase record (batch total cost, chosen party's id, cash amount, bank amount) is written
  Firestore-side as **PENDING**, alongside — not merged into — the existing inventory transaction.
- A Cloud Function picks it up and makes these HL calls, **idempotent** via deterministic
  `sourceId`s derived from the purchase record's own id (e.g. `<id>_purchase`, `<id>_payout_cash`,
  `<id>_payout_bank`) so retries are always safe:
  1. **`POST /api/v1/customer-purchases`** — `customerId` = the chosen party's HL id,
     `amount` = the batch's total cost, `expenseAccountId` = the company's **Inventory** account
     (HL's own field is documented as accepting an *"Expense/Inventory account"* — this is the
     confirmed, correct way to book it as an **asset**, not an expense; do **not** use
     `POST /api/v1/purchases`/the Vendor endpoints — see decisions below).
  2. If **cash paid now > 0**: a separate **`POST /api/v1/customer-payouts`** call
     (`method: "CASH"`) for that amount, against the same party.
  3. If **bank paid now > 0**: a separate **`POST /api/v1/customer-payouts`** call
     (`method: "BANK"`) for that amount, against the same party. *(Split payment — part cash,
     part bank — is handled by simply issuing both calls; HL has no single "split" call.)*
- On success: flip the purchase record to **SYNCED**. On failure: leave **PENDING**, picked up by
  the **same reconcile sweep** already built for entity sync (`syncWorker.ts`) — extend it to also
  cover purchase records, don't build a second sweep.
- The cashier never waits on or sees any of this — inventory confirms instantly regardless of
  HL's sync timing or outcome.
- **Prerequisite:** confirm (or create, idempotently, via `POST /api/v1/accounts`) an
  **`Inventory`** account of type `ASSET` in the company's HL chart of accounts for the CF to
  post against.

## 🧩 "Unspecified Supplier"
- A **real, ordinary Entity**, tagged `SUPPLIER`, with a **deterministic/fixed identifier**
  (Firestore doc id *and* the HL `externalId` used to create it) — never randomly generated —
  so two near-simultaneous first-uses (two cashiers, two devices) can't create duplicates; HL's
  own customer-create is already idempotent on `externalId`, so this composes safely.
- **Bootstrapped lazily** on first use if it doesn't exist yet — no manual setup step required.
- **Visible in the normal Entities list** like any other party, not hidden — its balance is
  genuinely useful information ("how much unattributed purchase debt has accumulated"), and the
  shop can clean it up over time by re-recording purchases against real suppliers later.

## 🧠 Decisions baked in (came out of a long design discussion — don't relitigate without reason)
- **Inventory purchases post to HL as an ASSET increase, never an immediate expense.** This is
  what makes Sales' later cost-of-goods-sold posting correct (HL's `/sales` endpoint already
  supports `cogsAmount` + `inventoryAccountId` + `cogsAccountId` — *"Model B / perpetual
  inventory"* — but that only works if the asset was actually booked here first).
- **No Vendor subsystem.** Everything nets through the existing Customer/party model
  (`customer-purchases` / `customer-payouts`), consistent with the M3 unified-party decision —
  `EntityRole` is explicitly a non-binding label ("you can always buy from a customer"). Do not
  route suppliers through `/api/v1/vendors`; the existing entity→HL `createCustomer` sync is
  already correct and needs **no change**.
- **One HL purchase entry per Add-Inventory batch/submission** (not per unit) — including a
  multi-SKU SICKW paste batch. Summed from each unit's actual reviewed cost.
- **Payment methods for the "paid now" side are Cash and/or Bank only** — matches HL's own
  payout endpoint, which has no card option (you're paying a supplier, not taking a card swipe).
- **The bought-from dropdown lists ALL entities, not just `SUPPLIER`-tagged ones** (suppliers
  sorted first) — deliberately, per the non-binding-role decision above.
- **No "fix it later" flow** — re-attributing an Unspecified-Supplier purchase to a real supplier
  after the fact is a manual HL correction for v1, not a UI feature (see Out of scope).

## ✅ Scope
- [ ] **Shared logic:** a use case that builds a purchase record from a completed Add-Inventory
      batch (total cost from actual per-unit costs, chosen party id, cash amount, bank amount)
      and queues it (Firestore write, PENDING) for sync.
- [ ] **The dialog**, per platform: bought-from dropdown (all entities, suppliers sorted first,
      add-new-inline, defaults to Unspecified Supplier pre-selected), Cash paid now, Bank paid
      now, inline validation, wired to the review screen's existing Save action.
- [ ] **Cloud Function:** on purchase-record write, call `customer-purchases` +
      `customer-payouts` (×0–2) against HL as specified above; idempotent; flips to SYNCED;
      folds into the existing reconcile sweep on failure.
- [ ] **"Unspecified Supplier" bootstrap** — lazy, idempotent, deterministic id, visible in
      Entities.
- [ ] **Confirm/create the `Inventory` (ASSET) HL account** the CF posts against.
- [ ] **i18n** for all new dialog strings.

## 🖼️ UI standards (Definition of Done)
- Reuse the existing searchable-dropdown component and the entities/inventory theme; light + dark.
- Money fields via `session.currency`.
- **Graceful errors:** paid-now exceeding the batch total (inline, before confirm); HL sync
  failures are silent/retried — never surfaced to or blocking the cashier.
- Dialog dismiss (Escape/click-away) never cancels the underlying inventory save — see flow §5.
- Accessibility: labels, dynamic type/OS scaling, contrast, keyboard nav (Tab/Enter/Esc on
  desktop). Strings via i18n. `/kmp-arch` throughout.

## 🎯 Acceptance Criteria
- [ ] Hitting Save on the Add-Inventory review screen shows the purchase dialog before the write
      commits; every dismissal path (confirm-at-defaults, Escape, click-away) behaves identically
      — inventory saves normally, purchase posts against Unspecified Supplier with nothing paid.
- [ ] Bought-from dropdown shows all entities (suppliers first), supports add-new-inline, and
      always includes Unspecified Supplier pre-selected by default.
- [ ] `cash + bank` exceeding the batch's real total cost is blocked with a clear inline error.
- [ ] The existing inventory write's speed and race-safety are completely unaffected, regardless
      of HL sync outcome.
- [ ] HL receives one `customer-purchases` call (Inventory booked as an **asset**) per batch,
      plus a `customer-payouts` call per non-zero cash/bank amount — all idempotent, all retried
      via the existing reconcile sweep on failure.
- [ ] Unspecified Supplier is a real, visible Entity; concurrent first-use bootstraps don't
      duplicate it.
- [ ] No secrets; i18n; `/kmp-arch`; builds + runs on Android, iOS, and Desktop.

## 🚫 Out of scope
- **Re-attributing/editing** a purchase's supplier or payment split after the fact.
- **The Vendor subsystem** — deliberately unused.
- **The future standalone "Purchase" feature** (a fuller purchasing workflow — invoices, POs) —
  this ticket is only the lightweight capture at Add-Inventory time.
- **Sales' side of this** (COGS-at-sale-time posting via `/sales`) — a separate, later ticket;
  this one only needs to leave the asset correctly booked for that to work.

## 🔗 Dependencies
- Builds on the merged Add-Inventory flow (**#52**) and the existing entity dual-write spine
  (M3) — reuse its PENDING/SYNCED/reconcile pattern, don't reinvent it.
- **HL account prerequisite:** an `Inventory` (ASSET) account must exist in the company's HL
  chart of accounts (create via `POST /api/v1/accounts` if missing, idempotently).

## 📚 References
- Add-Inventory review screen + Save action: `*/ui/inventory/AddStockViewModel.*`,
  `*/ui/inventory/InventoryScreen.kt` (desktop), `ReviewUnit`, `AddStockUseCase`.
- Searchable dropdown to reuse: `*/ui/components/FilterableDropdownField.*`.
- Entities / suppliers: `EntityRole.SUPPLIER`, `SaveEntityUseCase`, the existing `createCustomer`
  CF path (`firebase/functions/src/hl.ts`) — **unchanged** by this ticket.
- Existing dual-write + reconcile precedent: the M3 entity spine, `firebase/functions/src/syncWorker.ts`.
- **Humble Ledger API** (`https://ledger.humblesolutions.in/docs`): `POST /api/v1/customer-purchases`,
  `POST /api/v1/customer-payouts`, `POST /api/v1/accounts`. Read the actual schemas before
  implementing — `expenseAccountId` on `customer-purchases` is documented as accepting an
  *"Expense/Inventory account,"* which is load-bearing for this design.
- `/kmp-arch`.

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
