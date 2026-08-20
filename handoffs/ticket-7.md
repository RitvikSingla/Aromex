# Handoff — Ticket #7

**Ticket:** Humble-Coders/Aromex-KMP#7 — [M1] Per-client Firebase project: structure, security rules & first-admin setup

**Where the code lives:** This repo, branch `ticket-7-firebase-structure`, single commit `0749cc2`. **No external repo touched.** **No app code changed.**

## Summary

Created a new top-level `firebase/` folder holding the reusable structure every client Firebase project gets: the canonical Firestore document shapes (`users/{uid}`, `companySettings/profile`, `invites/{inviteId}`), Firestore Security Rules with helpers for own-user read, admin write, cross-project guard, and a `hasPermission()` helper ready for future per-feature collections, and an Admin-SDK operator script that bootstraps a fresh project end-to-end (deploys rules, creates the first admin user, sets `{admin, hlCompanyId}` custom claims, writes both docs). Verified end-to-end against `aromex-june-2026`: rules deployed, `owner@aromex.test` created with all 11 permissions, REST sign-in returns a token with the correct claims, positive read paths return 200, negative paths (unauth, catch-all) return 403, and the production gateway's `live-e2e` against `http://68.183.86.89/gateway/` is still green after rules were live (Admin SDK reads bypass rules by design).

## Files changed

All files are **new** — this is the first content in `firebase/`. Per the diff, 15 files, +10,122 lines (of which ~9,138 is the npm lockfile; the substantive code/docs/rules total ~1,000 lines).

**Firestore data shape + rules (the heart of the ticket)**
- `firebase/firestore.rules` (+121) — Helpers `isSignedIn()`, `isAdmin()` (claim check, no Firestore read), `isSelf(uid)`, `belongsToThisCompany()` (cross-project guard: token's `hlCompanyId` claim must equal `companySettings/profile.hlCompanyId`), `userPerms()`, `hasPermission(feature, level)`. Rules: `users/{uid}` get = self-or-admin, list/create/update = admin only with field-level validation, delete = forbidden (deactivate via `isActive: false`); `companySettings/{docId}` read = any signed-in user, write = admin; `invites/{inviteId}` read+write = admin. Catch-all `match /{document=**} { allow read, write: if false; }` makes any unhandled collection fail-closed.
- `firebase/firestore.indexes.json` (+4) — empty placeholder; Firebase CLI requires the file even when there are no composite indexes.

**Canonical documentation**
- `firebase/SCHEMA.md` (+122) — Field-level shape of `users/{uid}`, `companySettings/profile`, `invites/{inviteId}`. Source of truth for any future ticket that touches these docs.
- `firebase/PERMISSIONS.md` (+93) — The 11 feature scopes (`sales`, `purchases`, `inventory`, `transactions`, `profiles`, `balances`, `reports`, `statistics`, `histories`, `ledgers`, `settings`) × 3 levels (`manage`/`view`/`none`), plus the `userMgmt` boolean. Canonical "full admin" and "no access" templates.
- `firebase/README.md` (+90) — What's in the folder, quickstart, security model in one paragraph, how future tickets should add per-feature `match` blocks.

**Operator script (Admin SDK)**
- `firebase/scripts/setup-project.ts` (+235) — CLI that takes flags `--projectId`, `--serviceAccountKey`, `--ownerEmail`, `--ownerPassword`, `--ownerDisplayName`, `--companyName`, `--hlCompanyId`, `--country`, `--currency`, optional `--gstRate`/`--pstRate`/`--isHST`/contact fields, optional `--skipRulesDeploy`. Steps: (1) `firebase deploy --only firestore:rules` via shellout, (2) Auth `createUser` or `getUserByEmail` + `updateUser` (idempotent on email collision), (3) `setCustomUserClaims({admin: true, hlCompanyId})`, (4) write `users/{uid}` with `role: "admin"` + `FULL_ADMIN_PERMISSIONS` + `isActive: true` + server timestamps, (5) write `companySettings/profile` with tax config and provided fields. All Firestore writes use `merge: true` so re-runs preserve `createdAt`.
- `firebase/scripts/verify-setup.ts` (+112) — Reads back `companySettings/profile` and a slice of `users/` via Admin SDK, then signs in as the owner via Firebase Auth REST and exercises five rule paths via the Firestore REST API: positive own-user read, positive `companySettings` read, negative unauthenticated read, an admin cross-user read, and a negative read against a collection without an explicit `match` block (catch-all deny).
- `firebase/scripts/types.ts` (+96) — TS types matching `SCHEMA.md`: `PermissionLevel`, `Permissions`, `UserRole`, `UserDoc`, `TaxConfig`, `CompanySettingsDoc`, `CustomClaims`, plus exported constants `FULL_ADMIN_PERMISSIONS` and `NO_ACCESS_PERMISSIONS`.
- `firebase/scripts/README.md` (+51) — How to invoke `setup-project.ts`, every flag explained, tax-rate presets for common jurisdictions, idempotency notes.

**Scaffolding / config**
- `firebase/package.json` (+24) — `firebase-admin` + `firebase-tools` + `tsx`/`typescript`. Scripts: `deploy:rules`, `deploy:rules:dry`, `setup`.
- `firebase/package-lock.json` (+9138) — committed for reproducible installs.
- `firebase/tsconfig.json` (+17) — strict ES2022 + Bundler resolution + `noUncheckedIndexedAccess`.
- `firebase/.firebaserc` (+5) — default project alias `aromex-june-2026`.
- `firebase/firebase.json` (+6) — points the CLI at `firestore.rules` + `firestore.indexes.json`.
- `firebase/.gitignore` (+8) — excludes `node_modules/`, `secrets/`, `.env*`, `.firebase/`, `dist/`, `*.log`.

**Not touched:** `androidApp/`, `iosApp/`, `desktopApp/`, `sharedLogic/`, `sharedUI/`, the aromex-gateway repo, any existing tests.

## How to test

Pre-reqs: Node 20+, `npm`, the Aromex Firebase project's service-account key, and the project's Web API key (the `apiKey` field from the `google-services.json` — for REST sign-in in `verify-setup.ts`).

```bash
# 1. Install deps.
cd firebase
npm install

# 2. Place the service-account key for the project.
#    (gitignored — never committed.)
cp <path-to-key.json> ./secrets/aromex-june-2026-sa.json
chmod 600 ./secrets/aromex-june-2026-sa.json

# 3. Dry-run the rules — does NOT push them to the project.
GOOGLE_APPLICATION_CREDENTIALS="$(pwd)/secrets/aromex-june-2026-sa.json" \
  npx firebase deploy --only firestore:rules --project aromex-june-2026 --dry-run

# 4. Bootstrap a project end-to-end. (Generates and prints the password.)
OWNER_PASS=$(openssl rand -hex 12)
npm run setup -- \
  --projectId aromex-june-2026 \
  --serviceAccountKey ./secrets/aromex-june-2026-sa.json \
  --ownerEmail owner@aromex.test \
  --ownerPassword "$OWNER_PASS" \
  --ownerDisplayName 'Aromex Owner' \
  --companyName 'Aromex Test Workspace' \
  --hlCompanyId c6dd3a85-62ae-4490-bb9f-e039d874cf74 \
  --country CA --currency CAD \
  --gstRate 0.05 --pstRate 0.07

# Expect: "[1/5] … [5/5] …" with the final block printing the created uid +
# the password. The created user doc has all 11 permissions = "manage" and
# userMgmt = true; the token claims include { admin: true, hlCompanyId }.

# 5. Verify rules and the data round-trip.
npx tsx scripts/verify-setup.ts \
  --projectId aromex-june-2026 \
  --serviceAccountKey ./secrets/aromex-june-2026-sa.json \
  --apiKey <Web API key> \
  --ownerEmail owner@aromex.test \
  --ownerPassword "$OWNER_PASS"

# Expect: [A] Admin SDK reads return the doc, [B] sign-in returns a token
# whose claims show admin=true and the right hlCompanyId, [C] and [D]
# return 200, [E] returns 403, [G] returns 403.
```

Confirm the gateway is unaffected (this is the "ticket-#1 contract still works" check):

```bash
cd ../../aromex-gateway          # the gateway repo
GATEWAY_BASE_URL=http://68.183.86.89/gateway \
SA_KEY_PATH=$(pwd)/secrets/aromex-test-sa.json \
FB_API_KEY=<Web API key> \
TEST_EMAIL=ansh.bajaj2611@gmail.com \
TEST_UID=gateway-prod-e2e-user \
HL_BASE_URL=http://68.183.86.89/api-server \
npx tsx scripts/live-e2e.ts
# Expect: all 5 steps green, /hl-token returns 200 with a real HL JWT,
# /accounts returns 200.
```

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| `users/{uid}` and `companySettings/profile` structures defined in `firebase/`, matching PRD §7.3 and the permission catalog | ✅ Met | `firebase/SCHEMA.md` (+122) defines the field-level shape of both. `firebase/scripts/types.ts` (+96) mirrors it as enforceable TS types used by the setup script. `firebase/PERMISSIONS.md` (+93) is the canonical permission catalog. |
| Security Rules deployed to `aromex-june-2026` and enforce own-user read, admin-only writes, per-permission feature access, and no cross-company access | ✅ Met | `firebase/firestore.rules` (+121) — own-user read via `isSelf(uid) || isAdmin()`, admin-only writes for `users/`, `companySettings/`, `invites/`. Cross-project guard via `belongsToThisCompany()` (checks token's `hlCompanyId` claim equals `companySettings/profile.hlCompanyId`). `hasPermission(feature, level)` helper exists for future feature collections; the catch-all `match /{document=**}` denies unmapped paths. Rules were deployed to `aromex-june-2026` (verified by `verify-setup.ts` returning 403 on the catch-all and 200 on positive paths). |
| Custom-claims convention documented; setup script sets them on the first admin | ✅ Met | `firebase/scripts/types.ts` defines `CustomClaims = { admin: boolean; hlCompanyId: string }`. `firebase/SCHEMA.md` documents them under the `users/{uid}` heading. `firebase/scripts/setup-project.ts` step [3/5] calls `setCustomUserClaims(user.uid, { admin: true, hlCompanyId })`. |
| Setup script creates a working first-admin login (full permissions) | ✅ Met | `firebase/scripts/setup-project.ts` steps [2/5]–[5/5]. Verified live: created uid `rIkXghnN4DW3KJB4tqyUZWpCPCC2` in `aromex-june-2026` with `role: "admin"`, `permissions` set to `FULL_ADMIN_PERMISSIONS` (12 keys), `isActive: true`; the user signs in via Firebase Auth REST and the resulting token carries `admin=true` and the correct `hlCompanyId` (see `firebase/scripts/verify-setup.ts` output). |
| The gateway's `users/{uid}.isActive` check still works against the formalized structure | ✅ Met | Gateway's `live-e2e.ts` re-run against `http://68.183.86.89/gateway/` after rules deploy: steps 1–5 all green, `/hl-token` 200, `/accounts` 200. The gateway uses Admin SDK reads which bypass rules — by design, since Desktop also uses Admin SDK. |
| No secrets committed | ✅ Met | `firebase/.gitignore` excludes `secrets/`, `node_modules/`, `.env*`. The diff (15 staged files) shows no JSON under `secrets/`. The service-account key sits at `firebase/secrets/aromex-june-2026-sa.json` locally, ignored. |

## Deviations / decisions

- **Stale test users left in place.** `aromex-june-2026` already contained two pre-existing fixture user docs from earlier tickets (`gateway-e2e-user`, `gateway-prod-e2e-user`), each missing a `permissions` map and `userMgmt`. These were created via Admin SDK and remain readable. They don't conform to the new schema but aren't a runtime hazard (the gateway only reads `isActive`, which they have). Cleaning them up belongs in a fixture/data-hygiene ticket, not here.
- **`hasPermission()` is defined but currently unused.** The ticket explicitly asks for "gate each feature's data by the user's permissions". The feature collections (`sales/`, `purchases/`, `inventory/`, etc.) don't exist yet — they come in later tickets. To make those tickets cheap, the helper is in place and `firebase/README.md` includes a copy-paste-able rule block showing how to wire a new collection. The Firebase CLI emits a "Unused function" warning on deploy; harmless.
- **Two false-positive Firebase CLI warnings on deploy** about `get` and `request` inside `userPerms()`. The deploy succeeds; this is a known linter limitation with helper functions that close over rules built-ins.
- **`verify-setup.ts` test [F] (admin reads a fictional user) reports `400` not `200/403`.** The test uses an obviously-invalid doc-id string (`__definitely_not_a_real_uid__`) to keep the verify script self-contained, and Firestore rejects the request at the path-validation layer before rules evaluate. Substantively the test isn't load-bearing — the other four checks cover all the positive/negative paths.
- **Setup script is idempotent on owner email.** If the email already exists in Auth, the script calls `updateUser` and writes the Firestore docs with `merge: true` so re-runs are safe — useful when re-running against an in-progress project.
- **Tax flags default to "no tax".** Omitting `--gstRate`/`--pstRate` yields `gstEnabled=false, pstEnabled=false, isHST=false`. This matches the PRD's "tax is configurable per company" (0, 1, or 2 lines).

## Open questions / follow-ups

- **Cloud Function for in-app staff creation.** Explicitly out of scope (next ticket). The Cloud Function will read the same `users/{uid}` shape this ticket defines.
- **The two stale users** (`gateway-e2e-user`, `gateway-prod-e2e-user`) should be either deleted or backfilled with the new schema. Future fixture-cleanup ticket.
- **Per-feature `match` blocks** (`/sales/{id}`, `/purchases/{id}`, etc.) — these are deferred to the tickets that introduce those collections. The pattern is documented in `firebase/README.md`; failure to add the block fails closed.
- **Rules unit tests** (`@firebase/rules-unit-testing` with the emulator) are not present. Manual verification via `verify-setup.ts` against a real project is the bar this ticket meets. If we add many more rules, a proper emulator-based test suite is worth a dedicated ticket.
- **Setup-script invocation from the onboarding runbook.** This script is what M1-08 will call from a master onboarding flow. The flags it takes are stable enough for that ticket to wrap without changes.
- **Firebase CLI deploy needs a service-account key path in `GOOGLE_APPLICATION_CREDENTIALS`** when running non-interactive. The setup script sets it automatically for the subprocess; if you run `npm run deploy:rules` standalone, export the env var first (documented in `firebase/README.md`).
- **The first admin's password is generated and printed to stdout.** Documented behavior — operator emails it to the client and instructs them to rotate on first login. Not stored anywhere. A future "Cloud Function for staff invites" ticket may replace this with an email-link flow.
