# Handoff — Ticket #4

**Ticket:** Humble-Coders/Aromex-KMP#4 — [M1] Gateway: deploy aromex-gateway to the HL server

**Status:** **Phase A deployed.** The gateway is live and verified end-to-end at
`http://68.183.86.89/gateway/`. Phase B (HTTPS via Let's Encrypt) is documented but pending a
domain.

**Where the code lives:**
- Aromex-KMP, branch `ticket-4-handoff`, commit `eba7cc8` (partial-progress placeholder) + the
  commit that adds this completion document.
- `Humble-Coders/aromex-gateway`, branch `ticket-4-deploy-docs`, commit `ba38590` — single file
  diff (`docs/DEPLOY.md`: +234 / −82).

There is no application code change in either repo for this ticket. All the work was
operational, on the droplet itself.

## Summary

Stood up the gateway on the existing Humble Ledger droplet (`68.183.86.89`) as its own pm2
process on port `8090`, with its own Postgres database (`aromex_gateway`) in HL's existing
cluster, served publicly under a new `/gateway/` location block in the existing nginx site.
The whole stack — `/resolve-company`, `/hl-token`, admin endpoints — was verified end-to-end
from a laptop against the public URL, including the live HL exchange: the JWT the gateway
returned was accepted by HL's real `/api/v1/accounts` endpoint. `docs/DEPLOY.md` was rewritten
to match the runbook we actually executed (pm2 + ecosystem.config.cjs via a shell wrapper, not
the original systemd plan), with a new "Phase B" section that walks through Let's Encrypt for
when a domain is registered.

## Files changed

**Aromex-KMP (this PR)**
- `handoffs/ticket-4.md` — this file. Replaces the earlier partial-progress version on the
  same branch with a completion handoff.

**`Humble-Coders/aromex-gateway` (PR #3, commit `ba38590`)**
- `docs/DEPLOY.md` — rewritten end to end:
  - systemd unit replaced by a pm2 ecosystem file + shell-wrapper invocation (the wrapper is
    necessary because pm2's direct ESM `dist/server.js` invocation silently restart-loops on
    this Node 20 / pm2 6 combo; details in the new Troubleshooting section).
  - Install path moved from `/opt/aromex-gateway` to `~deploy/aromex-gateway` (no root-owned
    directories touched on the droplet; the `deploy` user owns everything).
  - `HL_BASE_URL` documented as `http://127.0.0.1/api-server` (HL on the same box, loopback
    only).
  - The exact nginx `location /gateway/` block we inserted into the existing `humble-ledger`
    site, with `rewrite ^/gateway/(.*) /$1 break;` so the gateway sees `/health` etc., not
    `/gateway/health`.
  - DB password URL-encoding gotcha (`@` → `%40`) called out.
  - Step 5: `scp` recipe for uploading per-company Firebase service-account keys to
    `~deploy/aromex-gateway/secrets/`.
  - Step 7: `pm2 startup systemd -u deploy --hp /home/deploy` so the gateway restarts on
    droplet reboot.
  - New "Phase B — Adding HTTPS via Let's Encrypt" section documenting the exact `certbot
    --nginx -d <domain>` flow.
  - New "Troubleshooting" section covering the pm2/ESM silent restart, P1010 from a missing
    DB user / unencoded password, `/hl-token` 502 vs 500 differentiation, and the 404 case
    when nginx wasn't reloaded.

No application code in either repo changed. No tests, no Prisma schema, no `src/*` edits.

## Operational changes made on the droplet (not represented in any git diff)

These changes are what actually make the deploy real. They are intentionally not committed
anywhere; the runbook in `docs/DEPLOY.md` is the source of truth for how to reproduce them.

| Change | Detail |
|---|---|
| Postgres user + DB | `aromex_gateway` role; `aromex_gateway` database; password rotated to the value in the deployed `.env`. |
| Schema | `prisma migrate deploy` applied both existing migrations (`init`, `rename_hl_credential_field`). |
| Code on disk | `~deploy/aromex-gateway/app` (git clone via read-only deploy SSH key tracked at `~deploy/.ssh/aromex_gateway_deploy`). |
| `.env` | `~deploy/aromex-gateway/app/.env`, chmod 600, holds `DATABASE_URL`, `ADMIN_API_TOKEN`, `HL_BASE_URL`, `HL_USER_AROMEXTEST` + `HL_PASS_AROMEXTEST`. Never committed. |
| Firebase SA key | `~deploy/aromex-gateway/secrets/aromex-test-sa.json`, chmod 600. |
| pm2 process | `aromex-gateway` (id 0, fork mode, deploy user), started via `ecosystem.config.cjs` + `start.sh` wrapper. `pm2 save` persisted to `~deploy/.pm2/dump.pm2`. |
| pm2 startup unit | `pm2-deploy.service` enabled in systemd (boots the pm2 daemon, which resurrects `aromex-gateway` on droplet reboot). |
| nginx | `/etc/nginx/sites-available/humble-ledger` backed up to `humble-ledger.bak.20260621-140223`; a single `location /gateway/` block inserted before the catch-all `location /`. No edits to any other HL location. `nginx -t` passed; reloaded. |
| Test company | `aromextest` registered via `scripts/register-company.ts` against the production gateway; `ansh.bajaj2611@gmail.com` indexed; `hlCredentialPrefix=AROMEXTEST`. |

## How to test

Anyone with internet access can run these — no SSH required:

```bash
# 1. Liveness
curl http://68.183.86.89/gateway/health
# Expect: {"status":"ok"}

# 2. Unknown-email enumeration check
curl -X POST http://68.183.86.89/gateway/resolve-company \
  -H 'Content-Type: application/json' \
  -d '{"email":"nobody@nowhere.test"}'
# Expect: {"companies":[]}

# 3. Negative auth checks
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://68.183.86.89/gateway/hl-token
# Expect: 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://68.183.86.89/gateway/admin/companies
# Expect: 401

# 4. Full Firebase → HL loop (requires the Aromex test SA key in ./secrets/)
cd Humble-Coders/aromex-gateway
GATEWAY_BASE_URL=http://68.183.86.89/gateway \
SA_KEY_PATH=$(pwd)/secrets/aromex-test-sa.json \
FB_API_KEY=<redacted> \
TEST_EMAIL=ansh.bajaj2611@gmail.com \
TEST_UID=gateway-prod-e2e-user \
HL_BASE_URL=http://68.183.86.89/api-server \
npx tsx scripts/live-e2e.ts
# Expect: all 5 steps green; step [4] returns a real HL JWT; step [5] returns 200 + accounts.
```

Operator-level verification (requires SSH as `deploy`):

```bash
ssh deploy@68.183.86.89 'pm2 list; pm2 logs aromex-gateway --lines 5 --nostream; \
  curl -s http://127.0.0.1:8090/health; \
  sudo ss -tlnp | grep :8090'
```

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Runs as its own service on the HL server (separate process/port), reachable at the agreed path. | ✅ Met | pm2 process `aromex-gateway` on port 8090, separate from HL's pm2 processes on 3000/3001. Reachable publicly at `http://68.183.86.89/gateway/`. |
| Directory DB persists; `/health` returns `ok`. | ✅ Met | `aromex_gateway` Postgres DB exists in HL's cluster with `Company` + `EmailIndex` + `_prisma_migrations` tables (verified post-`prisma migrate deploy`). `curl http://68.183.86.89/gateway/health` returned `{"status":"ok"}` from this laptop just before this handoff was written. |
| Secrets via env; none committed. | ✅ Met | All secrets live in `~deploy/aromex-gateway/app/.env` (chmod 600) and per-company `~deploy/aromex-gateway/secrets/*-sa.json` (chmod 600). Repo `.gitignore` excludes both. The single commit in this ticket (`ba38590`) is `docs/DEPLOY.md` only — no secret in the diff. |
| Deploy steps documented and repeatable. | ✅ Met | `docs/DEPLOY.md` (in PR #3 on the gateway repo) was rewritten to reflect what we actually did. Follows steps 1–10 plus Upgrade, Rotation, Phase B, and Troubleshooting. |

## Deviations / decisions

- **systemd → pm2 (+ shell wrapper).** The ticket said *"Run under pm2 on its own port"*, but
  the existing `docs/DEPLOY.md` had been written assuming a custom systemd unit. We followed
  the ticket and used pm2. We also needed a small `start.sh` wrapper because
  `pm2 start dist/server.js` on this Node 20.20.2 / pm2 6.0.14 combo silently restart-loops
  on the project's ESM build (no logs are written). Running `node dist/server.js` directly
  works, so the wrapper just `cd`s and `exec node`s. Documented in the runbook.
- **Install path `/opt/aromex-gateway` → `~deploy/aromex-gateway`.** `/opt` on this droplet
  already holds `digitalocean/` and `humblehelp/` from previous tooling. Using the `deploy`
  user's home avoids any root-owned write and keeps the deploy entirely user-scoped.
- **No new nginx site, just a new location.** We have no domain yet, and nginx only allows
  one `default_server` on port 80. So instead of a new `sites-available/aromex-gateway`
  server block (which would require a `server_name`), we inserted a single
  `location /gateway/` into the existing `humble-ledger` site, after backing it up. No
  existing HL location was modified.
- **`HL_BASE_URL=http://127.0.0.1/api-server` on the server, not the public IP.** HL is on
  the same droplet; gateway → HL traffic stays on loopback, never going through nginx.
- **`HL_USER_AROMEXTEST` / `HL_PASS_AROMEXTEST` accepted.** The credentials provided
  (`aromex-test@yourco.com` / `<redacted>`) work against the real HL — the JWT HL
  returned carries `companyId = c6dd3a85-…`, which is **different** from the documented
  `dreamland@gmail.com` company. So that account is a real Aromex HL test company, not a
  placeholder; the earlier suspicion was wrong.
- **The earlier partial-progress handoff stays in history.** The first commit on this
  branch (`eba7cc8`) was an honest "deploy blocked" report. This commit overwrites the
  file with a completion handoff rather than amending history, so the audit trail of "we
  hit a block, unblocked, finished" is preserved in the branch's commit log.

## Open questions / follow-ups

- **Phase B — HTTPS via Let's Encrypt.** Documented in the updated `DEPLOY.md` but not yet
  executed. Needs a registered domain (or subdomain) with an A record pointing at
  `68.183.86.89`. Resume by running `sudo certbot --nginx -d <domain>` per the Phase B
  section. Until this lands, the gateway is HTTP-only — acceptable for closed integration
  testing, **not acceptable for shipping the Aromex app to real users** because Firebase ID
  tokens would transit unencrypted.
- **Backups for `aromex_gateway` Postgres DB.** Currently no scheduled `pg_dump`. The
  registry is small and recoverable (re-running the onboarding runbook would rebuild it),
  but a nightly dump to `/opt/backups/` would be cheap insurance. Future ticket.
- **No alerting on pm2 restart loops.** If the gateway starts crash-looping in prod, nothing
  pages anyone. `pm2 plus` (free tier) or a tiny health-check cron that posts to a webhook
  would close this. Future ticket.
- **One Aromex test company is registered (`aromextest`) using the user's verified
  HL credentials.** Real client onboarding is M1-08's job; this row is a development
  fixture and can be left in place or removed at the team's preference.
- **The `start.sh` + `ecosystem.config.cjs` files live on the droplet only**, not in the
  repo. If we ever want to rebuild the deploy from a clean clone, the runbook in
  `docs/DEPLOY.md` walks through generating them. Worth committing them to the repo in a
  follow-up so a fresh clone has everything needed.
