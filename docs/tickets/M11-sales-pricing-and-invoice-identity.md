---
name: Feature / Task ticket
about: Sales — tax-inclusive pricing, discount placement, post-sale dialog, customer tax number
labels: []
---

## 📖 Story / Why

Four things on the Sales screen, three small and one with real arithmetic behind it.

**The one that matters:** every price in the app is currently **tax-exclusive** — tax is added on
top at checkout. Some customers are quoted a single all-in figure ("that's $700 out the door"), and
the cashier then has to work backwards by hand to make the total land on $700. A per-sale toggle
lets them type the all-in price and have the app work out the tax component.

The other three are papercuts a cashier hits every day: the discount sits on the wrong side of the
screen, the post-sale dialog's only button is labelled misleadingly, and a business customer's
invoice can't carry their GST number — so they can't claim their input tax credit from it.

## 🧭 Context

**Where this sits.** All four changes are on the Desktop Sales flow plus the contact record. Nothing
here touches Humble Ledger, and nothing touches the Bill Engine (see below — already verified).

### 1. Tax-inclusive pricing — a per-sale toggle

Today `SaleCalculator.compute` does:

```
subtotal      = Σ (unitPrice − lineDiscount)
taxableAmount = subtotal − saleDiscount        (clamped ≥ 0)
tax_i         = round½up(taxableAmount × rate_i, 2)   one leg per enabled tax
grandTotal    = taxableAmount + Σ tax_i
```

With the toggle **on**, the typed prices already *contain* the tax, so the arithmetic inverts:

```
grandTotal    = subtotal − saleDiscount        (what the customer pays — as typed)
taxableAmount = grandTotal ÷ (1 + Σ rate_i)
tax_i         = taxableAmount × rate_i
```

**The exactness rule — this is the part to get right.** `taxableAmount + Σ tax_i` must equal
`grandTotal` **to the penny**, always. Rounding each leg independently will drift by a cent and the
ledger won't balance. Do it this way:

1. `taxableAmount = round½up(grandTotal ÷ (1 + Σ rate_i), 2)`
2. compute every tax leg **except the last** as `round½up(taxableAmount × rate_i, 2)`
3. the **last** leg absorbs the remainder: `grandTotal − taxableAmount − Σ(other legs)`

That guarantees the identity holds for one tax, two taxes, or none.

**You will need division, and `Money` has none.** `Money` (shared, `util/Money.kt`) does string
add / subtract / compare / `multiplyRate`, deliberately never touching floats. Step 1 needs a
divide. Add `Money.divide(amount, divisor, scale = 2)` as pure string long division with half-up
rounding, alongside the existing helpers, and unit-test it — including `0.01 ÷ 3`, a divisor of
exactly `1`, and the BC case `700.00 ÷ 1.12`. **Do not** parse to Double as a shortcut; that is the
one thing `CLAUDE.md` forbids outright for money.

**Mode is per sale, and resets.** Each new sale starts tax-**exclusive**, exactly as today. A
cashier who flips it for one customer cannot leave it on and silently under-charge tax on the next
twenty sales. Store the flag on the sale record (`taxInclusive: Boolean`) so a later report can say
which sales were priced which way — the manager's condition for accepting a per-sale toggle rather
than a company-wide setting.

**Nothing downstream changes.** `syncSale` already sends HL the pre-tax `amount` plus explicit
`taxLines`, and the invoice payload already sends `subtotal` / `taxAmount` / `total` separately —
so an inclusive-priced sale posts and prints correctly with no change to either, as long as
`SaleTotals` is filled in correctly. The invoice will still break the tax out as a line, which is
what a tax invoice legally requires.

### 2. Sale discount moves to the right

It currently sits in `CartPane` (left), pinned under the cart list. It belongs in `CheckoutPane`
(right), next to `TotalsCard` and the payment fields — that's the number it changes. Move the field
and its `sales_error_sale_discount` hint together; leave the per-line discounts where they are.

### 3. "New sale" → "Done"

`SaleCompleteDialog`'s only button reads **New sale**, which reads like "discard this and start
another" at the moment the cashier is waiting on the invoice. Relabel it **Done**.

**Keep the behaviour identical** — it must still call `startNewSale()`, which clears the cart. A
button that only dismissed would leave the previous customer's cart loaded for the next sale.

**No change is needed to protect invoice generation.** It runs server-side in the `onSaleWrite`
Cloud Function the moment the sale doc is written; the dialog only *watches* it via a live listener.
Dismissing at any point cannot cancel it, and the sale + its invoice remain in Sales History (which
has its own retry). Verify this rather than assume it — close the dialog while the invoice row still
reads "preparing", then find the sale in Sales History and confirm the PDF issued.

### 4. Customer tax number on the invoice

We store the **shop's** GST number (`companySettings.taxNumber` → prints as "GST/HST No: …") but not
the **customer's**. A contact has name, phones, email, address, roles and notes — no tax number —
and the invoice payload sends only the buyer's name / address / phone / email.

**The invoice template is already ready — do not touch the Bill Engine.** Verified against the live
config: `billApps/aromex` (Firestore project `humble-bill-engine`) points at
`templates/aromex/invoice.html` in S3, and that template's *Bill To* box already contains
`{{customerTaxLine}}`. The Lambda renders an absent placeholder as an empty string, which is why
nothing shows today. So this is **entirely app-side**: populate `customerTaxLine` in the payload and
the line appears. Aromex's template is its own S3 object, separate from Dreamland's — but you have
no reason to open it at all.

**Snapshot it onto the sale.** Copy the customer's tax number onto the sale document at record time
rather than reading the contact when the invoice renders — the same reason `taxLines` are
snapshotted. Editing a contact next year must not change an invoice already issued.

Optional everywhere: a contact without one is normal, and the invoice line simply doesn't appear.
Walk-ins have no contact, so they have no tax number — that's fine and out of scope.

## 🔑 Access & prerequisites

- **Repo:** `Aromex-KMP` (this one). Work on Desktop + `sharedLogic` + `firebase/functions`.
- **Firebase project:** `aromex-june-2026`. You need the Desktop app signed in as an **admin** to
  test the invoice end to end. Credentials: **ask the manager via the team password manager** — do
  not put them in the issue, a commit, or a handoff.
- **Cloud Functions deploy:** needed to test #4 (the payload change lives in `syncWorker.ts`).
  Deploy **by name only** — `firebase deploy --only functions:onSaleWrite`. Never a bare
  `--only functions`, and **never `--force`**: that deleted a colleague's function on this project
  once already.
- **Bill Engine / AWS:** **not required.** If you think you need it, re-read §4 — you don't.
- **No new secrets** are introduced by this ticket.

## ✅ Scope / What to build

**Shared (`sharedLogic`)**
- `Money.divide(amount, divisor, scale)` — string long division, half-up, no floats. Tested.
- `SaleCalculator.compute(..., taxInclusive: Boolean)` — the inverted path above, with the
  last-leg-absorbs-the-remainder rule.
- `SaleInput.taxInclusive`, `SaleRecord.taxInclusive`, carried through `RecordSaleUseCase`.
- `SaleRecord.buyerTaxNumber` — snapshotted from the customer at record time.
- `Entity.taxNumber: String?`.

**Desktop**
- Sales checkout: a tax-mode toggle (label it in plain words — "Prices include tax"), defaulting to
  off on every new sale, next to the totals.
- `TotalsCard` reads correctly in both modes — subtotal, each tax leg, grand total. In inclusive
  mode the customer-facing total must equal exactly what was typed.
- Move the sale-discount field + its hint from `CartPane` to `CheckoutPane`.
- `SaleCompleteDialog`: button label → **Done** (new i18n key; keep `startNewSale()`).
- Contact create/edit form: an optional **Tax number** field.
- `BackendSalesRepository`: write `taxInclusive` and `buyerTaxNumber` on the sale doc.
- `BackendEntityRepository`: read/write `taxNumber`.

**Cloud Functions**
- `SaleData` gains `buyerTaxNumber`; `buildInvoicePayload` sends
  `customerTaxLine: "GST/HST No: <n>"` when present, mirroring `sellerTaxLine`, and omits the key
  entirely when absent.

**Rules / docs**
- `firestore.rules`: allow `taxNumber` on entities; confirm the sale-create guard still passes with
  the two new fields.
- `docs/SCHEMA.md` + `firebase/SCHEMA.md`: document `entities.taxNumber`, `sales.taxInclusive`,
  `sales.buyerTaxNumber`.

## 🎯 Acceptance Criteria

**Tax-inclusive**
- [ ] With the toggle **off**, every existing sale behaves **exactly** as before — verified by the
      existing `SaleCalculatorTest` suite still passing unchanged.
- [ ] With it **on** and GST 5% + PST 7%: a single line typed as `700.00` produces
      `taxableAmount + GST + PST == 700.00` **exactly**, and the customer-facing total reads
      `$700.00`.
- [ ] The identity `taxableAmount + Σ taxLines == grandTotal` holds to the penny for: one tax, two
      taxes, no tax, a sale discount, and a line discount. Property-style test over several amounts.
- [ ] `Money.divide` has its own tests, including a repeating decimal and a divisor of `1`.
- [ ] The toggle resets to **off** on `startNewSale()`.
- [ ] `taxInclusive` is stored on the sale document.
- [ ] An inclusive-priced sale posts to HL with the **pre-tax** amount plus its tax legs, and the
      party's balance moves by the full typed total.

**Discount**
- [ ] The sale-discount field and its "discount exceeds subtotal" hint render in the checkout pane;
      neither remains in the cart pane. Gating behaviour is unchanged.

**Dialog**
- [ ] The button reads **Done** and still clears the cart for the next sale.
- [ ] Dismissing it while the invoice row still reads "preparing" leaves the sale intact and the
      invoice still issues — confirmed by finding that sale in Sales History with a working PDF.

**Customer tax number**
- [ ] A contact can be saved with, and without, a tax number.
- [ ] Selling to a customer **with** one prints `GST/HST No: …` in the invoice's *Bill To* box.
- [ ] Selling to a customer **without** one, and to a walk-in, prints no such line and no stray
      label or empty row.
- [ ] Editing the contact's tax number afterwards does **not** change an already-issued invoice.

**Everything**
- [ ] `./gradlew :sharedLogic:jvmTest :desktopApp:test` and `npm test` in `firebase/functions` all
      pass, and `npm run test:rules` passes if rules changed.

## 🚫 Out of scope

- A company-wide tax-mode setting (the manager chose a per-sale toggle deliberately).
- Capturing a tax number for a walk-in at checkout — contacts only for now.
- Any change to invoice numbering, to Humble Ledger, or to the Bill Engine / its templates.
- Retro-fitting `taxInclusive` onto sales already recorded (absent reads as exclusive, which is
  correct for every one of them).

## 🔗 Dependencies

- None blocking. Builds on the tax config in Settings (M10) and the sale/invoice spine (#61, #76).

## 📚 References

- `sharedLogic/.../usecase/SaleCalculator.kt` — the arithmetic to invert
- `sharedLogic/.../util/Money.kt` — where `divide` goes; note the no-floats rule
- `desktopApp/.../ui/sales/SalesScreen.kt` — `CartPane`, `CheckoutPane`, `SaleCompleteDialog`
- `firebase/functions/src/syncWorker.ts` — `buildInvoicePayload`, `resolveInvoiceBuyer`,
  `sellerTaxLine` (copy its shape for `customerTaxLine`)
- `docs/SCHEMA.md` — the business-date convention added in #107, for how sale fields are documented
- `CLAUDE.md` — money is decimal strings, never Double; `/kmp-arch` for where logic lives

## 🖼️ UI standards

*No design is attached and none is needed — these are modifications to an existing screen. Match the
current Sales screen exactly: reuse its components, spacing and brand tokens. If anything is
genuinely ambiguous, ask the PM at kickoff rather than inventing a new visual language.*

- [ ] **Reuse, don't re-style.** Use the existing field shells, buttons and the shared table/toolbar
      parts already used by the Sales, Money and Stock History screens. No one-off colours or sizes.
- [ ] **Light and dark themes** both verified; every colour from a theme token defined in both.
- [ ] **Native components** — the toggle should be the platform switch, not a hand-rolled control.
- [ ] **Responsive:** the desktop window resizes to a sensible minimum and the checkout pane reflows
      without clipping. The pane already scrolls — keep it scrolling with the discount field added.
- [ ] **Keyboard:** the discount and tax-number fields take a numeric-appropriate keyboard; Tab moves
      to the next field; the focused field stays visible above the keyboard/scroll.
- [ ] **Truncation:** a long tax number or customer name ellipsizes cleanly rather than pushing the
      layout.
- [ ] **States:** loading / empty / error / disabled all defined for anything that submits; errors
      inline and in the screen's existing style, never a raw dump.
- [ ] **Accessibility:** labels on the toggle and both new fields; sensible focus order; adequate
      contrast; layout survives the largest font scale.
- [ ] **i18n:** every new user-facing string goes through `Strings` + `EnglishStrings` — no literals
      in the UI.
- [ ] **Architecture (`/kmp-arch`):** the tax arithmetic lives in `sharedLogic` (`SaleCalculator`),
      **not** in the UI. The ViewModel holds the toggle state; the screen only renders it.

## 🤖 Kickoff prompt (paste into Claude Code)

```
/start-ticket <#>
```

Before writing code, read `CLAUDE.md`, `docs/SCHEMA.md` and `SaleCalculator.kt`, and confirm you can
state the exactness rule for tax-inclusive rounding back to me in one sentence. Start with
`Money.divide` and its tests — everything else depends on it being right.
