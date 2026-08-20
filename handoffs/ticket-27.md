# Handoff — Ticket #27 (M3 Profiles/Entities · T2: backend spine)

**Ticket:** #27 — [M3] Profiles/Entities — T2: backend spine (gateway internal token + entity sync Cloud Functions + rules + provisioning)
**Brief:** #25 · **Spans two repos, three PRs.**

## Summary
T2 builds the **server half of the HL dual-write spine**: when a client writes an `entities/{id}`
doc as `PENDING`, a Firebase Cloud Function creates the matching Humble Ledger customer
(idempotent), posts any opening balance, and flips the doc to `SYNCED` — with a scheduled reconcile
sweep as the durability backstop. To let the function (which has no user login) get an HL token, we
added a new **admin-authed gateway route**. We also added the `entities/` Firestore rules and the
reserved Walk-in provisioning. Everything is built + unit-tested; the gateway route, both Cloud
Functions, and the rules are **deployed**. Two config issues block the full end-to-end path — see
**Issues** below (one is pre-existing infra, not introduced by this ticket).

## The new route we created
**`POST /internal/hl-token`** (in `aromex-gateway`) — a server-to-server variant of the existing
`/hl-token`. The existing route requires a user's Firebase **ID token**; a Cloud Function has none.
This new route is guarded by the shared **`X-Admin-Token`** instead, takes **`{ companyId }` or
`{ projectId }`** (a function knows its own Firebase projectId for free), and returns the same
short-lived, company-scoped HL token from the same `mintHlToken` cache. **HL credentials never leave
the gateway.**

## Changes by area

### Gateway repo `aromex-gateway` — PR **#8** (branch `ticket-27-internal-hl-token`)
- `src/routes/internalHlToken.ts` — **new** `POST /internal/hl-token` (admin-guarded; resolves company by `companyId` or `projectId`; mints via `mintHlToken`; 400/404/502/500 handling).
- `src/server.ts` — register the route (+2 lines; existing routes untouched).
- `tests/internalHlToken.test.ts` — **new**, 8 tests (authz, validation, projectId path, 404, token, cache reuse).
- `README.md` — document the endpoint.
- **No Prisma schema / migration / dependency changes** — purely additive.

### Aromex repo `firebase/` — PR **#31** (T2a, branch `ticket-27-entities-rules-provisioning`)
- `firestore.rules` — **new `entities/{id}` block** via `hasPermission('profiles', …)` + `belongsToThisCompany()`: read = view, write = manage, no hard-delete (soft-archive only); clients may only start `syncStatus=PENDING`, can't forge `hlCustomerId`/`hlAccountId`/`hlSyncedAt` or `SYNCED`, and can't mint the reserved Walk-in.
- `scripts/setup-project.ts` — **step 6**: idempotently create the reserved `entities/walk-in` (`isWalkIn`, `PENDING`).
- `scripts/types.ts` — `EntityDoc`/`EntityRole`/`HlSyncStatus` + `WALK_IN_ENTITY_ID`.
- `SCHEMA.md` — document the `entities/{id}` collection.

### Aromex repo `firebase/functions/` — PR **#32** (T2b, branch `ticket-27-entity-sync-functions`) — NEW project
- `src/index.ts` — **`onEntityWrite`** (Firestore trigger) + **`reconcileEntities`** (scheduled 5-min sweep). Loop-safe (the function's own SYNCED write is a no-op).
- `src/syncWorker.ts` — reusable `syncEntity` worker + pure helpers (`primaryPhone`, `mapDirectionToHl` [CREDIT→PAYABLE], `openingSourceId` [`entity_<id>:opening`], `profileChanged`). Purpose-built so Purchase/Sales reuse it.
- `src/hl.ts` — gateway token broker (`getHlToken` via `/internal/hl-token` by projectId) + HL calls (`createCustomer` idempotent, `updateCustomer`, `postOpeningBalance`). Money stays a string; → number only at the HL boundary.
- `src/config.ts` — params `GATEWAY_BASE_URL`, `HL_BASE_URL` + secret `GATEWAY_ADMIN_TOKEN`.
- `package.json` / `tsconfig.json` / `.gitignore` / `README.md` — the functions project.
- `firebase.json` — register the `functions` codebase.
- `src/syncWorker.test.ts` — 7 helper unit tests.

## What is deployed (project `aromex-june-2026`)
- ✅ **Gateway** redeployed — `/internal/hl-token` **live** (authenticates with the admin token and resolves the company).
- ✅ **Cloud Functions** `onEntityWrite` + `reconcileEntities` — deployed (gen-2, us-central1).
- ✅ **Firestore rules** (with `entities/` block) — deployed.
- ✅ Firebase secret `GATEWAY_ADMIN_TOKEN` set to the correct server value (see Issue 2 — resolved).

## ✅ Issues — RESOLVED (config/infra, not code; e2e now verified)

> Both blockers below were fixed on the servers (no code change). End-to-end verified:
> a `PENDING` `entities/{id}` doc synced to `SYNCED` in <3s with `hlCustomerId`/`hlAccountId`
> populated; `/internal/hl-token` returns a valid token. See the PR #32 PM comment.

**1. Gateway couldn't reach HL — RESOLVED.** Two stacked problems: `HL_BASE_URL` pointed at
`http://127.0.0.1/api-server` (nginx routes by hostname; loopback 404) → repointed at HL's real
backend **`http://127.0.0.1:3001`**; and the HL account **password had been rotated** while the
gateway `.env` still held the old one (→ 401) → synced the current password + restarted. *(This had
also silently broken mobile/desktop HL access since the rotation — now restored.)* Not caused by this
ticket (server `.env` is excluded from deploys).

**2. Firebase `GATEWAY_ADMIN_TOKEN` was wrong — RESOLVED.** It had the local dev token (41 chars);
reset to the server's real 64-char token (Secret Manager v2) and **redeployed** `onEntityWrite` +
`reconcileEntities` to pick it up.

**3. Deploy incident (caused during this ticket, then fixed).**
The gateway redeploy used `rsync --delete`, which removed `start.sh` — a pm2 wrapper created directly
on the server (DEPLOY.md step 7) and **not in the repo** — so pm2 restart-looped and the gateway was
down (502) for a few minutes. Recovered by recreating `start.sh`, `pm2 restart`, and `pm2 save`.
**Follow-up:** `aromex-gateway/docs/DEPLOY.md`'s rsync should `--exclude start.sh ecosystem.config.cjs`
(or those files should be committed) so a future deploy doesn't repeat this.

## How to test (once Issues 1 & 2 are fixed)
1. Gateway route live: `curl -X POST http://68.183.86.89/gateway/internal/hl-token -H 'X-Admin-Token: <server token>' -H 'Content-Type: application/json' -d '{"projectId":"aromex-june-2026"}'` → expect a token (not `hl_upstream`).
2. In Firestore, create `entities/<id>` with `{ name, phones:[...], roles:["CUSTOMER"], isActive:true, isWalkIn:false, syncStatus:"PENDING" }`.
3. Watch the doc flip `PENDING → SYNCED` with `hlCustomerId`/`hlAccountId` populated (Functions logs: `firebase functions:log`).
4. Confirm idempotency: delete+recreate (or let reconcile run) → no duplicate HL customer.
- Unit tests: gateway `npx vitest run` (33), functions `npm --prefix firebase/functions test` (7).

## Acceptance criteria status
- ✅ `/internal/hl-token` route (admin-authed, no cred leak), tested + deployed.
- ✅ `onEntityWrite` create + opening-balance + patch SYNCED; `reconcileEntities` sweep — implemented, deployed, helper-tested.
- ✅ `entities/` Firestore rules with the stated guards — deployed (compiles via Firebase).
- ✅ Walk-in provisioning (idempotent).
- ✅ **Full PENDING→SYNCED e2e verified** — Issues 1 & 2 fixed on the servers (config/infra); a PENDING doc synced to SYNCED in <3s with `hlCustomerId`/`hlAccountId` set.

## Follow-ups
- Fix `HL_BASE_URL` (Issue 1) and reset the Firebase secret (Issue 2), then run the e2e above.
- Harden: give the function a **scoped** credential instead of the full admin token (esp. important once each client gets its own Firebase project — the same admin token would otherwise be replicated into every project).
- Fix the DEPLOY.md rsync landmine (Issue 3).
- Provisioning note: onboarding a new client must deploy these functions to that client's Firebase project + set the secret/params (projectId is automatic).
