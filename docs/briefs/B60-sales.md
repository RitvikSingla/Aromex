## 🎯 What we're building & why
The shop can hold customers and stock and knows what it paid — but it still can't do the one thing it exists to do: **sell a phone**. This is the revenue action. A cashier rings up **one or more in-stock phones** (plus the odd non-inventory item), to a **named customer or a walk-in**, takes payment, and the result lands correctly in **both** places at once: operations (the units leave stock) and the books (Humble Ledger gets revenue, tax, cost-of-goods, and the customer's balance) — with zero manual accounting. This is the payoff of Entities + Inventory + the #58 asset booking.

## 👤 Who it's for
The **cashier / salesperson at the counter** (permission: `sales`), on **Desktop first**. The owner benefits downstream from accurate **profit-per-sale** and **customer balances** falling out automatically.

## ✅ What it must do (capabilities)
- Build a **cart of one or more in-stock phones** (pick from current stock), plus **optional non-inventory line items** (e.g. a case, a fee) that are revenue-only.
- **Edit a phone's price** at checkout, and apply **per-item and/or whole-sale discounts** (keep the original price *and* the discount, so discounting is reportable).
- Attach the sale to a **named customer** (searchable) **or a walk-in**.
- Take **payment: cash + card, including split**, and **full or partial** — a named customer's unpaid remainder becomes their **account balance**; a **walk-in must pay in full**.
- Apply **tax** (prices are tax-exclusive; tax added at checkout from company tax config).
- **Confirm** → the units are marked **sold** and the sale is recorded; the books are posted in the background (**revenue, tax, cost-of-goods against the inventory asset, and the payment**).
- Optional **sale note**.
- A clear **on-screen "sale complete" confirmation** (no printed/PDF receipt in v1).

## 🌟 What "good" looks like
- A cashier rings up a multi-phone sale to a customer or walk-in, takes a split payment, and is done in a few clicks.
- The books are **right automatically** — revenue, tax, **profit-per-sale (COGS)**, and the customer's balance all reflect the sale with no manual bookkeeping.
- Two cashiers **can't double-sell the same phone**; the one who loses the race gets a **clear, graceful "already sold" message**, never a crash or a silent drop.
- Desktop feels as polished as the Entities / Inventory screens; the phone builds compile and run (bare UI for now).

## 🚫 Non-negotiables
- **HL is never written from the device** — posting is server-side via the dual-write spine; HL credentials never touch the client.
- **Operationally atomic:** a unit can't be marked sold without the sale record, and vice-versa (the #58 atomicity lesson).
- **Money is decimal strings end-to-end** — never float.
- **A walk-in sale must be paid in full** — no balance carried on an anonymous party.
- **All three platforms stay stable** (shared logic + all three ViewModels + all three compiling + bare functional UI); only the **Desktop UI is polished** now.
- **Idempotent HL posting** — retries are always safe.

## 🧭 Technical steers
- `[hard]` **Perpetual COGS:** post the unit's **actual cost** as cost-of-goods-sold against the HL **Inventory asset** at sale time (HL `/sales` `cogsAmount`+`inventoryAccountId`+`cogsAccountId`). Depends on **#58** booking inventory as an asset.
- `[hard]` **Tax-exclusive:** prices are pre-tax; tax added at checkout from the company tax config; maps to HL `/sales` pre-tax `amount` + tax.
- `[hard]` **Customer model:** a **named Entity** (searchable picker) **or** a shared **"Walk-in Customer" placeholder Entity** — mirror of #58's Unspecified Supplier (deterministic id, lazy idempotent bootstrap, visible in Entities).
- `[hard]` **Payment (full/partial):** post via HL `/sales` (creates the invoice = amount owed) + `/payments` (settles the paid part); the remainder becomes the named customer's HL **balance**. Same shape as #58's purchase + payout.
- `[hard]` **Concurrency:** **no cart lock**; the mark-sold is a **race-safe transaction**; the losing cashier gets a graceful "already sold" message (mirrors Add-Inventory).
- `[hard]` **Dual-write spine:** Firestore **PENDING** → Cloud Function posts to HL → **SYNCED**, with the existing **reconcile sweep** as backstop. Reuse the entity/#58 machinery — don't reinvent it.
- `[hard]` **Selling a unit** flips its serial status → **SOLD** and **releases its imeiIndex** (already in the M4 model, so a returned phone can be re-added later).
- `[hard]` **`/kmp-arch`:** shared model/repo/usecase; per-platform ViewModels (all three) + native UI (Desktop polished); no business logic in the UI.
- `[preference]` **Reuse the inventory browse table (#55/#57)** as the item-picker rather than building a new selection screen.
- `[preference]` Payment methods map to the HL **Cash / Credit Card** (and Bank) accounts already provisioned in the chart of accounts.

## 🧊 Happy to defer
- The standalone **"customer pays down their tab later"** collection screen (a separate small follow-up via HL `/payments`).
- **Returns / refunds.**
- Any **printed / PDF receipt or invoice document** — on-screen confirmation only for v1.
- **Phone (Android/iOS) production UI polish** — stable-but-bare now, polished later off the Desktop reference.
- **Middleman** role handling (PRD mentions it; not v1).

## 📎 References
- **PRD** §9.4 (Sales — all platforms, permission `sales`), §12 (milestones — Sales is pulled **ahead** of the standalone Purchase feature; that Purchase milestone still follows later), §7/§13 (HL; the "inventory-as-asset + COGS-on-sale" decision that was *pending* is now **made**: perpetual/asset).
- **#58** (inventory→HL purchase) — the mirror pattern for HL posting + the placeholder-party idea; this depends on it for the booked asset.
- **Entities (M3)** dual-write spine (`firebase/functions/src/syncWorker.ts`); the browse table (**#55/#57**) as the picker; **#52** add-inventory review UX for the confirm-before-write feel.
- **HL API** (`https://ledger.humblesolutions.in/docs`): `/sales` (with `cogs*` fields), `/payments`, customer-balance reads.
- `/kmp-arch`.
