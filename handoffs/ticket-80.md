# Handoff — Ticket #80

**Ticket:** #80 — [Invoicing] Walk-in invoices print an empty Bill To box (+ UTC date, dead payload builder)

## Summary
Three Gate-2 review follow-ups on the merged/in-flight invoicing work (#76/#77/#78), all
backend/Cloud-Function scoped. (1) Walk-in sales — the most common sale type — rendered a **blank
"Bill To" box** because `resolveInvoiceBuyer` returned `undefined` when no name was captured;
it now always returns a buyer with a non-empty name, falling back to the existing
`WALK_IN_CUSTOMER_NAME` for a nameless walk-in and for a named sale whose `entities/{id}` doc is
missing/unnamed. (2) The invoice date was the server's **UTC** date; it now formats in the shop's
IANA timezone (`companySettings/profile.timezone`) with a UTC fallback, anchors to the sale's
`createdAt` instant (not render time, so a retry can't reprint a different day), and the header now
carries the local **time-of-day + zone** (e.g. `30 Jul 2026, 8:32 PM PDT`) via the existing
`issueDate` field — no bill-engine template change. (3) The dead Kotlin `BuildInvoicePayloadUseCase`
+ `InvoiceRequest` models + their 297-line test were deleted — nothing at runtime called them (the
TypeScript `buildInvoicePayload` is the sole path), so the tests validated code that never ran.

This branch was cut from `ticket-77-invoice-ui` (not yet merged), so it depends on #77's walk-in
buyer capture. The scope of THIS ticket is the diff `e96e6f7..HEAD`.

## Files changed

### Server (Cloud Functions)
- `firebase/functions/src/syncWorker.ts` — `resolveInvoiceBuyer` always returns a non-empty-name
  buyer (fixes the empty Bill To); new `resolveZone` helper; `formatIssueDate(d, timeZone?)` now
  formats in the shop's zone with a UTC fallback; new `formatIssueDateTime` adds local time + zone
  abbreviation; `issueSaleInvoice` anchors the stamp to `data.createdAt` and passes
  `profile.timezone`. Added `createdAt` to `SaleData` and `timezone` to `CompanyProfileData`.
- `firebase/functions/src/invoice.test.ts` — asserts the walk-in payload's `customer.name`
  (the gap that let the bug through); tests for a captured walk-in name, a missing-entity named
  sale, `formatIssueDate` (UTC default, in-zone previous-day shift, month boundary, invalid-id
  fallback), `formatIssueDateTime` (local time + zone abbrev, UTC fallback), and an end-to-end
  `issueSaleInvoice` date-from-sale-instant assertion.

### Shared logic (deletions)
- `sharedLogic/.../usecase/BuildInvoicePayloadUseCase.kt` — **deleted** (dead: no runtime caller).
- `sharedLogic/.../model/InvoiceRequest.kt` — **deleted** (models used only by the dead builder).
- `sharedLogic/.../sales/BuildInvoicePayloadUseCaseTest.kt` — **deleted** (tested the dead code).

### Docs
- `docs/SCHEMA.md` — documents the new `companySettings/profile.timezone` field.
- `docs/PRD.md` — adds `timezone` to the profile schema and the §8.1 provisioning runbook step.

## How to test
1. `cd firebase/functions && npm ci` (if needed) then `npx vitest run` → **57/57 pass**
   (invoice suite 21, incl. the new #80 cases).
2. `npm run build` (tsc) → clean.
3. `./gradlew :sharedLogic:jvmTest` → green (confirms the Kotlin deletion left no dangling refs).
4. Manual (deployed): ensure `companySettings/profile.timezone = "America/Vancouver"`. Ring up a
   sale **after ~5pm Pacific** and open the invoice — the "Bill To" box is populated (walk-in →
   "Walk-in Customer") and the header reads the correct **local** date + time (e.g.
   `30 Jul 2026, 8:32 PM PDT`), not tomorrow's UTC date.

## Acceptance criteria
- **#1 Walk-in prints a filled "Bill To" (fallback to "Walk-in Customer")** — ✅ met
  (`resolveInvoiceBuyer` walk-in path).
- **#1 Add a payload assertion on `customer.name` (tests missed it)** — ✅ met (invoice.test.ts).
- **#1 Same fallback on the named path when `entities/{id}` is missing** — ✅ met.
- **#2 Invoice date in the shop's timezone once `profile.timezone` exists** — ✅ met (field added,
  consumed, UTC fallback). Ticket flagged this "can be its own ticket"; done here.
- **#3 Remove the drift risk — delete the Kotlin copy OR share a golden fixture** — ✅ met via
  **Option A (delete)**; TS `buildInvoicePayload` is the sole runtime + sole tested path.

## Deviations / decisions
- **#2 done in this ticket** rather than deferred — the fix was small and the field was easy to add.
- **#2 anchors to the sale's `createdAt`, not render time** — beyond the literal ask; prevents a
  next-day retry from reprinting a different date (legacy docs without `createdAt` fall back to now).
- **Invoice header now shows local time-of-day**, not just the date — requested during the ticket;
  folded into `issueDate` so no external bill-engine template change was needed.
- **#3 chose delete over golden fixture** — the payload only ever executes server-side (TS), so a
  second copy is maintenance debt, not shared logic.

## Open questions / follow-ups
- **`timezone` is not yet editable in-app** — `companySettings/profile` is provisioned by runbook
  (nothing in-app writes it). A future company-settings-editing ticket should surface it, and
  optionally add `timezone` to the shared Kotlin `CompanyProfile` (intentionally left out here).
- **Node.js 20 runtime is deprecated** (decommission 2026-10-31) — surfaced by the deploy; needs a
  runtime bump in a maintenance ticket (unrelated to #80).
- **Retroactive:** only invoices rendered after deploy get the new date/Bill-To; previously issued
  PDFs are unchanged by design.
