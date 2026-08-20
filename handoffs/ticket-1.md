# Handoff — Ticket #1

**Ticket:** #1 — [M1] Central Directory & Humble Ledger Gateway service

**Where the code lives:** A separate repo, **`Humble-Coders/aromex-gateway`**, locally at
`~/AndroidStudioProjects/aromex-gateway`. Initial commit `f837d7f`.

## Summary

Built the vendor-owned gateway service that the Aromex app needs at login: (1) `POST /resolve-company`
maps an email to the right client company's **public Firebase config** so the app knows which Firebase
project to sign into, and (2) `POST /hl-token` exchanges a Firebase ID token for a short-lived,
company-scoped Humble Ledger access token so the app can call HL without ever holding HL credentials.
Stack is Node + TypeScript + Fastify + Prisma/Postgres. The registry uses its own Postgres database
(separate from HL's). The actual HL-mint call is currently stubbed — the HL endpoint and request shape
are pending HL's `MOBILE_ADMIN_API.md` spec; the surrounding flow is wired and tested. The whole
service was verified end-to-end against the existing Aromex Firebase project (`aromex-june-2026`) on
localhost, including a real Firebase ID token round-trip.

## Files changed (initial commit, 31 files, +6203 lines)

All paths are inside the **`aromex-gateway`** repo unless noted.

**Server (Fastify + routes)**
- `src/server.ts` — Fastify bootstrap, per-route rate-limit on `/resolve-company`, graceful shutdown.
- `src/config.ts` — env loading + zod validation; fails fast on bad config.
- `src/routes/health.ts` — `GET /health` liveness check.
- `src/routes/resolveCompany.ts` — `POST /resolve-company`; returns same shape for unknown/malformed
  emails to prevent enumeration; **only** `companyId` + `firebaseConfig`, no `displayName`/no secret refs.
- `src/routes/hlToken.ts` — `POST /hl-token`; decodes JWT (unverified) to find project → looks up company →
  `verifyIdToken` via that company's firebase-admin App → checks `users/{uid}.isActive` → mints HL token.
- `src/routes/provisioning.ts` — internal `POST /admin/companies` + `POST /admin/email-index`, guarded
  by `requireAdminToken` preHandler.
- `src/middleware/adminAuth.ts` — timing-safe `X-Admin-Token` check.

**Services (business logic)**
- `src/services/firebaseVerifier.ts` — lazy per-company `initializeApp` cache; `verifyIdToken`,
  `isUserActive` (Firestore read), `unsafeDecodeProjectId` (JWT header peek to route to right project).
- `src/services/hlClient.ts` — `mintHlToken` reads `process.env[company.hlCredentialEnvVar]` and would
  call HL; **currently returns a stub token** with a clearly-marked TODO and the example real-call code
  in a comment block.
- `src/services/emailHash.ts` — `sha256(lower(trim(email)))` so emails are never stored in plaintext.

**Data layer**
- `prisma/schema.prisma` — `Company` (firebaseConfig as JSON, secret *references*) and `EmailIndex`
  (composite PK `(emailHash, companyId)` so one email can map to multiple companies).
- `prisma/migrations/20260620140639_init/migration.sql` — initial migration.
- `src/db/client.ts` — PrismaClient singleton.
- `src/db/companiesRepo.ts` — typed `findCompaniesByEmail`, `findCompanyByProjectId`, `upsertCompany`,
  `addEmailToCompany`.

**Tests (vitest + Fastify inject)**
- `tests/resolveCompany.test.ts` — 5 tests: found / not-found / multi-company / no-leak / malformed.
- `tests/hlToken.test.ts` — 5 tests: missing header / unknown project / verify-fail / inactive user / valid.
- `tests/rateLimit.test.ts` — 11th call within 60s → 429.
- `tests/provisioning.test.ts` — 3 tests: missing token / wrong token / valid create.
- `tests/setup.ts` — loads `.env.test`, runs `prisma migrate deploy`, truncates tables between tests.
- `tests/helpers/factory.ts` — `makeCompany`, `indexEmail` test fixtures.
- `vitest.config.ts` — vitest config (no thread sharing).

**Scripts**
- `scripts/register-company.ts` — operator CLI: takes flags + a firebase-config JSON file, hits the
  admin endpoints with the admin token, registers a company + email mapping.
- `scripts/live-e2e.ts` — live verification: resolve → mint custom token → exchange for real ID token →
  POST /hl-token.

**Config & docs**
- `package.json`, `package-lock.json`, `tsconfig.json` (ESM, strict).
- `.gitignore` — excludes `.env*`, `secrets/`, `node_modules/`, `.idea/`, etc.
- `.env.example` — documents every required env var.
- `README.md` — quickstart, layout, security rules.
- `docs/API.md` — request/response shapes for every endpoint, including error codes.
- `docs/DEPLOY.md` — systemd unit, nginx location, Postgres setup, secret rotation procedure.

## How to test (reviewer steps)

```bash
# 1. Prereqs
brew install postgresql@16
brew services start postgresql@16
createdb aromex_gateway
createdb aromex_gateway_test

# 2. Repo
cd ~/AndroidStudioProjects/aromex-gateway
npm install
cp .env.example .env
# edit .env: set DATABASE_URL=postgresql://<your-user>@localhost:5432/aromex_gateway
# set ADMIN_API_TOKEN to anything >=16 chars
npx prisma migrate dev

# 3. Unit tests (expect 14 passing across 4 files)
npm test

# 4. Manual smoke
npm run dev    # listens on :8080
# in another shell:
curl http://localhost:8080/health
# → {"status":"ok"}

# 5. Register the local Aromex Firebase as test company
# (assumes the SA key file is at ./secrets/aromex-test-sa.json)
GATEWAY_BASE_URL=http://localhost:8080 \
ADMIN_API_TOKEN=<your-admin-token> \
npx tsx scripts/register-company.ts \
  --id aromextest \
  --displayName "Aromex Test Workspace" \
  --firebaseConfig ./secrets/aromex-test-firebase-config.json \
  --serviceAccountKey "$(pwd)/secrets/aromex-test-sa.json" \
  --hlCompanyId co_aromex_test \
  --hlCredentialEnvVar HL_CRED_AROMEXTEST \
  --currency CAD \
  --email ansh.bajaj2611@gmail.com

# 6. Live end-to-end (requires Firebase Auth + Firestore enabled on the test project)
SKIP_FIRESTORE=0 \
GATEWAY_BASE_URL=http://localhost:8080 \
SA_KEY_PATH="$(pwd)/secrets/aromex-test-sa.json" \
FB_API_KEY="AIzaSyBmXf6Bh7x0uPZCEDJCIoy7Fy1j0d9kOv8" \
TEST_EMAIL="ansh.bajaj2611@gmail.com" \
TEST_UID="gateway-e2e-user" \
npx tsx scripts/live-e2e.ts
# Expected: all 4 steps green, /hl-token returns 200 with a stub HL token.
```

Negative checks worth running:

```bash
# Unknown email → empty array, not 404
curl -s -X POST http://localhost:8080/resolve-company \
  -H "Content-Type: application/json" -d '{"email":"nobody@nowhere.test"}'
# → {"companies":[]}

# /hl-token without Authorization → 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/hl-token
# → 401

# Admin without X-Admin-Token → 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/admin/companies \
  -H "Content-Type: application/json" -d '{}'
# → 401
```

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Known email → Firebase config, no secrets, no company name | ✅ Met | `src/routes/resolveCompany.ts` returns only `companyId` + `firebaseConfig`; covered by `tests/resolveCompany.test.ts` "never leaks displayName, secret refs, or HL fields". |
| Unknown email → generic not-found (no enumeration) | ✅ Met | Same `{companies:[]}` shape for unknown / malformed; covered by two tests in `resolveCompany.test.ts`. |
| Multi-company email → chooser data | ✅ Met | `EmailIndex` composite PK supports multi-row; covered by "returns multiple entries…" test. |
| `/resolve-company` rate-limited | ✅ Met | `@fastify/rate-limit` per-route, keyed by `req.ip`; covered by `tests/rateLimit.test.ts`. |
| `/hl-token` 401 for invalid/expired; valid → short-lived token | ✅ Met | All 5 cases in `tests/hlToken.test.ts` pass; verified live against real Aromex Firebase. |
| No endpoint returns HL cred or SA key | ✅ Met | DB stores only references (`hlCredentialEnvVar`, `serviceAccountKeyPath`). Asserted in tests and confirmed by inspecting raw response bodies. |
| Own service on HL server, own DB | ⚠️ Partially | DB is separate (`aromex_gateway`); deploy to HL droplet **not yet executed** — documented in `docs/DEPLOY.md`. |
| Deploy steps documented, no secret committed | ✅ Met | `docs/DEPLOY.md` covers systemd + nginx + rotation. `.gitignore` excludes `.env*` and `secrets/`. |
| Tests cover resolve / token / rate-limit | ✅ Met | 14 passing tests across 4 files. |

## Deviations / decisions

- **`prisma/schema.prisma` `EmailIndex.@@id([emailHash, companyId])`** — used a composite PK instead of
  `emailHash` alone so one email can legitimately map to multiple companies (the PRD's "shared
  accountant" case) without conflicts. Matches the multi-company acceptance criterion.
- **`displayName` is stored on `Company`** for operator use, but is **never** returned by
  `/resolve-company` — even on the multi-company chooser path. The PRD asked for
  no-customer-name leakage; the route returns only `companyId` + `firebaseConfig`.
- **`/resolve-company` returns `200` with empty array for malformed emails**, not `400`. This is
  deliberate: a `400` would let an attacker tell "email shape is wrong" apart from "email is unknown."
  Same response shape for both → no enumeration signal.
- **HL token minting is stubbed** in `src/services/hlClient.ts` because HL's `MOBILE_ADMIN_API.md`
  spec isn't yet available. The function reads the per-company credential, throws if missing, and
  returns a placeholder string. The real call shape is in a commented-out block. Swap the body when
  the spec lands.
- **The desktop client is not relevant to this ticket**, but is worth noting for downstream tickets:
  the desktop app will need to call the same gateway endpoints; nothing in this gateway needs to
  change to support it.

## Open questions / follow-ups

- **HL token mint** — pending HL `MOBILE_ADMIN_API.md`. Swap `src/services/hlClient.ts::mintHlToken`
  to the real call once available. No callers change.
- **Production deploy** — `docs/DEPLOY.md` is written; the actual deploy to `68.183.86.89` is the
  next operational step. Needs PM to confirm the public path behind nginx.
- **GitHub repo** — `Humble-Coders/aromex-gateway` is not yet created on GitHub; the local repo has
  one initial commit (`f837d7f`) waiting to be pushed.
- **Custom claims** (PRD §7.2 mentions `{ admin, hlCompanyId }` claims as a cheap rule check) — not
  set here; will likely be set by the user-management Cloud Function (M1 onwards), not the gateway.
- **Token caching on the app side** — the app should cache the short-lived HL token for its ~1h life
  and only call `/hl-token` again on expiry. That's the app's concern (M1-05), not the gateway's.
- **Admin audit log** — `/admin/*` writes aren't logged to a table yet. For now, Fastify's request
  log is the only record. Worth adding before the gateway has many operators.
