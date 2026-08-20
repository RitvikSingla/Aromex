# [Onboarding] Capture the iOS Firebase app ID so companies work on iOS

> **Components:** `aromex-gateway` (`scripts/register-company.ts`) + the **onboarding-portal** + a small
> data backfill. Issue tracked here; code lands in those repos.
> Milestone: **iOS & Desktop parity**.

## 📖 Story / Why
The iOS app (ticket #13) init s Firebase per company from the config the gateway returns. Firebase's iOS
SDK **rejects the Android app ID** and crashes at `configure()` with *"invalid GOOGLE_APP_ID"* — it needs
the **iOS-format** app ID (`1:PROJECT_NUMBER:ios:HASH`). So the shared model now carries an optional
`iosApplicationId`, and iOS reads `firebaseConfig.iosAppId` from `/resolve-company`.

**The gap:** only **`aromextest`** was hand-patched (during #13) to include `iosAppId`. Onboarding doesn't
capture it, so **every new company — and GTR today — will crash on iOS.** This ticket makes `iosAppId` a
first-class part of onboarding and backfills existing companies.

## 🧭 Context
- The gateway stores one **passthrough** `firebaseConfig` JSON blob per company (`POST /admin/companies`;
  zod schema is `.passthrough()`), returned verbatim by `/resolve-company`. Adding `iosAppId` needs **no
  gateway code change** — just make sure onboarding puts it in the blob.
- The iOS app ID comes from the client's Firebase project: **Project settings → Your apps → the iOS app →
  App ID** (`1:…:ios:…`). Register an iOS app in the project if one doesn't exist.
- Existing iOS app IDs already exist for our test projects — e.g. **GTR** (`gtr---kmp`) already has an iOS
  app: `1:405204067459:ios:e01f951ad2a828157c67b6`. So backfill is just a config update, not new Firebase
  setup.

## ✅ Scope / What to build
- [ ] **`aromex-gateway/scripts/register-company.ts`** — capture `iosAppId` and include it in the
      `firebaseConfig` blob sent to `/admin/companies` (either a new `--iosAppId` flag, or require the
      `--firebaseConfig` JSON to contain `iosAppId` and validate its presence with a clear warning if
      missing).
- [ ] **onboarding-portal** — add an **"iOS app ID"** field (or have the operator paste the iOS config),
      and include `iosAppId` in the `firebaseConfig` it posts to `/admin/companies`. Update its README.
- [ ] **Backfill existing companies** — add `iosAppId` to each already-registered company's gateway config
      via `POST /admin/companies` (at minimum **GTR** → `1:405204067459:ios:e01f951ad2a828157c67b6`; confirm
      `aromextest` already has it). *(Ask the PM for the admin token via secure channel.)*
- [ ] **Docs** — note the `iosAppId` requirement in `aromex-gateway/docs/DEPLOY.md` and the onboarding
      runbook / auth guide (a company must carry both `appId` (Android) and `iosAppId` (iOS) to work on both
      platforms).

## 🎯 Acceptance Criteria
- [ ] Onboarding a new company (via `register-company.ts` **and** via the portal) results in the company's
      `/resolve-company` response containing a valid `iosAppId`.
- [ ] `curl /resolve-company` for **GTR** returns an `iosAppId` (backfill applied); iOS login no longer
      crashes for it.
- [ ] Missing `iosAppId` at onboarding is surfaced as a clear warning (not a silent success that breaks iOS
      later).
- [ ] Docs state the two-app-ID requirement. No secrets committed.

## 🚫 Out of scope
- Any iOS app code (already shipped in #13).
- Gateway source changes (the passthrough schema already carries the field).

## 🔗 Dependencies
- Follows **#13** (merged) which added `FirebaseClientConfig.iosApplicationId` + the `iosAppId` DTO parse on
  both platforms.

## 📚 References
- `aromex-gateway/scripts/register-company.ts`, `docs/DEPLOY.md`.
- onboarding-portal `onboard.mjs` / `public/index.html` (the firebaseConfig it posts).
- Aromex-KMP #13 handoff (`handoffs/ticket-13.md`) — the "Firebase iOS app ID had to be plumbed
  end-to-end" deviation.

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
