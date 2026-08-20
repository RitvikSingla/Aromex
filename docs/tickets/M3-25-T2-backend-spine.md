---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M3] Profiles/Entities — T2: backend spine (gateway internal token + entity sync Cloud Functions + rules + provisioning)"
labels: []
assignees: []
---

**Brief:** #25

> **Milestone:** M3 — Profiles / Entities (the HL dual-write spine).
> **Ticket 2 of 4** (T1 shared logic → **T2 backend spine** → T3 mobile → T4 desktop).
> This is **the reusable money spine** — build the idempotent HL create + HL↔Firebase dual-write + repair
> pattern here, once, so Purchase/Sales/Transactions copy it. Get it right.

## 📖 Story / Why
Saving an entity is a **dual write**: an operational record in the client's Firebase **and** a customer
account in Humble Ledger (HL) — and they must **never silently diverge** (PRD §6.3). We deliberately chose
the simplest domain (parties) to prove this pattern before line-items/tax/inventory pile on.

**Decision (locked with the manager): the HL write lives server-side in a Cloud Function, not on the
client.** The client only writes the Firebase doc as `PENDING`; a **Firestore-triggered Cloud Function**
creates the HL customer (idempotent), posts the optional opening balance, and flips the doc to `SYNCED`.
A scheduled **reconcile** sweep is the durability net. This ticket builds that server side + the gateway
auth it needs + the Firestore rules + the per-company provisioning.

## 🧭 Context
**End-to-end flow this ticket implements (server half):**
```
client writes entities/{id} { …profile, roles:[UPPER], syncStatus:"PENDING", opening? }   (T3/T4)
        │
        ▼  Firestore onCreate/onUpdate trigger  (THIS TICKET)
  get HL token (gateway /internal/hl-token) → POST /customers {name,email,phone:phones[0],externalId:id}
        │   (idempotent on externalId; idempotent:true ⇒ treat as success, not a dup)
        │   if opening present & not posted → POST /customers/{hlId}/opening-balance (idempotent sourceId)
        ▼
  patch doc → hlCustomerId, hlAccountId, syncStatus:"SYNCED", hlSyncedAt   (client listener flips PENDING→SYNCED)
  on failure → syncStatus:"FAILED", hlSyncError, THROW so Functions retries; reconcile sweep is the net
```

**The two backends (per `CLAUDE.md`):**
- **Gateway** (ours, Node/Fastify + Prisma, repo `aromex-gateway`): already brokers HL tokens to *users* via `POST /hl-token` (verifies a Firebase ID token). It holds each company's HL credential (`HL_USER_*`/`HL_PASS_*`) and caches minted tokens (`src/services/hlClient.ts` → `mintHlToken(company, uid)`). Admin routes are guarded by `X-Admin-Token` (`src/middleware/adminAuth.ts` → `requireAdminToken`). Provisioning lives in `scripts/register-company.ts` + `src/routes/provisioning.ts`.
- **Per-client Firebase** (Auth + Firestore + **Cloud Functions**): holds the operational entity docs. Cloud Functions run **in the client's own Firebase project**, so they can use the Firebase Admin SDK to read/write Firestore and reach the gateway.

**Why the gateway needs a new door:** the Cloud Function is a **robot** — it has **no Firebase ID token**, so it can't use `POST /hl-token` (which demands one). It needs a server-to-server way to get an HL token. We add an **admin-authed** internal route that reuses the same token cache.

**HL endpoints (verified live at `https://ledger.humblesolutions.in/docs`):**
- `POST /api/v1/customers` — **idempotent on `externalId`** → `{ success, data:{id,accountId,…}, idempotent, existingCustomerId }`.
- `POST /api/v1/customers/{id}/opening-balance` — `{ amount, direction: "RECEIVABLE"|"PAYABLE", date?, appId, sourceId, actorRef? }` (idempotent on `(appId, sourceId)`). **Map our `CREDIT` → HL `PAYABLE`.**
- `POST /api/v1/accounts` — create an account (**NOT idempotent** → get-or-create). `GET /api/v1/accounts` to look up first.

**Money & idempotency rules:** money is decimal **strings** in our code; convert to number only when building the HL request body. Every HL post uses `appId:"aromex"` + a stable `sourceId` = `"<collection>_<docId>[:<kind>]"` (e.g. `entity_<id>:opening`). Attribution: stamp the initiating staff uid into `actorRef` (read `createdBy` off the doc).

## 🔑 Access & prerequisites
> All via the manager / secure channel. Never commit secrets.
- **Deploy targets:** (1) the **gateway** server (where `aromex-gateway` runs — for the new route) and (2) the client Firebase project's **Cloud Functions** (Firebase CLI deploy). Confirm both with the manager at kickoff.
- **Gateway:** repo access, `ADMIN_API_TOKEN` value (for testing the internal route), and the test company's `hlCredentialPrefix`.
- **Firebase:** the **aromex-test** Firebase project (Functions + Firestore enabled) and deploy rights (Firebase CLI login / service account).
- **HL test company:** `aromex-test@yourco.com` (password via manager). Its `Opening Balance Equity` account already exists (id `b40137cd-8668-4aad-84b5-53fac3acaaab`).

## ✅ Scope / What to build
**A. Gateway — internal HL-token route** (`aromex-gateway`, TypeScript):
- [ ] `POST /internal/hl-token` guarded by `requireAdminToken` (`X-Admin-Token`); body `{ companyId }`; looks up the company, calls `mintHlToken(company, …)`, returns `{ hlToken, expiresIn }`. HL creds/logs never leak. Add a test (Fastify `inject`) mirroring `hlToken.ts`.

**B. Cloud Functions** (client Firebase project, **TypeScript**, built as reusable scaffolding):
- [ ] A generic **sync-worker** helper: *read pending doc → get HL token (gateway `/internal/hl-token`, admin token from Functions config) → do the HL post(s) → patch status → on error set FAILED + throw for retry.* Written so Purchase/Sales reuse it.
- [ ] `onEntityWrite` — Firestore trigger on `entities/{id}` create + update:
  - Create HL customer (idempotent `externalId=id`), store `hlCustomerId` + `hlAccountId`.
  - If `opening` present & `opening.posted != true` → post opening-balance (map `CREDIT→PAYABLE`, `sourceId="entity_<id>:opening"`, `actorRef` = doc `createdBy`), set `opening.posted=true`.
  - Patch `syncStatus:"SYNCED"`, `hlSyncedAt`. On profile-only updates (roles/notes — not in HL) skip the HL call.
  - Failure → `syncStatus:"FAILED"`, `hlSyncError`, throw (Functions retry).
- [ ] `reconcileEntities` — scheduled (~every 5 min): re-run the sync for docs `syncStatus in [PENDING, FAILED]` older than N min (idempotent → safe). This is the §6.3 reconciliation net. **Write it generically** (parameterized by collection) so later features reuse it.
- [ ] Functions config for the gateway base URL + `ADMIN_API_TOKEN` (via Functions secrets/config, never committed).

**C. Firestore rules** (`firebase/firestore.rules`) — add an `entities/{id}` block **above** the catch-all, using existing helpers `hasPermission('profiles', …)` + `belongsToThisCompany()`:
- [ ] `get,list` → `profiles` view; `create,update` → `profiles` manage; `delete` → false (soft-archive only).
- [ ] Guards: on create, `syncStatus == 'PENDING'` and no `hlCustomerId`/`hlAccountId` (CF owns those); `isWalkIn == false` (clients can't mint Walk-in); on update, `isWalkIn` immutable and clients can't change `hlCustomerId`/`hlAccountId`/`hlSyncedAt`. (CF writes via Admin SDK → bypasses these, as intended.)

**D. Provisioning** (extend `aromex-gateway` `scripts/register-company.ts` + runbook):
- [ ] **Get-or-create** per company (via HL `GET`/`POST /accounts`, get-or-create because create isn't idempotent): `Opening Balance Equity` (EQUITY), `Inventory` (ASSET), `Cost of Goods Sold` (EXPENSE) — Title Case, **no account codes** (matches the live chart convention).
- [ ] Create the reserved **Walk-in Customer** entity for the company (Firestore `entities/{…}` with `isWalkIn:true` + its HL customer), server-side, idempotently. It must be un-deletable/un-renamable (enforced by rules + shared use case in T1).

## 🎯 Acceptance Criteria
- [ ] `POST /internal/hl-token` returns a valid HL token for a known `companyId` **only** with a correct `X-Admin-Token` (401 otherwise); never exposes HL creds; test passes.
- [ ] Creating an `entities/{id}` doc with `syncStatus:"PENDING"` triggers `onEntityWrite`, which creates exactly **one** HL customer (verified: a re-trigger / retry does **not** create a duplicate — `idempotent:true` respected) and patches `hlCustomerId`, `hlAccountId`, `syncStatus:"SYNCED"`.
- [ ] An entity created **with** an opening balance posts the opening-balance transaction once (idempotent `sourceId`), maps `CREDIT→PAYABLE`, stamps `actorRef`, and sets `opening.posted=true`.
- [ ] Forcing an HL failure leaves the doc `FAILED` with `hlSyncError`; `reconcileEntities` later completes it to `SYNCED` with no duplication.
- [ ] Firestore rules: a `profiles:none`/`view` user cannot create/update an entity from a mobile client; nobody can hard-delete; a client cannot set `isWalkIn:true` or forge `hlCustomerId`. (Admin-SDK/CF writes still succeed.)
- [ ] Provisioning a fresh company get-or-creates the 3 accounts (no duplicates on re-run) and the Walk-in entity (single, idempotent).
- [ ] No secrets committed; gateway URL + admin token come from Functions config.

## 🚫 Out of scope
- Shared Kotlin models/interfaces/use cases + the shared Ktor read client — **T1**.
- Any client repo impls, ViewModels, DI, or UI — **T3/T4**.
- HL **reads** of balances from the client (that's the shared Ktor client in T1, consumed in T3/T4).
- Purchase/Sales posting endpoints usage — later milestones (this ticket only sets the reusable pattern).

## 🔗 Dependencies
- **T1** (defines the `entities/{id}` doc shape / `HlSyncStatus` / field names the CF reads & writes). T1 and T2 can proceed in parallel once the doc contract is agreed, but T2 must match T1's field names.

## 📚 References
- **Brief:** #25 · `docs/briefs/B25-profiles-entities.md`
- **PRD:** `docs/PRD.md` §6 (esp. §6.3 sync reliability), §7.2/§7.3 (permissions, rules), §8.1 (provisioning runbook)
- **HL API:** `https://ledger.humblesolutions.in/docs` — customers, customers/{id}/opening-balance, accounts
- **Gateway:** repo `aromex-gateway` — `src/services/hlClient.ts`, `src/middleware/adminAuth.ts`, `src/routes/hlToken.ts`, `scripts/register-company.ts`
- **Existing rules:** `firebase/firestore.rules` (has `hasPermission`/`belongsToThisCompany` helpers ready)
- **Design decisions:** memory `b25-profiles-entities-design`, `hl-compatibility-audit`, `hl-provisioning-opening-balance-equity`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
