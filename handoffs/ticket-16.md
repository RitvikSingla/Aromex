# Handoff — Ticket #16

**Ticket:** Humble-Coders/Aromex-KMP#16 — [Onboarding] Capture iOS Firebase app ID so companies work on iOS (register-company + portal + backfill)

**Where the code lives:**
- `Humble-Coders/aromex-gateway`, branch `ticket-16-onboarding-ios-appid`, commit `95752bb` on top of `main`. Two files, +102 / −0.
- This repo (Aromex-KMP): only this handoff document; the ticket has no Aromex-KMP application code.

## Summary

Closes the onboarding half of the iOS Firebase app-ID fix started in ticket #13. The gateway already carries `iosAppId` end-to-end via its passthrough `firebaseConfig` JSON blob (no gateway source change needed then, none needed now), but nothing prevented a new company from being registered *without* it — the failure would only surface later, at iOS `FirebaseApp.configure()`, as *"invalid GOOGLE_APP_ID"*. This ticket teaches the CLI onboarding path to refuse that: `scripts/register-company.ts` now validates the `--firebaseConfig` JSON contains a non-empty `iosAppId` matching `^1:\d+:ios:[0-9a-f]+$`, and prints an actionable multi-line error pointing at Firebase Console when it's missing or wrong-shaped. `docs/DEPLOY.md §10` gets an example JSON with both `appId` (Android/web) and `iosAppId` (iOS) fields, an explanation of why both are required, and a copy-pasteable backfill recipe for already-registered companies. The other two scope items — **the onboarding-portal update** and **the GTR backfill** — were completed separately by the PM (this developer's PR covered only `register-company.ts` + docs): gateway PR #7 adds the portal's "iOS app ID" field and **enforces `iosAppId` at `/admin/companies`** (closing the portal / direct-API gap the CLI-only check left open), and **GTR was backfilled** via `POST /admin/companies` — its config now carries the Android `appId` + `iosAppId`, verified live via `/resolve-company`.

## Files changed

**Server — CLI script** (`aromex-gateway`)
- `scripts/register-company.ts` (+40) — Added three validation checks after `JSON.parse` of the `--firebaseConfig` file: (1) `appId` present + non-empty (implicit before, now explicit); (2) `iosAppId` present + non-empty, with a five-line error that lists both required fields, tells the operator exactly where to find `iosAppId` in Firebase Console (Project settings → Your apps → the iOS app → App ID), and calls out the Aromex iOS bundle identifier for the case where no iOS app is yet registered; (3) `iosAppId` matches `^1:\d+:ios:[0-9a-f]+$`, catching the common mistake of pasting the Android app ID into the iOS field. All three exit 2 before any network call. The JSDoc header at the top of the file gets a matching paragraph explaining the requirement and consequence.

**Server — docs** (`aromex-gateway`)
- `docs/DEPLOY.md` (+62) — New subsection under §10 (Onboard a company against the production gateway): **`--firebaseConfig` must carry BOTH `appId` and `iosAppId`**. Shows an example JSON blob with both fields commented, explains where each comes from, notes the iOS bundle identifier, and states the failure mode when `iosAppId` is missing. A second new subsection — **Backfilling an already-registered company** — gives a copy-pasteable `curl POST /admin/companies` recipe (upsert semantics), followed by a `resolve-company` + `jq` verification command.

## How to test

Prereqs:
- Node 20+, `tsx` on PATH (`npm i -g tsx` or `npx tsx`).
- Local checkout of `Humble-Coders/aromex-gateway`.

```bash
git clone git@github.com:Humble-Coders/aromex-gateway.git   # or use your existing checkout
cd aromex-gateway
git fetch
git checkout ticket-16-onboarding-ios-appid
npm ci
```

**Case 1 — valid config passes validation and proceeds to the network step.**

```bash
# Uses the aromex-test Firebase config which already has both appId and iosAppId
# (updated to include iosAppId during ticket #13's live patch).
head -c 200 secrets/aromex-test-firebase-config.json
# → shows both "appId": "1:...:android:..." and "iosAppId": "1:...:ios:..."

# Run against a bogus base URL so the script fails on the network step, AFTER
# validation. This proves the config passes the new checks.
ADMIN_API_TOKEN=x GATEWAY_BASE_URL=http://127.0.0.1:1 \
npx tsx scripts/register-company.ts \
  --id t --displayName t \
  --firebaseConfig ./secrets/aromex-test-firebase-config.json \
  --serviceAccountKey /tmp/nope.json \
  --hlCompanyId t --hlCredentialPrefix T --currency CAD --email a@b.c
# → validation passes; script fails on POST /admin/companies with a fetch/connect error.
```

**Case 2 — missing `iosAppId` errors before any network call.**

```bash
cat > /tmp/no-ios.json <<'EOF'
{"apiKey":"AIza","projectId":"x","appId":"1:1:android:aaaa"}
EOF
ADMIN_API_TOKEN=x GATEWAY_BASE_URL=http://127.0.0.1:1 \
npx tsx scripts/register-company.ts \
  --id t --displayName t --firebaseConfig /tmp/no-ios.json \
  --serviceAccountKey /tmp/nope.json \
  --hlCompanyId t --hlCredentialPrefix T --currency CAD --email a@b.c
# → prints the multi-line "missing iosAppId" error and exits 2.
```

**Case 3 — wrong-shape `iosAppId` errors before any network call.**

```bash
cat > /tmp/wrong.json <<'EOF'
{"apiKey":"AIza","projectId":"x","appId":"1:1:android:aaaa","iosAppId":"1:1:android:cafebabe"}
EOF
ADMIN_API_TOKEN=x GATEWAY_BASE_URL=http://127.0.0.1:1 \
npx tsx scripts/register-company.ts \
  --id t --displayName t --firebaseConfig /tmp/wrong.json \
  --serviceAccountKey /tmp/nope.json \
  --hlCompanyId t --hlCredentialPrefix T --currency CAD --email a@b.c
# → "does not look like an iOS app ID" error; exits 2.
```

**Case 4 — the deployed gateway still resolves `aromextest` with `iosAppId` from ticket #13's live patch:**

```bash
curl -sS -X POST http://68.183.86.89/gateway/resolve-company \
  -H "Content-Type: application/json" \
  -d '{"email":"ansh.bajaj2611@gmail.com"}' | jq '.companies[0].firebaseConfig'
# → object contains both "appId" (android) and "iosAppId" (ios). Confirms no regression to
# the deployed row, and gives the reviewer a reference for what a valid resolved config looks like.
```

**Case 5 — docs render as expected.** Open `docs/DEPLOY.md` at §10 and confirm the two new subsections (`--firebaseConfig must carry BOTH…` and `Backfilling an already-registered company`) read cleanly and the JSON example / curl commands are formatted correctly.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| `register-company.ts` — capture `iosAppId` and include it in the `firebaseConfig` blob sent to `/admin/companies` (either a new `--iosAppId` flag, or require the `--firebaseConfig` JSON to contain `iosAppId` and validate its presence with a clear warning if missing). | ✅ Met | Chose the second variant (validate the JSON). The script now checks `appId` presence, `iosAppId` presence, and `iosAppId` shape against `^1:\d+:ios:[0-9a-f]+$` — each with a `process.exit(2)` and a targeted error string. The `firebaseConfig` object itself is still passed through to the gateway unchanged (the gateway is `.passthrough()`), so a valid `iosAppId` in the file flows into the DB automatically. Verified by dry-run against three configs (valid, missing `iosAppId`, wrong-shape `iosAppId`) — outputs match the messages in the diff. |
| onboarding-portal — add an **"iOS app ID"** field, include `iosAppId` in the `firebaseConfig` it posts, update its README. | ❌ Deferred to a follow-up issue at reporter's request | No portal change in this diff. Portal repo is not currently checked out locally; scope was trimmed rather than blocking the ticket on a repo hunt. Result: **companies onboarded via the portal (as opposed to the CLI) still don't get an `iosAppId`** until the follow-up ships — same crash-on-iOS behavior the CLI now prevents. |
| Backfill existing companies — add `iosAppId` for at minimum **GTR**; confirm `aromextest` already has it. | ⚠️ Half met, half deferred | `aromextest` confirmed: `curl /resolve-company` for `ansh.bajaj2611@gmail.com` returns `iosAppId=1:409115559862:ios:0efe477c97b61e7523e317` (from ticket #13's live patch; live-verified during this ticket — no regression). GTR backfill was deferred at reporter's request rather than executed. GTR remains broken on iOS until the follow-up. |
| Docs state the two-app-ID requirement in `aromex-gateway/docs/DEPLOY.md` and the onboarding runbook / auth guide. | ✅ Met (DEPLOY.md); portal README deferred with the portal work | `docs/DEPLOY.md §10` diff adds a full example JSON, the "where to find each" pointers, the failure-mode explanation, and the backfill recipe. Portal README waits on the portal follow-up. |
| No secrets committed. | ✅ Met | Diff contains no service-account JSON, no `.env`, no HL passwords, no bundled Firebase configs. `secrets/aromex-test-firebase-config.json` is untouched by this branch and remains under the gateway repo's `.gitignore` (`secrets/` is line 5). |
| Missing `iosAppId` at onboarding is surfaced as a clear warning (not a silent success that breaks iOS later). | ✅ Met | Two exit paths — missing and wrong-shape — each print a distinct multi-line error and exit 2. The messages name the file, describe the required format, tell the operator exactly where to find the value in Firebase Console, and mention the iOS bundle identifier. |
| Onboarding a new company via `register-company.ts` results in `/resolve-company` returning a valid `iosAppId`. | ✅ Met (for the CLI path) | The gateway is unchanged — its `.passthrough()` schema already returns `firebaseConfig` verbatim, which the deployed gateway confirmed live during ticket #13 (and re-confirmed for `aromextest` in this ticket). The CLI now enforces the input has `iosAppId`, so the output must too. The **portal** path is not yet covered — see the deferred criterion above. |
| `curl /resolve-company` for **GTR** returns an `iosAppId`; iOS login no longer crashes for it. | ❌ Deferred | GTR row not modified in this ticket. Explicit reporter decision. Deferred to a follow-up. |

## Deviations / decisions

- **Chose "validate JSON contains `iosAppId`" over "add `--iosAppId` CLI flag".** The ticket allowed either. The JSON is already the canonical Firebase config shape (that's how operators receive it from Firebase Console / from clients); adding a parallel CLI flag would create two sources of truth and let them disagree. Validation in the file keeps the CLI signature stable and matches the direction the portal would naturally take too (form field goes into the same JSON object).
- **Regex is intentionally permissive.** `^1:\d+:ios:[0-9a-f]+$` catches the two mistakes operators actually make (pasting the Android or web ID). It doesn't validate the hash length or Firebase's exact prefix rules — those change over time and a stricter regex would produce false rejections for legitimate future IDs.
- **`appId` presence check was tightened as a side-effect.** The old script parsed the JSON and forwarded it without checking any field, so a totally-empty `{}` would have upserted a garbage `firebaseConfig` blob. Since I was already adding validation, I added a one-liner for `appId` too. Purely defensive; no operator has hit this in practice.
- **Portal + GTR backfill deferred at reporter's explicit request.** The ticket's four scope items were surfaced to the reporter during planning:
  - Portal: reporter chose "Skip portal — do it in a follow-up ticket."
  - GTR backfill: reporter chose "Skip GTR — track separately."
  Both should ship as their own tickets so this ticket's small, obviously-correct gateway + docs change can merge without waiting on a repo hunt or a production write.
- **No test file added.** The changes are three sequential guards immediately after `JSON.parse`; the existing vitest suite in `tests/` covers the gateway routes, not the CLI script. Live dry-run against three fixture configs is the practical verification. If a `scripts/` test suite is added later, this validation is easy to unit-test.

## Open questions / follow-ups

- **Follow-up ticket: onboarding-portal iOS app ID field.** Add an "iOS app ID" input to the portal form (validation: `^1:\d+:ios:[0-9a-f]+$`, same regex the CLI now uses), include `iosAppId` in the `firebaseConfig` object POSTed to `/admin/companies`, and update the portal README to point at the two-app-ID rule in `aromex-gateway/docs/DEPLOY.md`. **Until this ships, companies onboarded via the portal will crash on iOS the first time an iOS user signs in.**
- **Follow-up ticket: backfill GTR.** One live `POST /admin/companies` upsert against `http://68.183.86.89/gateway/` with GTR's existing row values plus `iosAppId: "1:405204067459:ios:e01f951ad2a828157c67b6"` (from the ticket description). Requires the current row's `serviceAccountKeyPath`, `hlCompanyId`, `hlCredentialPrefix`, `currency`, and the rest of the `firebaseConfig` — safest read from Postgres on the server rather than reconstructed from memory. **Until this ships, GTR crashes on iOS.**
- **Ticket #13 handoff already flagged this ticket** in its "Open questions / follow-ups" — that flag can now point at #16 (this ticket) for the CLI + docs piece, and the two new follow-up issues for the portal + GTR pieces.
- **`aromextest` sanity confirmed live.** During this ticket, `curl /resolve-company` for the indexed test email returned `firebaseConfig.iosAppId = "1:409115559862:ios:0efe477c97b61e7523e317"`. The ticket #13 hand-patch has not regressed.
- **Regex format could tighten later.** If Firebase changes their app-ID format (or adds a new platform tag we care about), the regex will need updating in three places going forward: `scripts/register-company.ts`, the future portal form validator, and any client-side validators. Worth centralizing in a small `src/util/firebaseIds.ts` if a third caller appears.
- **`--serviceAccountKey` still passes an arbitrary path unchecked.** Out of scope here — mentioned only to note that the CLI still trusts the operator on the server-side file's existence and contents. If we ever ship a "self-service" onboarding path, that assumption changes.
