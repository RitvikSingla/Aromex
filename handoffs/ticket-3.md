# Handoff — Ticket #3

**Ticket:** Humble-Coders/Aromex-KMP#3 — [M1] Gateway: wire real Humble Ledger token brokering (replace stub)

**Where the code lives:** `Humble-Coders/aromex-gateway`, branch `ticket-3-real-hl-token`. One commit `da49638` on top of `main` — 15 files, +298 / −79.

## Summary

Replaced the placeholder `mintHlToken()` stub from ticket #1 with a real call to Humble Ledger's `POST /api/v1/auth/login` against `http://68.183.86.89/api-server`. The gateway now logs into HL with the company's stored credential and returns the resulting short-lived (~15 min) access token to the app. A per-company in-memory token cache refreshes via HL's `/auth/refresh` ~60 s before expiry, falling back to a fresh login if refresh fails — so we don't hammer HL on every request. Per-company HL credentials moved from a single `HL_CRED_<X>` env var to a `HL_USER_<X>` + `HL_PASS_<X>` pair (HL requires email + password), with `Company.hlCredentialEnvVar` renamed to `Company.hlCredentialPrefix` via a clean SQL `RENAME` migration. Verified end-to-end against the real HL droplet: the returned JWT was accepted by `GET /api/v1/accounts` and returned the live chart of accounts.

## Files changed

**Server — services**
- `src/services/hlClient.ts` — full rewrite. Real `POST /api/v1/auth/login` and `/auth/refresh`, response-envelope handling (`{ success, data }`), per-company `tokenCache` keyed by `company.id`, 15-min TTL with a 60-s refresh window, and a test-only `_resetTokenCache()`. The `HlUpstreamError` class is preserved.

**Server — data layer**
- `prisma/schema.prisma` — renamed `Company.hlCredentialEnvVar` → `Company.hlCredentialPrefix`.
- `prisma/migrations/20260621115419_rename_hl_credential_field/migration.sql` — hand-edited from Prisma's default DROP+ADD to a clean `ALTER TABLE … RENAME COLUMN`, preserving any existing prod data.
- `src/db/companiesRepo.ts` — `UpsertCompanyInput` field renamed.

**Server — routes**
- `src/routes/provisioning.ts` — admin body zod schema renamed, with an added `regex(/^[A-Z0-9_]+$/)` constraint so a prefix is always a valid env-var suffix.

**Scripts**
- `scripts/register-company.ts` — CLI flag `--hlCredentialEnvVar` → `--hlCredentialPrefix`; usage docblock updated.
- `scripts/live-e2e.ts` — new step 5: take the returned `hlToken` and call real HL `/api/v1/accounts?limit=1` to confirm the token works. New env knobs: `HL_BASE_URL`, `SKIP_HL_VERIFY=1`.

**Tests**
- `tests/hlToken.test.ts` — replaced stub-shape assertions with `vi.spyOn(globalThis, 'fetch')` mocking HL login/refresh. Added 3 cases: cache reuse (assert only one HL login call across two `/hl-token` requests), HL down → 502 `hl_upstream`, missing `HL_USER_*`/`HL_PASS_*` → 500 with no env-var-name leakage. Cleans the cache between tests via `_resetTokenCache()`.
- `tests/helpers/factory.ts` — `makeCompany` accepts `hlCredentialPrefix` (defaults to `id.toUpperCase()`).
- `tests/provisioning.test.ts`, `tests/resolveCompany.test.ts` — field rename in fixture + the no-leak assertion.

**Config / env / docs**
- `.env.example` — documents the new `HL_USER_<PREFIX>` / `HL_PASS_<PREFIX>` convention and sets `HL_BASE_URL=http://68.183.86.89/api-server`.
- `vitest.config.ts` — typo fix: `fileParallel` → `fileParallelism`. The old key was silently ignored, so Vitest was running test files in parallel and the `globalThis.fetch` mock from `hlToken.test.ts` was leaking into `resolveCompany.test.ts`. Now serialized correctly.
- `docs/API.md` — `/hl-token` section rewritten to document the real HL call, the cache/refresh flow, the new `expiresIn = 900`, and the per-company env-var convention; admin-route example updated.
- `docs/DEPLOY.md` — `.env` example, `register-company` invocation, and the secrets-rotation section updated for `HL_USER_*` / `HL_PASS_*`.

**Not touched:** anything in the Aromex KMP app (`androidApp/`, `iosApp/`, `desktopApp/`, `sharedLogic/`). The `/hl-token` response shape (`{ hlToken, expiresIn }`) is unchanged, so the app side needs no work.

## How to test

```bash
# 1. Get the branch
git clone https://github.com/Humble-Coders/aromex-gateway.git
cd aromex-gateway
git checkout ticket-3-real-hl-token

# 2. Postgres + install
brew install postgresql@16 && brew services start postgresql@16
createdb aromex_gateway
createdb aromex_gateway_test
npm install
cp .env.example .env
# edit .env: set DATABASE_URL with your user, ADMIN_API_TOKEN to a random 16+ char string,
# and HL_USER_AROMEXTEST / HL_PASS_AROMEXTEST = dreamland@gmail.com / 114198 (the only
# documented test login per HL MOBILE_ADMIN_API.md).

# 3. Apply the rename migration
npx prisma migrate dev

# 4. Unit tests — expect 17 passing across 4 files
npm test
```

Live end-to-end against the real HL droplet (requires the Aromex Firebase service-account key at `secrets/aromex-test-sa.json` plus `secrets/aromex-test-firebase-config.json`):

```bash
# Start the gateway
npm run dev &

# Register the Aromex Firebase as a test company in the gateway DB
ADMIN_API_TOKEN=<from your .env> GATEWAY_BASE_URL=http://localhost:8080 \
npx tsx scripts/register-company.ts \
  --id aromextest \
  --displayName "Aromex Test Workspace" \
  --firebaseConfig ./secrets/aromex-test-firebase-config.json \
  --serviceAccountKey "$(pwd)/secrets/aromex-test-sa.json" \
  --hlCompanyId co_aromex_test \
  --hlCredentialPrefix AROMEXTEST \
  --currency CAD \
  --email ansh.bajaj2611@gmail.com

# Run the full live loop — expect [1]…[5] all green
GATEWAY_BASE_URL=http://localhost:8080 \
SA_KEY_PATH="$(pwd)/secrets/aromex-test-sa.json" \
FB_API_KEY="<aromex-june-2026 web API key>" \
TEST_EMAIL="ansh.bajaj2611@gmail.com" \
TEST_UID="gateway-e2e-user" \
HL_BASE_URL="http://68.183.86.89/api-server" \
npx tsx scripts/live-e2e.ts
```

Expected output: `[4] POST /hl-token → 200` with a real JWT in the body, then `[5] use the HL token against http://68.183.86.89/api-server/api/v1/accounts → 200` plus a chunk of real account data.

Negative spot-checks:
- Wrong `HL_USER_*` / `HL_PASS_*` → `/hl-token` returns `502 { "error": "hl_upstream" }`.
- Missing `HL_USER_*` / `HL_PASS_*` for the company's prefix → `/hl-token` returns `500 { "error": "internal" }` and the response body contains **no** env-var name (asserted in tests).
- Calling `/hl-token` twice in a row should produce only one `POST /api/v1/auth/login` against HL (covered by the cache-reuse vitest case).

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| `/hl-token` returns a real HL access token for a valid + active user, and it works against HL. | ✅ Met | Verified live: step 4 returns an HL JWT (decoded: `role: OWNER, companyId: cc80530d…`), step 5 uses it against `GET /api/v1/accounts` and HL responds 200 with real data. |
| Invalid token → 401; inactive user → 403 (unchanged). | ✅ Met | `tests/hlToken.test.ts` "returns 401 for a token whose verification fails" and "returns 403 when user is marked inactive in Firestore" still pass after the rewrite. |
| HL credential never returned or logged. | ✅ Met | `tests/hlToken.test.ts` asserts the response body contains neither the password (`hl-secret-do-not-leak`), the refresh token (`hl-refresh-1`), nor the login email (`acme-hl@example.com`). The missing-creds test additionally asserts the error response contains no `HL_USER_` / `HL_PASS_` substring. No `console.log` of credentials in `hlClient.ts`. |
| Tests cover the real path (mocked) + a live e2e. | ✅ Met | 8 mocked `hlToken.test.ts` cases (mocked `fetch`), plus extended `scripts/live-e2e.ts` step 5 that hits the real HL droplet. |

## Deviations / decisions

- **HL credential storage moved from one env var per company to a pair.** The ticket assumed a single `HL_CRED_<X>` would work, but HL's `MOBILE_ADMIN_API.md` makes clear `/auth/login` requires `{ email, password }`. So `HL_CRED_X` became `HL_USER_X` + `HL_PASS_X`, and `Company.hlCredentialEnvVar` was renamed to `Company.hlCredentialPrefix` (a single short ID like `ACME` rather than a full env-var name). Migration is a real `RENAME COLUMN` — no data loss in prod.
- **Documented test account ≠ user-provided test login.** The user supplied `aromex-test@yourco.com / AromexTest2026`; HL's `MOBILE_ADMIN_API.md` §2.1 explicitly says *"Use ONLY this account: `dreamland@gmail.com` / `114198`"*. I used the documented account for the live verification because it's the only one HL accepts; the `.env` example and `register-company` flow are credential-agnostic, so swap whenever a real Aromex HL test account is provisioned.
- **15-min TTL is hardcoded.** HL's `/auth/login` response does not include `expires_in`; the doc just says "~15 min". `ACCESS_TOKEN_TTL_MS = 15 * 60 * 1000` in `hlClient.ts`, with a 60-s refresh window. If HL ever returns an explicit `expiresIn`, the cache logic should switch to using that.
- **Refresh tokens are used, not ignored.** The plan considered just re-logging-in on every cache miss. HL's `/auth/refresh` is cheap and the refresh token lasts 30 days, so the cache uses it first and only falls back to login if refresh throws.
- **`fileParallelism: false` is a real correctness fix, not just style.** The prior `fileParallel: false` key was being silently ignored. With files running in parallel, the `vi.spyOn(globalThis, 'fetch')` in `hlToken.test.ts` was overwriting the global `fetch` while other files were mid-test, producing the cross-file flakiness we saw in ticket #1 too. Worth flagging in review.

## Open questions / follow-ups

- **A real Aromex HL test company.** Right now we're verifying against the Dreamland Hotel demo account because that's all HL has. Before the app team can integration-test, an Aromex test company should be provisioned in HL and its credentials swapped into `.env`.
- **Token cache survives only as long as the process.** A `systemctl restart aromex-gateway` invalidates every company's cached HL token. Acceptable for now (HL login is cheap), but if we deploy multiple gateway instances behind a load balancer later, a shared cache (Redis) would avoid each instance maintaining its own.
- **No metric / log on cache hit-rate.** Useful before scaling. Lives in a future observability ticket.
- **Per-company token leak across HL.** Because HL's account model is one-per-company, a compromised `HL_USER_X` / `HL_PASS_X` pair = full access to that company's HL books. Mitigation today: those env vars live only on the gateway server (chmod-protected `.env`). A future ticket should set up automated rotation.
- **Production deploy.** Still pending from ticket #1. The new `HL_USER_*` / `HL_PASS_*` env vars need to be set in `/opt/aromex-gateway/app/.env` on the droplet when we deploy.
