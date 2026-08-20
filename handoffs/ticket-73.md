# Handoff — Ticket #73

**Ticket:** #73 — [Hardening] Walk-in / Unspecified-party HL sync — self-heal a bad cached `hlCustomerId`

## Summary
`resolvePartyHlCustomerId` returned a party's cached `entities/{party}.hlCustomerId` and trusted it forever — so if that id were ever cached in a bad state (pointing at a non-existent HL customer), every walk-in sale / unspecified-supplier purchase using it would fail or mis-post indefinitely, with no recovery. This adds a **reactive self-heal**: `hlPost`/`hlGet` now throw a typed `HlHttpError` carrying the HTTP status, and the customer-scoped HL posts in `syncSale`/`syncPurchase` are wrapped so that on a **404** (customer not found → stale id) the party is re-resolved (`createCustomer` is idempotent on `externalId`, so it returns the authoritative id, which is written back to the entity doc) and the post is **replayed exactly once**. Because every HL leg is idempotent on `appId+sourceId`, the replay never double-posts, and a second failure propagates (→ `FAILED`) so there is no retry loop. The heal covers both reserved placeholders (Walk-in Customer, Unspecified Supplier) and named parties. On the dev backend, both placeholders were confirmed to resolve to live HL customers — not poisoned — so no manual Firestore repair was needed.

## Files changed
**Server (Cloud Functions / HL client)**
- `firebase/functions/src/hl.ts` — new exported `HlHttpError extends Error` carrying `status`; `hlPost` and `hlGet` now throw it (same message text), so callers can react to specific codes without string-parsing.
- `firebase/functions/src/syncWorker.ts` —
  - `isStaleCustomerError(err)` — true for an `HlHttpError` with `status === 404`.
  - `repartyHlCustomerId(db, partyEntityId, cfg, token)` *(exported)* — re-creates the HL customer (idempotent `externalId`), writes the corrected `hlCustomerId`/`hlAccountId` back to the entity doc, returns the id.
  - `withCustomerSelfHeal(db, party, cfg, token, hlCustomerId, post)` *(exported)* — runs `post(hlCustomerId)`; on a stale-customer 404, re-resolves via `repartyHlCustomerId` and replays `post` once.
  - `syncSale` / `syncPurchase` — account resolution hoisted out of the customer-scoped block; the sale+payments (resp. purchase+payouts) now run inside `withCustomerSelfHeal` so only idempotent, customer-scoped legs are replayed.
- `firebase/functions/src/syncWorker.test.ts` — `vi.mock('./hl.js')` switched to spread the real module (keeps the genuine `HlHttpError` for `instanceof`) while mocking network fns; new `stale cached hlCustomerId self-heal (ticket #73)` suite: repair writeback, heal-and-replay-once on 404, non-404 propagates without healing, and at-most-one replay (no loop).

## How to test
1. `cd firebase/functions && npm test` — 17/17 pass, including the four new self-heal cases.
2. `npm run build` (tsc) — clean.
3. (Optional, live) Poison a dev entity's `hlCustomerId` to a bogus value, ring a walk-in sale, and confirm the doc reaches `SYNCED` (the function re-resolves the id and replays) rather than sticking on `FAILED`.

## Acceptance criteria
- **Self-heal when an HL post fails implying the cached id is invalid (e.g. HL 404) — re-resolve/re-create and retry instead of trusting the cached id forever** — ✅ met (`withCustomerSelfHeal` on 404 → `repartyHlCustomerId` + one replay).
- **Applies to both Walk-in Customer (sales) and Unspecified Supplier (purchases)** — ✅ met (wired into both `syncSale` and `syncPurchase`; heal is party-agnostic).
- **Confirm/clean a currently-poisoned `entities/walk-in-customer` doc on the dev backend** — ✅ met: HL confirmed both placeholders resolve to live customers (`externalId` walk-in-customer / unspecified-supplier); not poisoned, no manual repair required.

## Deviations / decisions
- Chose **Option A (reactive on 404 + retry)** over a proactive per-transaction validation GET — matches the ticket's wording and adds zero overhead on the healthy path.
- Detection uses a typed `HlHttpError.status === 404` rather than parsing error strings — more robust and reusable.
- Self-heal is applied to **any** party (idempotent `createCustomer`), which naturally includes both placeholders; this is broader than strictly the two placeholders but strictly safer.
- **Deployed** to the dev backend (`aromex-june-2026`, `firebase deploy --only functions`) — `onSaleWrite` / `onPurchaseWrite` / `onEntityWrite` / `reconcileEntities` all updated successfully.

## Open questions / follow-ups
- Deploy surfaced two advisories (not caused by this change): **Node.js 20 runtime deprecated** (decommission 2026-10-31) and an **outdated `firebase-functions`** — worth a runtime-upgrade ticket. A post-deploy **build-image cleanup** warning also appeared (clears on next deploy; optionally add an Artifact Registry cleanup policy).
