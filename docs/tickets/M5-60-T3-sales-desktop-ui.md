---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M5] Sales T3 — Desktop UI (polished counter screen)"
labels: []
assignees: []
---

**Brief:** #60

## 📖 Story / Why
This is the **counter** — the polished Desktop screen a cashier actually rings a sale on: build a
cart of in-stock phones (plus the odd non-inventory item), edit prices, apply discounts, pick a
named customer or a walk-in, take a split cash/card payment, and confirm to a clear "sale
complete" screen. Desktop is where the counter lives, so **Desktop is polished first** (phones
stay bare in T4). It binds to the T2 ViewModel — **no business logic in the UI.**

## 🧭 Context
- **`/kmp-arch`:** Compose-Desktop UI bound to the Desktop `SalesViewModel` (T2) via `StateFlow`;
  rendering + action dispatch only.
- **Reuse, don't rebuild** (`[preference]` steer): the **inventory browse table (#55/#57)** is the
  item picker; the **`FilterableDropdownField` (#58)** is the customer picker; the Desktop money
  field + `MoneyFormat` (session currency); the gradient-header / card / footer theme from the
  entity form (the #58 Desktop purchase dialog is the closest reference).
- **Single-screen two-pane POS**, not a wizard — the brief's bar is "done in a few clicks."

## 🔑 Access & prerequisites
- **Design assets:** the PM provides the approved design (Figma / screenshots). **They are NOT in
  this issue — ask the PM for them at `/start-ticket` before building**, and match them exactly.
  If no design is provided, build against the brand kit (`docs/brand-kit.md`) + the existing
  Entities/Inventory Desktop screens as the visual reference.
- **T2 merged** (Desktop `SalesViewModel`). Firebase dev config (team password manager / manager).
- Run the Desktop app locally to verify (`/run`).

## ✅ Scope / What to build
- [ ] **Sales destination** in the Desktop nav (mirror the Entities/Inventory route); nav item
      visible only with `sales` VIEW.
- [ ] **Two-pane layout** under a gradient header:
  - **Left (cart):** "+ Add phone" / "+ Item" actions; one row per cart line — inventory line
    (label + IMEI, editable **price** field, editable **discount** field, derived net, original
    `listPrice` shown subtly/struck when discounted, remove); custom line (name, price, discount,
    remove); a **whole-sale discount** field beneath the list.
  - **Right (checkout):** customer `FilterableDropdownField` (searchable, **Walk-in Customer as a
    selectable default**); **Cash / Card / Bank** money fields; optional note; a **totals card**
    (subtotal, one row per `taxLine`, grand total, amount paid, **balance highlighted**); inline
    validation hints from `errors`; **Confirm sale** button (disabled unless `canConfirm`).
- [ ] **Item picker modal** wrapping the #55/#57 `InventoryBrowseTable` filtered to in-stock, with
      search + location filter; clicking a row calls `addUnitToCart`; **stays open for multi-add**
      with an "added" affordance; excludes units already in the cart.
- [ ] **Confirm outcomes** (render `confirmState`): `Submitting` (spinner, inputs locked);
      `Success(saleId)` → **"Sale complete" modal** (customer, item count, grand total, paid,
      balance) + **"New sale"** → `startNewSale()`; `AlreadySold(imei,label)` → **graceful dialog**
      ("… was just sold — removed from the cart") with the line removed; `Error` → inline banner,
      cart preserved.
- [ ] **i18n:** all strings via `Strings.kt` / `EnglishStrings.kt` (add sales keys).

## 🖼️ UI standards
- [ ] **Match the provided design exactly** — reproduce spacing, sizing, color, type, hierarchy,
      and every state. Do not approximate or "improve" it. *(Only if the ticket says "no design"
      do you design against the brand kit.)*
- [ ] The design is provided by the **PM and is not attached** — **ask for it at `/start-ticket`
      before building.**
- [ ] Use **design-system tokens** and **reuse/extend shared components** — no one-off colors/sizes,
      no duplicated component code (reuse the browse table, dropdown, money field, theme).
- [ ] Support **both light and dark themes** — every color from a token defined in both; verify in
      both.
- [ ] Prefer **native Compose-Desktop components** (menus, dialogs, lists, text fields); where the
      design can't be done natively, tell the PM, explain the trade-off, and proceed with the
      closest native approach.
- [ ] **Resizable window with a sensible minimum size and a layout that reflows** — no clipping or
      fixed-size assumptions; cap/centre content width where appropriate.
- [ ] **Correct text truncation** — labels/IMEIs ellipsize (`…`) cleanly instead of clipping or
      breaking the row.
- [ ] **Numeric input** for all money fields (price, discount, cash/card/bank); keep the focused
      field visible; sensible focus order across the form.
- [ ] Define **loading / empty / error / disabled** states for the picker and the checkout; disable
      controls + show progress during `Submitting`; surface errors inline (design-consistent), never
      raw dumps.
- [ ] **Preserve UI state** across window resize (cart, field input, selection, scroll).
- [ ] Accessible labels on all interactive elements; sufficient contrast (WCAG AA); respect font
      scaling.
- [ ] **No hardcoded user-facing strings** (i18n); follow `/kmp-arch` — **no business logic in the
      UI**; no secrets committed.

## 🎯 Acceptance Criteria
- [ ] A cashier can, in a few clicks, build a multi-phone cart (via the reused browse-table picker),
      add a non-inventory item, edit a price, apply per-item **and** whole-sale discounts, pick a
      named customer **or** walk-in, enter a **split** cash/card payment, add a note, and confirm.
- [ ] Original price **and** discount are both visible (discounting is legible on screen).
- [ ] Totals card shows subtotal, per-tax lines, grand total, paid, and the **highlighted balance**;
      a walk-in cannot confirm while short-paid (button disabled + "must pay in full" hint).
- [ ] **Sale complete** shows an on-screen confirmation with a "New sale" action (no PDF/receipt).
- [ ] The **already-sold** race shows a graceful dialog and removes the line — never a crash or
      silent drop.
- [ ] Light **and** dark verified; window resizes/reflows without clipping; matches the PM's design.
- [ ] Nav item gated on `sales` VIEW; the use case remains the authoritative Confirm gate.
- [ ] `desktopApp:compileKotlin` passes; verified by running the Desktop app end-to-end.

## ✅ PO ruling (Gate-1 sign-off, 2026-07-22 — resolved)
Swipe fee = **(a) business absorbs it** (see #61) — **no surcharge UI to build.** If the shop ever
chooses to surcharge, the cashier can add a "Card surcharge" **non-inventory custom line** via the
existing "+ Item" action — no extra work then either. Nothing to add to this screen.

## 🚫 Out of scope
- Android / iOS UI (T4).
- Printed / PDF receipt or invoice; the tab-collection screen; returns/refunds.
- Any new business logic or ViewModel changes (consume T2 as-is).

## 🔗 Dependencies
- **T2** (`M5-60-T2`) — Desktop `SalesViewModel`.
- **#55/#57** (inventory browse table) and **#58** (`FilterableDropdownField`) — merged, reused.

## 📚 References
- Brief: #60 · `docs/briefs/B60-sales.md` · PRD `docs/PRD.md` §9.4
- `docs/brand-kit.md`; existing Desktop Entities/Inventory screens; #58 Desktop purchase dialog
- `/kmp-architecture`; `CLAUDE.md`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
