# Aromex — Feature Reference (for KMP Rebuild)

> Functional specification of the existing Swift Multiplatform (iOS/iPadOS/macOS) app.
> This documents **what the app does, screen by screen** — features and behavior only, not the database schema or implementation. Use it as the product spec when rebuilding in Kotlin Multiplatform (Android, iOS, Desktop).

**What Aromex is:** accounting software for mobile-phone distributors/retailers. It tracks inventory of individual phones (by IMEI), purchases from suppliers, sales to customers, manual cash/bank/currency transactions, per-entity balances across multiple currencies, and generates printable invoices and ledgers.

---

## 0. Global / App-Wide

- **Platforms today:** iPhone, iPad, macOS (one codebase, responsive layouts). Rebuild target adds **Android** and **Windows/Desktop**.
- **Splash screen:** animated branded splash (~2.5s) showing the business name (fetched live, cached fallback) and "A Humble Solutions Product".
- **Business name** is configurable and shown in the sidebar/splash; fetched from backend, cached locally for offline.
- **Navigation:** persistent sidebar (macOS/iPad split view) / hamburger sheet (iPhone). Menu items:
  - Home, Transactions, Purchase, Sales, Profiles, Inventory, Balance Report, Histories, Scanner (iOS only), Statistics.
  - **Sales is available on all platforms**, including iPhone (via the mobile menu). (There is a leftover disable in the desktop-style sidebar that only triggers on a phone held in landscape; it is not the real behavior.)
  - **Scanner is iOS-only** (uses the device camera).
- **Real-time data:** the app uses live backend listeners throughout — balances, inventory, order numbers, entity lists, and transaction history update automatically across devices.
- **Three account "buckets" tracked everywhere:** Cash, Bank, Credit Card. Color-coded (green positive / red negative).
- **Money color convention:** green = positive / "to receive" / inflow; red = negative / "to give" / outflow; gray = settled/zero.
- **Responsive patterns:** full-screen sheets + vertical stacks on iPhone; centered modal dialogs + multi-column/table layouts on iPad/macOS; hover/cursor affordances and keyboard support on macOS.

---

## 1. Home Screen

- **Financial Overview — Account Balances card:** live Bank, Cash, and Credit Card balances, color-coded, USD-formatted.
  - Each balance has an **edit** action → dialog showing current value + new-amount input.
  - Credit Card field auto-applies negative-sign logic.
  - iPhone uses a full-screen sheet; iPad/macOS use a compact centered dialog. Saving shows a loading overlay; updates sync in real time.
- **Quick Actions:** shortcut buttons — Add Entity, Add Product, Add Expense, Statistics, Inventory. (Vertical stack on iPhone; grid on iPad/macOS.)

---

## 2. Transactions (Manual Entry)

The manual ledger-entry screen for recording money movement between parties/accounts.

- **From (giver) / To (receiver):** searchable dropdowns spanning all entities (Customers, Suppliers, Middlemen) **plus** the special accounts **"Myself CASH"** and **"Myself BANK"**.
- **Amount:** decimal input. On **macOS**, accepts math expressions (e.g. `100 + 50/2` → evaluates).
- **Currency:** selectable per transaction (CAD default; any configured currency). Each party tracks balances **per currency independently**.
- **Notes** and **Transaction date** (defaults today; can backdate/future-date).
- **Validation:** both parties required, must differ, amount > 0, currency required.
- **Effect:** decrements the giver, increments the receiver, in the chosen currency. Records a balance snapshot for audit.

### Currency Exchange mode
- Toggle to record converting one currency to another (giving currency → receiving currency).
- Fields: giving currency, receiving currency, amount, **custom exchange rate**.
- If no direct rate exists for the pair, a **Direct Rate Input dialog** prompts for it and saves it for reuse.
- **Profit/loss** is computed when the custom rate differs from the market rate: `(customRate − marketRate) × amountGiven`, denominated in the receiving currency, and stored with the transaction.

### Currency management (used here and elsewhere)
- **Add Currency dialog:** code (auto-uppercased, e.g. USD/INR/EUR) + symbol ($/₹/€). Immediately available in dropdowns.
- **Exchange Rates dialog:** lists currency pairs with **bidirectional** editable rates ("1 A = X B" and "1 B = Y A"); validated positive; saved to backend.
- **Direct Exchange Rates:** separate table of explicit pair-to-pair rates, used for non-USD conversions; fetched at startup.
- **Multi-currency balances:** "Myself CASH" and customers can hold balances in many currencies at once; "Myself BANK" is CAD-only.

---

## 3. Purchase Flow

Create a purchase from a supplier, then auto-generate a printable invoice.

### Setup
- **Order number:** auto-generated & incremented (`ORD-{n}`), real-time synced to avoid duplicates; manually overridable with an "Auto" revert.
- **Transaction date:** date picker (defaults today).
- **Supplier selection:** searchable dropdown of entities; supplier phone is pulled onto the invoice.

### Line items — Products (phones)
Add Product dialog with:
- **Brand / Model** dropdowns (filtered Model-by-Brand), each with **add-new** inline.
- **Capacity** (number) + **unit** toggle (GB/TB).
- **Color**, **Carrier** (optional), **Storage location** dropdowns — all with add-new.
- **Status:** Active / Inactive.
- **IMEI/serial:** add one or many per product; **iOS barcode scan** to capture; duplicate-check against existing inventory; each IMEI editable/removable before save.
- **Unit cost** per item.
- Cart shows products as cards (iPhone) or a full table (iPad/macOS) with edit/delete per row.

### Line items — Services & Expenses
- **Add Service:** name + price (flagged as a service, distinct styling; no IMEI).
- **Add Expense:** category dropdown (add-new), total amount, split across Cash/Bank/Credit Card (must sum to total), optional notes; recent expenses viewable/reversible.

### Totals
- **Subtotal** auto-summed.
- **GST %** and **PST %** → auto-computed amounts.
- **Adjustment:** amount + Discount/Surcharge toggle.
- **Notes** field (prints on invoice).
- **Grand total** = subtotal + GST + PST ± adjustment, live.

### Payment
- **Cash** and **Bank** amount fields. **Credit Card is N/A for purchases** (disabled).
- **Remaining/credit** = grand total − paid, shown in red if outstanding.
- **Overpayment alert** if paid > total.

### Confirmation (atomic)
On confirm: creates phone records per IMEI (added to inventory + IMEI index), creates the purchase record, **deducts paid amounts from Cash/Bank balances**, logs the purchase to the supplier's history, and increments the order counter. Validates supplier + ≥1 item + valid prices + ≥1 IMEI per phone.

### Invoice (auto-opens after confirm) — shared by Purchase & Sales
- **Auto-paginated** based on item count (single page ≤8 items; multi-page layouts beyond).
- **Header:** company name/address/email/phone (editable; toggle on/off).
- **Body:** bill title (Purchase/Sales Invoice), order #, date, supplier/customer + phone; itemized table (description, IMEI, unit cost, total); totals (subtotal, GST, PST, adjustment, grand total); payment breakdown + remaining credit; notes.
- **Viewer:** swipeable pages (iOS) / prev-next paging (macOS); zoom/scroll.
- **Edit contact info:** modal to change company details for this bill or **save permanently** to backend; regenerates the bill.
- **Share/Export:** generates a merged **PDF** (`Purchase_Invoice_{order}.pdf`); iOS share sheet / macOS Finder + AirDrop.

---

## 4. Sales Flow

Same skeleton as Purchase (order #, date, entity selection, taxes, adjustment, notes, invoice generation) with sales-specific differences:

- **Customer selection:** searchable dropdown of entities, showing each entity's current balance.
- **Add products is a two-step flow:**
  1. **Pick IMEIs from inventory** — filter by storage location; table of available phones (brand/model/capacity/color/carrier/status/IMEI/cost); multi-select; "Active only" toggle; phones already in cart are blocked.
  2. **Set selling price per phone** — live **profit/loss per device** vs. inventory cost (green/red).
- **Add services:** name + price.
- **Payment:** Cash + Bank; **Credit Card N/A for sales**; live credit/outstanding; overpayment warning.
- **Middleman tracking (optional toggle):**
  - Select a middleman entity; enter amount with **Give/Receive** direction.
  - Separate payment breakdown (Cash/Bank/Card — Card only in Give mode); middleman credit computed.
- **Barcode auto-add:** a scanned IMEI (from the Scanner) is looked up in inventory and auto-added to the sale cart, with success/not-found/already-in-cart feedback.
- **Profit Breakdown dialog (multi-currency):** exchange-rate profit converted to CAD via Direct Exchange Rates; per-currency profit with CAD equivalent and rate-availability indicator; filter by Daily/Weekly/Monthly/Yearly/All-time.
- **On confirm:** saves the sale (items, taxes, adjustment, payments, credit, middleman details, notes), records whether order # was auto/custom, updates balances, opens the Sales Invoice (same engine as Purchase).

---

## 5. Profiles / Entities

Manage Customers, Suppliers, and Middlemen.

- **Three entity types,** color-coded (Customer=blue, Supplier=green, Middleman=orange), stored separately.
- **Tabbed list** by type, each tab shows a count.
- **Search:** by name, phone, or balance amount; live.
- **List layout:** cards on iPhone; table (Name / Balance / Phone / Actions) on iPad/macOS. Balance color-coded. Inline **delete** (confirmation alert; transaction history preserved after deletion).
- **Add/Edit Entity dialog:**
  - Type selector (Customer/Supplier/Middleman — changing type moves the entity).
  - Name (required), Initial balance with **To Receive / To Give** toggle (sets sign), Phone, Email, multi-line Address, multi-line Notes.
  - Success toast + haptics (iOS); name required to save.
- **Entity Detail view:**
  - Header: name, type badge, contact info, notes.
  - **Balances:** primary CAD balance card + horizontally-scrolling cards for each other currency, each with inline edit (currency picker, amount, To Receive/To Give).
  - **Transaction history** for the entity: searchable; filter chips by type (Purchase/Sale/Middleman/Transaction-Cash); date-range (From/To, "Forever"); color-coded rows (purchase/sale/middleman/currency/exchange/expense/balance-adjustment); tap a row to open its full bill/detail.
  - **Print Ledger** button → date-range dialog → generates the entity's ledger (see §8).

---

## 6. Inventory  *(per-phone, IMEI-level)*

- **Hierarchical view:** Brand → Model → individual Phone.
  - macOS/iPad: Excel-style grid (IMEI, Capacity, Color, Carrier, Location, Price, Status, Actions) with collapsible brand/model sections and connector lines.
  - iPhone: expandable list of brand cards → model rows → phone cards.
- **Per-phone attributes:** IMEI, Brand, Model, Capacity (+GB/TB), Color, Carrier, Status (Active/Inactive pill), Storage location, Unit cost. Quantity is computed per matching group.
- **Summary stats:** Total Devices, total Inventory Value (sum of unit costs), unique Locations count; per-brand/model headers show unit counts, model counts, total value, and an **inactive count badge**.
- **Search:** across Brand/Model/IMEI/Color/Carrier.
- **Filters:** by Brand (dropdown), Status (All/Active/Inactive), Storage Location (clickable sidebar buttons with counts); **Clear Filters**.
- **Sort:** 8 options — Brand A–Z/Z–A, Model A–Z/Z–A, Quantity high/low, Price high/low.
- **Collapse/Expand all** bulk controls; everything starts collapsed.
- **Add Product:** same rich dialog as Purchase (brand/model/capacity/IMEI(s)/color/carrier/status/price/location, with add-new + iOS scan + macOS barcode listener).
- **Edit Product:** tap a phone row → pre-filled dialog; Model auto-updates when Brand changes.
- **Delete Product:** trash icon → confirmation showing IMEI → async delete with spinner, success toast ("Deleted IMEI …"), auto-dismiss.
- *(No multi-select/bulk edit or bulk delete today — individual operations only.)*

---

## 7. Scanner  *(iOS only)*

- Full-screen camera with a framed scan target; flashlight toggle (iPhone); larger frame on iPad.
- **Capture-photo model** (not continuous): freezes a frame, runs barcode detection.
- Supports QR, Aztec, Data Matrix, PDF417, Code128/93/39, EAN13/8, UPC-E.
- Handles **single / multiple / no** barcodes detected: pick from a numbered list when multiple; retake; cancel.
- On confirm, the barcode is written to the backend "scanner" channel → other surfaces (Add/Edit Product dialogs, Sales cart, macOS barcode listener) **pick it up automatically** and fill the IMEI / add the phone.

---

## 8. Histories & Ledger

### Histories view
- **Tabs:** Purchase, Sale, Middleman, Currency (Cash), Bank, Credit Card, Expense. The same transaction can appear under multiple payment-method tabs if split.
- **Search:** entity names, notes, order numbers, amounts, giver/taker, middleman, IMEIs.
- **Date filters:** All Time, Today, This Week, This Month, This Year, or Custom range (inclusive).
- **Day-wise pagination:** loads the most recent day first; "Load Previous Day" footer loads older days; state preserved across tab switches.
- **Row content:** date/time (with Today/Yesterday), entity (or "Giver → Taker" for currency), color-coded type badge, description, payment method(s), signed/color-coded amount, credit owed.
- **Deduplication:** third-party currency transfers show once as "Giver → Taker"; personal-account transfers show once.
- Tap a transaction → opens its full bill/detail (reuses the invoice screen).

### Ledger / statement generation
- Reached from **Entity Detail** (one customer) or from **Histories** (whole category, e.g. all Purchases).
- **Date-range dialog** first (both start & end optional; empty = forever / through today).
- **Content:** company header (first page), entity info + period + generation date, chronological transaction table (date/time, entity, type, description, payment-method breakdown, signed amount, credit column), and a **summary** (Total Inflow, Total Outflow, Net Balance) on the last page; page numbers + repeated company footer.
- **Multi-page pagination** with header only on page 1 and summary only on the last page.
- **Edit contact info** dialog (temporary for this PDF, or permanent to backend), show/hide header toggle.
- **Output:** rendered HTML preview → merged **PDF**; iOS share sheet / macOS file viewer + AirDrop; filename includes entity + date range.

---

## 9. Balance Report

A company-wide receivables/payables dashboard.

- **Summary cards (horizontal scroll):**
  - **Total I Owe** (red) — per-currency amounts where balance is negative ("All settled" if none).
  - **Total Due to Me** (green) — per-currency positive balances ("Nothing due" if none).
  - **Account balances:** My Cash, Bank, Credit Card.
  - **Inventory Value:** total cost of all phones in stock.
- **Search:** by customer name or balance amount, with result count + clear.
- **Filters popup:** currency filter; sort by Name / Total Balance / CAD Balance / Last Updated; sort-direction toggle; min/max amount range; Clear All.
- **Table (iPad/macOS):** Contact (name+type), Phone, Email, Address (hidden on iPad), and one balance column **per currency** showing amount + "To Receive"/"To Pay"/"Settled". Rows clickable → customer detail.
- **List (iPhone):** customer cards with type chip, phone, primary CAD net balance + status, and secondary currency chips.
- Loading / empty / refresh states.

---

## 10. Statistics  *(biometric-locked)*

- **Gate:** Face ID / Touch ID / device auth required before viewing.
- **Period picker:** All Time / Today / This Week / This Month / This Year / Custom (From–To).
- **Overview cards (tappable → detail breakdown sheets):** Purchases, Sales, Profit (sales−purchases), Net (after expenses), Expenses. Each detail sheet lists the contributing transactions (date/entity/amount).
- **Expenses:** breakdown by method (Cash/Bank/Card) + top 5 categories.
- **Tax:** GST and PST split into Sales vs Purchases (tappable for detail).
- **Payment methods:** Cash/Bank/Card × Purchases/Sales table + stacked "payment mix" chart.
- **Charts:** Sales vs Purchases bar; Phones Purchased vs Sold bar.
- **Volume mini-stats:** phones purchased/sold, service items (purchases/sales).
- **Top lists:** top products by purchase revenue and by sales revenue (qty + revenue); top suppliers (by purchases); top customers (by sales).

---

## 11. Cross-Cutting Features to Carry Forward

- **IMEI-level inventory** is the backbone: phones are individual units identified by IMEI, scanned in, costed, located, and sold individually with per-unit profit.
- **Multi-currency** everywhere: per-entity, per-currency balances; configurable currencies; bidirectional + direct exchange rates; exchange-profit tracking.
- **Three payment rails** (Cash / Bank / Credit Card) consistently, with Credit Card disabled on Purchase & Sales primary payment but used in expenses and middleman-give.
- **Auto-incrementing, real-time-synced order numbers** with manual override.
- **PDF generation** (invoices + ledgers) via HTML templates, paginated, with editable/persistable company header and platform-appropriate sharing.
- **Barcode scanning** as a cross-device channel (scan on iPhone → consumed on macOS/iPad).
- **Atomic transaction writes** that simultaneously update inventory, entity histories, and account balances.
- **Biometric protection** on sensitive analytics.
- **Responsive, platform-aware UI** (sheets vs modals, lists vs tables, math-input on desktop) — re-create the equivalents per KMP target.
