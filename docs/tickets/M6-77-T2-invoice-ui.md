> **UI for the invoice** produced by T1 — Desktop **polished**, Android/iOS **bare-but-stable** (the
> agreed platform strategy). Consumes what T1 writes; **no new business logic**.
> ⚠️ **Follow `/kmp-arch`** — native UI per platform, no logic in the UI. Milestone: **M6 — Invoicing +
> Sales History**.

## 📖 Story / Why
T1 makes every sale produce a PDF invoice and stores its link on the sale. This surfaces it: the cashier
sees the invoice appear on the **Sale complete** screen and can open, print or share it — and can capture
a walk-in buyer's name at checkout so it lands on the document.

## 🎯 The two pieces

### 1. Optional buyer capture at checkout (walk-ins)
A walk-in has no details on file, but a business buyer often wants their name on the invoice.
- On the checkout pane, when the selected customer **is the walk-in party**, show an optional
  **"Name for invoice"** (+ optional phone) field. Empty → the invoice reads "Walk-in Customer".
- Writes `buyerName` / `buyerPhone` onto the sale (fields defined in **T1 #76**; the CF uses them as the
  invoice's Bill-To, falling back to "Walk-in Customer"). **Never blocks confirming.**
- Not shown for a named customer — their Entity details are used.

### 2. The invoice on the Sale complete screen
The existing **Sale complete** dialog gains an invoice row that **resolves in place** as the background
job finishes (observe the sale doc — T1 writes `invoiceStatus`/`invoiceUrl`):

| `invoiceStatus` | Shows |
|---|---|
| `PENDING` (or absent) | *Preparing invoice…* with a small spinner |
| `ISSUED` | the **invoice number** + **View** · **Print** · **Copy link** · **Share** |
| `FAILED` | *"Invoice is still being prepared — it'll appear here shortly."* + a **Retry** affordance |

**Non-negotiable:** the cashier is **never blocked**. "New sale" is always available, and a failed or
slow invoice must never read as though the sale failed — the sale and the books are already committed.

- **Desktop:** *View*/*Print* open the URL in the system browser; *Copy link* to the clipboard.
- **Phones (bare):** the number, an **Open** action, and the OS **share sheet** — stock components only.

## ✅ Scope
- [ ] **Desktop (polished):** walk-in buyer fields on the checkout pane; the invoice row on Sale complete
      with all four states and View/Print/Copy/Share; matches the existing dialog styling; light + dark.
- [ ] **Android + iOS (bare-but-stable):** the same capability with stock components — buyer field,
      invoice number, Open, Share. No theming/polish.
- [ ] **All three ViewModels** expose the invoice state + buyer fields (per the platform strategy:
      logic everywhere, polish on Desktop). No new use cases — consume T1.
- [ ] **i18n** for every new string; money via the existing formatter.

## 🖼️ UI standards (Definition of Done)
- Reuse the existing dialog/button/field components; **light + dark** verified on Desktop.
- **States:** preparing / issued / failed / retrying all render; nothing is a dead end.
- **Graceful errors:** a failed invoice shows reassurance + retry, never a raw error or an implication
  that the sale broke.
- Long invoice numbers and shop names **ellipsize**; the dialog reflows on a narrow window.
- Accessibility: labelled actions, keyboard-reachable on Desktop, ≥44pt/48dp touch targets on phones.
- `/kmp-arch` — rendering + dispatch only; **no business logic in the UI**.

## 🎯 Acceptance Criteria
- [ ] Completing a sale shows *Preparing invoice…*, which becomes the **invoice number + actions** once
      T1 finishes — with the cashier able to start a new sale at any point.
- [ ] **View/Print** opens the correct PDF; **Copy link** puts a working URL on the clipboard;
      **Share** opens the OS share sheet on phones.
- [ ] For a walk-in, a name typed at checkout appears as the buyer on the PDF; left blank, it reads
      "Walk-in Customer". The field is absent for named customers and never blocks Confirm.
- [ ] A `FAILED` invoice shows the reassuring message + Retry, and the sale still reads as complete.
- [ ] Light + dark verified on Desktop; both phone builds compile and can complete a sale and open an
      invoice without crashing; `sharedLogic:jvmTest` stays green.

## 🚫 Out of scope
- The **Sales History** screen (next ticket) — reaching invoices for *older* sales lands there.
- Emailing/WhatsApping the invoice directly to a customer from their Entity record.
- Re-issuing, voiding or editing an invoice; returns/credit notes.
- Phone visual polish (a later pass off the Desktop reference).

## 🔗 Dependencies
- **T1 (#76)** — the invoice fields on `sales/{saleId}` and the CF that fills them.
- The bill-engine template + `aromex` entity are **already built, tested and live** — no engine work here.
- The existing Sale complete dialog + checkout pane (#63/#64).

## 📚 References
- T1 ticket (payload/fields contract) · `docs/SCHEMA.md` `sales/{saleId}`
- Desktop `ui/sales/SalesScreen.kt` (`SaleCompleteDialog`), `ui/sales/SalesViewModel.kt`
- `/kmp-arch`, `CLAUDE.md`, `docs/brand-kit.md`

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
