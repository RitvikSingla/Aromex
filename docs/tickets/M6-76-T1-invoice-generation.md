> **Backend integration.** Auto-issues a branded PDF invoice for every completed sale by calling the
> **Humble Bill Engine**, using the invoice number **Humble Ledger already returns**.
> ⚠️ **The bill-engine side is already built and live — you do not touch it.** The template and the
> `aromex` entity exist and are tested; your job is to build the payload and POST it.
> `/kmp-arch`: payload building is shared Kotlin, the HTTP call happens **only in the Cloud Function**.
> Milestone: **M6 — Invoicing + Sales History**.

## 🛑 Do NOT touch the Humble Bill Engine
The engine is one shared AWS Lambda serving **live daily clients** (`dreamland`, `cutq`, `HumbleSolutions`,
`bookMyDreamland`, `rtr`, `PrintQ`). The PM has **already provisioned and verified** everything Aromex needs:

- ✅ `templates/aromex/invoice.html` (S3) — designed, tested, signed off
- ✅ `billApps/aromex` (Firestore `humble-bill-engine`) — created with label defaults

**You must not** modify `lambda/index.mjs`, any template, any `billApps` doc, or the bucket. If you think the
engine must change, **stop and raise it** — that is a separate ticket with its own review.

## 📖 Story / Why
A sale is recorded and posted to the books, but the customer walks out with **no document**. This makes every
completed sale produce a real, branded PDF invoice with a proper tax breakdown, and stores its link on the
sale so it can be opened, printed or shared (UI is T2 #77).

## 🔌 The integration — exact contract

**Endpoint (from the Cloud Function only — it is unauthenticated, so it must never be reachable from a device):**
```
POST https://ty7dvtg7bygzryorzmszp6ykjy0qlhsv.lambda-url.us-east-1.on.aws/
Content-Type: application/json
```
Put the URL in function config, not in client code. Allow **~30 s** (cold start). Success = HTTP 200 **and**
a `url` in the body; anything else is a failure.

**Response:** `{ "message": "...", "url": "https://company-billing-templates.s3.us-east-1.amazonaws.com/invoices/aromex-<invoiceNumber>.pdf" }`
The URL is **public and permanent** — store it as-is.

### The payload
```jsonc
{
  "appId": "aromex",                       // fixed
  "data": {
    "invoiceNumber": "INV-000042",         // ← from HL's /sales response (see below)
    "issueDate": "29 Jul 2026",            // DISPLAY string, pre-formatted, shop-local date
    "currency": "USD",                     // ⚠️ glyph-only — see the note below. NOT a currency claim.

    // ── Seller (letterhead) — all from companySettings/profile ──
    "sellerName":     "Pukhraj Mobiles Ltd.",              // legalName, fall back to companyName
    "sellerAddress":  "123 Main St, Surrey, BC V3T 0A1",   // businessAddress
    "sellerContact":  "+1 (604) 555-0100  ·  a@b.com",     // contactPhone · contactEmail (compose; omit blanks)
    "sellerPhone":    "+1 (604) 555-0100",                 // contactPhone
    "sellerTaxLine":  "GST/HST No: 123456789 RT0001",      // ← composed string, see NEW FIELD below
    "logoUrl":        "https://…/assets/<shop>-logo.png",  // logoUrl (omit if null)

    // ── Buyer ──
    "customer": { "name": "…", "address": "…", "phone": "…", "email": "…" },
    "customerTaxLine": "GST/HST No: 987654321 RT0002",     // only if the buyer has one

    // ── Items: ONE ROW PER UNIT. IMEI goes in `hsn` (it is the IMEI column). ──
    "lineItems": [
      { "name": "Apple iPhone 14 · 256GB · Purple", "hsn": "353340195540565", "qty": 1, "rate": 900 },
      { "name": "Tempered glass screen protector",                            "qty": 2, "rate": 15 }
    ],
    "subtotal": 2530,                       // plain number; engine needs it

    // ── Totals: PRE-FORMATTED Canadian strings (see quirk #1) ──
    "subtotalDisp":    "$2,530.00",
    "discountLabel":   "Discount",          "discountDisp":   "-$30.00",
    "tax1Label":       "GST 5%",            "tax1Disp":       "$125.00",
    "tax2Label":       "PST 7%",            "tax2Disp":       "$175.00",
    "totalDisp":       "$2,800.00",
    "amountPaidLabel": "Amount Paid",       "amountPaidDisp": "-$1,800.00",
    "balanceLabel":    "Balance Due",       "balanceDisp":    "$1,000.00",

    "notesText": "Warranty: 30 days on used devices…"      // optional
  }
}
```

**Omit anything you don't have — the template collapses it.** Verified: a payload with only
`invoiceNumber`, `issueDate`, `sellerName`, `customer.name`, one line item, `subtotalDisp` and `totalDisp`
renders a clean invoice with **no orphan labels and no empty rows**. Never send `""` to "clear" a field —
just leave the key out. `docTitle` / `itemsLabel` / `currencyNote` / `footerNote` have defaults in the
entity config and only need sending to override them.

### Where the seller details come from — `companySettings/profile`
Already contains: `companyName`, `legalName`, `logoUrl`, `country`, `currency`,
`tax { gstEnabled, gstRate, pstEnabled, pstRate, isHST }`, `businessAddress`, `contactEmail`, `contactPhone`.
The Cloud Function has **never read this doc before** — it will need to (Admin SDK). Read it once per
invoice (or cache per invocation).

**🆕 NEW FIELD REQUIRED — `taxNumber`.** The seller's GST/HST registration number **does not exist anywhere
in the codebase** and is **legally required** on a Canadian tax invoice. Add it:
- to `CompanySettingsDoc` (`firebase/scripts/types.ts`),
- to the provisioning script (`firebase/scripts/setup-project.ts`, prompted like the other company fields),
- to `docs/SCHEMA.md`.
Compose `sellerTaxLine` as `"GST/HST No: <taxNumber>"`; **omit the whole line if `taxNumber` is absent**
(never render a dangling label). *(A Settings screen to edit these later is a separate future ticket — PO decision.)*

### Where the invoice number comes from
`POST /api/v1/sales` **already returns the invoice HL created**:
`response.data.invoice.invoiceNumber` (e.g. `INV-000042`). Use it verbatim.
**Do not** build a numbering counter, and **do not** use HL's `/api/v1/billing/*` endpoints — that route
drops the tax and custom fields this design depends on. `createSale` currently returns `void` — change it
to return the parsed response.

### ⚠️ Two engine quirks — work with them, don't fight them
1. **The engine formats money Indian-style** (`inr()` → `1,80,000.00`) and writes amounts in **Rupees**.
   That is why every total above is a **pre-formatted string under a `*Disp` name**. Do **not** send
   `taxRate`/`taxAmount` (they'd render Indian CGST/SGST rows), and never reuse the engine's own key names
   (`subtotal`, `total`, `discount`, `amountPaid`, `balanceDue`, `amountInWords`, …) for display values —
   the engine overwrites those. *One row per unit keeps each line under $100,000, where Indian and Western
   grouping are identical — which is why the items table can safely use the engine's formatting.*
2. **There is no CAD glyph.** `currency: "USD"` is sent **purely to select the `$` character**.
   It is **not** a currency claim: the string "USD" never appears on the PDF, the template prints
   *"All amounts are in Canadian Dollars (CAD)"*, and the sale in Firestore/HL stays CAD.
   **Comment this in code** so nobody later reads it as a USD sale.

## ✅ Scope

### Shared logic (`sharedLogic`)
- [ ] `model/InvoiceRequest.kt` — the payload contract above (nullable optionals; omitted, not blank).
- [ ] `usecase/BuildInvoicePayloadUseCase.kt` — **pure**: `SaleRecord` + company profile + invoice number →
      payload. One line per inventory unit (`hsn` = IMEI, `name` = brand · model · capacity · colour),
      custom lines as-is (no `hsn`), CAD-formatted `*Disp` strings, and **0–2 tax rows** built from the
      sale's snapshotted `taxLines` (GST+PST, or a single HST row). Money stays **decimal strings** in our
      code — convert to numbers only at the payload boundary.
- [ ] Extend the company-settings read to include `taxNumber`.

### Cloud Functions (`firebase/functions`)
- [ ] `billEngine.ts` — `renderInvoice(payload): Promise<string>`; 30 s timeout; treats a 200 without `url`
      as failure; typed error carrying the engine's `details`.
- [ ] `createSale` returns the HL response so `syncSale` can read `data.invoice.invoiceNumber`.
- [ ] `syncSale` — **after** HL is marked `SYNCED`: read `companySettings/profile`, build the payload,
      POST it, then persist `invoiceNumber`, `invoiceUrl`, `invoiceStatus: ISSUED`, `invoiceIssuedAt`.
      On failure → `invoiceStatus: FAILED` + `invoiceError`, **without** failing the HL legs or the sale.
- [ ] Extend the existing **reconcile sweep** to retry sales whose `invoiceStatus` is `PENDING`/`FAILED`.

### Schema + rules
- [ ] `sales/{saleId}` gains `invoiceNumber`, `invoiceUrl`, `invoiceStatus` (`PENDING|ISSUED|FAILED`),
      `invoiceIssuedAt`, `invoiceError`, and optional `buyerName`/`buyerPhone` (walk-in capture, UI in T2).
- [ ] `firestore.rules`: clients may set `buyerName`/`buyerPhone` on create but **never** any `invoice*`
      field (CF-owned); `update`/`delete` stay `false`. Update `docs/SCHEMA.md`.

## 🎯 Acceptance Criteria
- [ ] Completing a sale stores a working `invoiceUrl` within seconds, with **no client-side call** to the
      engine and **no cashier waiting**.
- [ ] The PDF shows the shop's letterhead from `companySettings/profile` (legal name, address, contact,
      GST/HST number, logo when set), the buyer, **one row per phone with its IMEI**, correct CAD amounts,
      the **GST/PST (or HST) breakdown**, total, paid and balance — and **"USD" appears nowhere**.
- [ ] The number on the PDF is **the same number HL recorded** for that sale.
- [ ] A render failure leaves the sale and the books intact, sets `FAILED`, and is retried by reconcile;
      a retry reuses the same number and produces the same URL (no second invoice).
- [ ] Optional fields absent → those areas are simply blank (no stray labels/rows).
- [ ] **Zero changes** to the bill engine, its templates, or its Firestore config.
- [ ] `sharedLogic:jvmTest` covers the payload builder (IMEI lines, custom lines, GST+PST vs HST, walk-in
      vs named buyer, omitted optionals, money formatting); functions tests cover success, failure→FAILED
      and retry idempotency. Android + Desktop compile; shared tests green. No secrets committed.

## 🚫 Out of scope
Any UI (T2 #77) · the Sales History screen · returns/credit notes/void/re-issue · emailing the invoice ·
**any bill-engine change** · the pre-existing public-URL exposure (PO has accepted it — do not "fix" it here).

## 🔗 Dependencies
Sales (#61–#64) and the `onSaleWrite` spine — merged. Engine reference (read-only):
`~/Desktop/Projects/HumbleBillEngine` — `lambda/index.mjs` (renderer internals),
`docs/cutq-invoice-api.md` (closest working example, tax-registered).

## 📚 References
- HL `POST /api/v1/sales` → `data.invoice.invoiceNumber` — `https://ledger.humblesolutions.in/docs`
- `firebase/functions/src/{index,syncWorker,hl}.ts` · `firebase/scripts/{types,setup-project}.ts`
- `docs/SCHEMA.md` (`companySettings/profile`, `sales/{saleId}`) · `CLAUDE.md` · `/kmp-arch`

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
