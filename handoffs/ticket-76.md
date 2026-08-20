# Handoff — Ticket #76

**Ticket:** #76 — [M6] Invoicing T1 — auto-issue a PDF invoice per sale (bill engine + HL number)

## Summary
Every completed sale now auto-issues a branded PDF invoice, entirely server-side. After a sale
posts to Humble Ledger and is marked `SYNCED`, the `onSaleWrite` Cloud Function reads the seller
letterhead from `companySettings/profile`, builds the Humble Bill Engine payload using the invoice
number HL already returns, POSTs it to the engine, and stores the resulting public PDF URL on the
sale (`invoiceUrl` + `invoiceStatus: ISSUED`). A render failure leaves the sale and the books
intact (marks `invoiceStatus: FAILED`) and is retried by the reconcile sweep, reusing the same HL
number so a retry never produces a second invoice. The payload contract is defined and unit-tested
once in shared Kotlin (`BuildInvoicePayloadUseCase`) and mirrored in the Cloud Function's
TypeScript, the same lockstep pattern the repo uses for `saleSourceId`. A new
`taxNumber` field (the shop's GST/HST registration number, legally required on a Canadian tax
invoice) was added to the company profile, provisioning, and schema. **No bill-engine code,
template, or config was touched.**

## Files changed

### Shared logic (`sharedLogic`)
- `model/InvoiceRequest.kt` *(new)* — the bill-engine payload contract (nullable optionals that are
  omitted, never blanked; money as decimal strings, converted to numbers only at the CF boundary).
- `usecase/BuildInvoicePayloadUseCase.kt` *(new)* — the pure, canonical payload builder: one row per
  unit (IMEI → `hsn`), custom lines with no `hsn`, 0–2 tax rows (GST+PST / single HST / none),
  CAD `*Disp` formatting, `currency:"USD"` sent glyph-only (commented), letterhead composition.
- `repository/UserRepository.kt` — `CompanyProfile` gains `taxNumber` + letterhead fields
  (`legalName`, `logoUrl`, `businessAddress`, `contactEmail`, `contactPhone`) the invoice needs.
- `commonTest/.../sales/BuildInvoicePayloadUseCaseTest.kt` *(new)* — 20 tests covering IMEI vs custom
  lines, GST+PST vs HST vs none, walk-in vs named buyer, omitted optionals, and money formatting.

### Android
- `data/FirestoreUserRepository.kt` — `getCompanyProfile` now reads `taxNumber` + the letterhead
  fields into `CompanyProfile`.

### iOS
- `repository/FirestoreUserRepository.swift` — `getCompanyProfile` maps the new profile fields
  (all args passed explicitly, since SKIE doesn't expose Kotlin default params to Swift).

### Desktop
- `data/FirestoreUserRepository.kt` — the profile mapper extracted to a shared `companyProfileOf`
  helper (used by both the one-shot read and the real-time listener) and extended with the new fields.

### Cloud Functions (`firebase/functions`)
- `billEngine.ts` *(new)* — `renderInvoice()`: 30 s timeout, a 200-without-`url` counts as failure,
  and a typed `BillEngineError` carries the engine's `details`.
- `hl.ts` — `createSale` now returns HL's parsed response (`data.invoice.invoiceNumber`) instead of
  `void`, so the caller can read the invoice number HL minted.
- `syncWorker.ts` — extended `SaleData` with the fields the payload needs; added the TS mirror of
  the payload builder + `formatIssueDate` + `resolveInvoiceBuyer` + `issueSaleInvoice` (issues after
  `SYNCED`, never throws, idempotent — skips an already-`ISSUED` sale); wired issuance into `syncSale`.
- `index.ts` — `onSaleWrite` timeout raised to 120 s (HL post + ~30 s cold render); the reconcile
  sweep now also retries SYNCED sales stuck at `invoiceStatus` PENDING/FAILED (in-memory
  `syncStatus` filter → no composite index).
- `billEngine.test.ts`, `invoice.test.ts` *(new)* — render success/failure/timeout, the TS payload
  mapping, and `issueSaleInvoice` success / failure→FAILED / retry-idempotency / named-buyer paths.

### Schema + provisioning + rules
- `firebase/scripts/types.ts` — `CompanySettingsDoc` gains `taxNumber: string | null`.
- `firebase/scripts/setup-project.ts` — new `--taxNumber` arg, written into `companySettings/profile`.
- `firebase/scripts/README.md` — documents the `--taxNumber` flag.
- `firebase/firestore.rules` — sale `create` now forbids any client-set `invoice*` field and allows
  optional string `buyerName`/`buyerPhone`; `update`/`delete` stay `false`.
- `docs/SCHEMA.md` — documents `taxNumber` on the profile and the `invoice*` + `buyerName`/`buyerPhone`
  fields on `sales/{saleId}`.

## How to test
Automated:
```bash
# Shared payload builder (pure)
./gradlew :sharedLogic:jvmTest --tests "*BuildInvoicePayloadUseCaseTest"
# Cloud Function tests
cd firebase/functions && npm test
# Compilation
./gradlew :sharedLogic:jvmTest :desktopApp:compileKotlin :androidApp:compileDebugKotlin
```
End-to-end (verified on the `aromex-june-2026` dev project): ring up a sale and watch the
`sales/{id}` doc flip `syncStatus:SYNCED`, then gain `invoiceNumber`, a working `invoiceUrl`, and
`invoiceStatus:ISSUED`; open the URL and confirm the letterhead, one row per phone with its IMEI,
correct CAD amounts, the tax breakdown, and that "USD" appears nowhere.

## Acceptance criteria
- ✅ Completing a sale stores a working `invoiceUrl` within seconds, no client-side engine call, no
  cashier wait — issuance runs in `onSaleWrite` after `SYNCED`.
- ✅ The PDF shows the letterhead from `companySettings/profile` (legal name, address, contact,
  GST/HST number, logo), the buyer, one row per phone with its IMEI, CAD amounts, the GST/PST (or
  HST) breakdown, total/paid/balance, and no "USD".
- ✅ The number on the PDF is the same number HL recorded — taken verbatim from
  `createSale`'s `data.invoice.invoiceNumber`.
- ✅ A render failure leaves the sale + books intact, sets `FAILED`, and is retried by reconcile; a
  retry reuses the same number → same URL (issuance is idempotent, skips `ISSUED`).
- ✅ Absent optionals leave those areas blank (omitted, never `""`).
- ✅ Zero changes to the bill engine, its templates, or its Firestore config.
- ✅ `sharedLogic:jvmTest` covers the payload builder; functions tests cover success, failure→FAILED,
  and retry idempotency; Android + Desktop compile; shared tests green; no secrets committed
  (`BILL_ENGINE_URL` lives in the gitignored `firebase/functions/.env`).

## Deviations / decisions
- **Buyer is an explicit parameter** to `BuildInvoicePayloadUseCase` (`buyer`, `buyerTaxNumber`),
  not derived inside it — the ticket's "SaleRecord + company profile + invoice number" is the core
  input; the CF resolves the buyer (walk-in capture, or the named party's `entities/{id}` profile)
  and passes it in, keeping the builder pure.
- **`issueDate` is formatted in the Cloud Function** and passed into the builder as a string, so the
  shared builder stays pure. It is formatted in **UTC** (the system has no per-company timezone yet),
  so an evening sale west of UTC can print the next day — acceptable for v1; see follow-ups.
- **`invoiceIssuedAt`** is a Firestore `Timestamp` (`serverTimestamp()`); the `issueDate` display
  string is derived from the same instant.
- **`onSaleWrite` timeout raised to 120 s** for render headroom; the design is timeout-safe anyway
  (SYNCED commits before issuance, so a timeout only leaves the invoice PENDING for reconcile).
- **Reconcile invoice retry is a separate query block** (`invoiceStatus in [PENDING, FAILED]`,
  `syncStatus === 'SYNCED'` filtered in memory), distinct from the existing `syncStatus` sweep.

## Open questions / follow-ups
- **`taxNumber` backfill:** the field is prompted for new companies via `setup-project.ts`, but
  existing companies have no value until `companySettings/profile.taxNumber` is set manually. A
  Settings screen to edit it is a separate future ticket (PO decision, noted in the ticket).
- **Named-customer tax line:** `entities/{id}` has no tax-number field, so `customerTaxLine` is
  always omitted today; add it if buyers ever need their registration number on the invoice.
- **Invoice date is UTC, not shop-local.** Add `companySettings/profile.timezone` and format
  `issueDate` against it when day-boundary accuracy matters (a late-evening sale west of UTC prints
  the next day today).
- **Runtime deprecations** (Node 20, older `firebase-functions`) surfaced at deploy — pre-existing,
  worth a separate upgrade ticket before the Oct 2026 decommission.
- **Per-tenant rollout:** this was verified on the `aromex-june-2026` dev project; each real client
  project needs the same functions + rules deploy and its own `taxNumber` set when this ships.
