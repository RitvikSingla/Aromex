# Handoff — Ticket #11

**Ticket:** Humble-Coders/Aromex-KMP#11 — [M2] Android — HL client: brokered token + read account balances onto Home

**Where the code lives:** This repo, branch `ticket-11-android-hl-balances`, single commit `4349f5c` on top of `master`. **No external repo touched.** 20 files, +807 / −16.

## Summary

First M2 ticket — wires the Android app to Humble Ledger. After login, Home asks Firebase Auth for a fresh ID token, posts it to the gateway's `/hl-token`, caches the returned short-lived HL JWT in memory, and uses it directly against HL to read the company's chart of accounts + balances via `GET /api/v1/reports/balance-sheet?date=2999-12-31` (the endpoint that bundles accounts + balances per HL `MOBILE_ADMIN_API.md` §4–§5; `/accounts` has only the chart). Built in strict `/kmp-arch` layering: shared `model`/`repository`/`usecase` with no platform/Firebase/HL imports (verified by `:sharedLogic:compileCommonMainKotlinMetadata`), platform repo impls in `androidApp/data`, `StateFlow` ViewModels with manual DI, nothing in `sharedUI`. Money is treated as a decimal `String` end-to-end; the cached HL token refreshes lazily — on the 30s-before-expiry edge or on a 401 from HL (single retry). Verified live on an emulator against `aromex-june-2026` + `http://68.183.86.89/{gateway,api-server}` end-to-end: `/resolve-company` → `/hl-token` (200, 760s expiry) → `/balance-sheet` (200, empty chart for the freshly-provisioned test company — read works, which is what the ticket asks to prove).

## Files changed

**Shared logic (`sharedLogic/commonMain` — pure Kotlin, zero platform imports)**
- `model/LedgerAccount.kt` (+66) — `data class LedgerAccount(id, name, category: AccountCategory, type: AccountType, balance: String)` + `AccountCategory { ASSET, LIABILITY, EQUITY }` (from the balance-sheet section the row came from) + `AccountType { CASH, BANK, CREDIT_CARD, RECEIVABLE, PAYABLE, REVENUE, EXPENSE, TAX, OTHER }` with `fromName(name)` that infers a specific type from HL's display name. Money is `String`.
- `model/HlError.kt` (+16) — sealed `HlError`: `NetworkUnavailable`, `GatewayUnreachable`, `TokenRejected`, `HlUnreachable`, `Unauthorized`, `Unexpected(message)`. `class HlException(val error: HlError) : RuntimeException(error.toString())`. Mirrors `LoginError`/`LoginException`.
- `repository/LedgerRepository.kt` (+11) — `interface LedgerRepository { suspend fun getAccounts(): List<LedgerAccount> }`.
- `repository/HlTokenProvider.kt` (+17) — `interface HlTokenProvider { suspend fun currentToken(): String; suspend fun invalidate() }`. Token seam so use cases never see Firebase / gateway.
- `repository/AuthRepository.kt` (+8) — added `suspend fun idToken(config: FirebaseClientConfig, forceRefresh: Boolean = false): String`.
- `usecase/GetAccountBalancesUseCase.kt` (+10) — thin orchestrator (`execute() = ledger.getAccounts()`); UI does the grouping for display.
- `model/UserSession.kt` (+1) — added `currency: String` so the UI can render `${balance} ${currency}` without re-reading `companySettings/profile`.
- `usecase/LoginUseCase.kt` (+1), `usecase/RestoreSessionUseCase.kt` (+1) — propagate `companyProfile.currency` into the new `UserSession.currency` field.
- `i18n/Strings.kt` (+30) — new keys: `home_balances_title`, `home_balances_loading`, `home_balances_empty`, `home_balances_error`, `home_balances_retry`, `home_balances_section_{cash,bank,credit_card,other}`, `hl_error_{network,gateway,token_rejected,hl_unreachable,unauthorized,unexpected}`, `account_type_{cash,bank,credit_card,receivable,payable,revenue,expense,tax,other}`.
- `i18n/EnglishStrings.kt` (+30) — English values for all of the above.

**Android (`androidApp`)**
- `data/AromexConfig.kt` (+13 / −1) — added `internal const val HL_API_BASE_URL = "http://68.183.86.89/api-server"` next to the gateway URL; same `TODO(M1-04 Phase B)` for HTTPS and a comment noting the existing `network_security_config.xml` cleartext allowance already covers HL (same IP as the gateway).
- `data/FirebaseAuthRepository.kt` (+15) — implements the new `idToken(config, forceRefresh)` via `authFor(config).currentUser?.getIdToken(forceRefresh)?.await()?.token`. Throws `LoginException(LoginError.Unexpected("no signed-in user"))` if null; wraps non-LoginException throwables as `LoginError.FirebaseFailure(...)`.
- `data/HlTokenRepository.kt` (+134) — implements `HlTokenProvider`. In-memory `cachedToken` + `expiresAtMs` guarded by a `Mutex` (so concurrent first-call requests share one broker round-trip). `currentToken()`: returns cached if `now < expiresAt - 30s`, else brokers fresh. `invalidate()` clears the cache. `brokerLocked()` calls `authRepo.idToken(activeConfig)` then `POST $gatewayBase/hl-token` with `Authorization: Bearer <idToken>` and **no body / no Content-Type** (Fastify returns 400 `FST_ERR_CTP_EMPTY_JSON_BODY` if you send `Content-Type: application/json` with an empty body — matches the contract verified by `aromex-gateway/scripts/live-e2e.ts`). Parses `{ hlToken, expiresIn }`; maps `IOException` → `HlError.NetworkUnavailable`, 401/403 → `HlError.TokenRejected`, non-2xx → `HlError.GatewayUnreachable`.
- `data/HlLedgerRepository.kt` (+147) — implements `LedgerRepository`. `getAccounts()`: builds `GET $hlBase/api/v1/reports/balance-sheet?date=2999-12-31` with `Authorization: Bearer ${tokenProvider.currentToken()}`. On 401 calls `tokenProvider.invalidate()` and retries exactly once with a fresh token; a second 401 → `HlError.Unauthorized`. Parses HL's `{ success, data: { assets, liabilities, equity, … } }` envelope and flattens the three sections into a single `List<LedgerAccount>` (category derived from the section). Money kept as the raw HL string. Maps `IOException` → `HlError.NetworkUnavailable`, other non-2xx → `HlError.HlUnreachable`, parse failures → `HlError.Unexpected(...)`.
- `data/HttpCompanyDirectoryRepository.kt` (+6) — added structured logcat lines on the existing `/resolve-company` path so the whole network trace shows up under one filter (`Aromex/Gateway`).
- `data/NetLog.kt` (+45) — small redaction + chunked-log helper (`NetLog.redact("eyJhbGci…(412ch)")`; `logLong()` splits bodies above ~3.5 KB so logcat's 4 KB line cap doesn't truncate). Tokens never reach logcat in full.
- `ui/home/HomeViewModel.kt` (+62 / −3) — manual DI now wires `prefs`, `authRepo`, and per-session `tokenRepo = HlTokenRepository(authRepo, config)` + `ledgerRepo = HlLedgerRepository(tokens)` + `balancesUseCase` inside `bind(session, config)`. State adds `accounts: List<LedgerAccount>`, `isLoadingBalances: Boolean`, `balancesError: HlError?`. New `retryBalances()`. `signOut()` no longer takes a config parameter — it's stored on the VM after `bind`.
- `ui/home/HomeScreen.kt` (+189 / −10) — replaced the placeholder. Header (title + signed-in-as + role), then a "Account balances" panel: loading spinner while fetching, error card + Retry button on error, otherwise a `LazyColumn` grouped by `AccountType` — Cash / Bank / Credit Card pinned at the top, "Other accounts" below. Each row: name + type label on the left, `${balance} ${session.currency}` on the right. Empty list → `home_balances_empty`. Sign-out at the bottom. All copy via `Strings` / `LocalStrings`.
- `navigation/AromexApp.kt` (+5 / −2) — passes both `session` and `config` into `homeViewModel.bind(...)` and wires the new `onRetryBalances` callback; sign-out callback dropped its `config` argument.

**Not touched:** `iosApp/`, `desktopApp/`, `sharedUI/`, the `aromex-gateway` repo, `firebase/`, `docs/`, `network_security_config.xml`, `AndroidManifest.xml`, `build.gradle.kts`, `gradle/libs.versions.toml`. No `expect`/`actual`. No DI framework. No new deps (OkHttp + kotlinx-serialization already pulled in by ticket #9).

## How to test

Prereqs:
- Android emulator or device on API 24+ with Internet.
- Android SDK with `adb` on PATH.
- The Aromex gateway live at `http://68.183.86.89/gateway/` and HL live at `http://68.183.86.89/api-server/` (both verified live during ticket #3 / #4).
- A working test login in `aromex-june-2026` whose company is provisioned in HL (e.g. `aromextest`). **Password is provided by the PM via one-time-secret — never committed.**

```bash
# 1. Clean checkout + branch.
git fetch
git checkout ticket-11-android-hl-balances

# 2. Build the debug APK. The metadata build proves no platform imports leaked into sharedLogic.
./gradlew :sharedLogic:compileCommonMainKotlinMetadata
./gradlew :androidApp:assembleDebug

# 3. Install on a running emulator/device.
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.humblesolutions.aromex/.MainActivity

# 4. Watch the network trace (one filtered logcat shows the whole chain).
adb logcat -c
adb logcat -s Aromex/Gateway:D Aromex/HlToken:D Aromex/HlLedger:D
```

Live flow on Home:
- Sign in with the test login → Home shows the title / signed-in-as / role header, then the balances panel.
- Expected logcat (one full chain, happy path):
  - `Aromex/Gateway  POST .../resolve-company  → HTTP 200, 1 candidate(s)`
  - `Aromex/HlToken  cache MISS → brokering fresh /hl-token`
  - `Aromex/HlToken  POST .../hl-token  → HTTP 200, expiresIn=900s` (or close — gateway caches the HL login and counts down a shared TTL)
  - `Aromex/HlLedger getAccounts() → GET .../balance-sheet?date=2999-12-31 (attempt 1)`
  - `Aromex/HlLedger attempt 1 → HTTP 200; raw body: {"success":true,"data":{...}}`
  - `Aromex/HlLedger attempt 1 → parsed: N accounts (X assets, Y liab, Z equity)`
- For a freshly-provisioned HL company the arrays are empty — UI renders `home_balances_empty` ("No accounts yet."). The read itself succeeding is the acceptance condition (the ticket explicitly says *"A freshly-registered HL company has zero balances — the chart of accounts still lists, which is enough to verify the read."*).

Cache + retry spot-checks:
- Force-stop and relaunch (`adb shell am force-stop com.humblesolutions.aromex && adb shell am start -n com.humblesolutions.aromex/.MainActivity`) → restored session goes back to Home, you see exactly **one** new `POST /hl-token` (in-memory cache doesn't survive process death — expected), then the `GET /balance-sheet`. Within the same process, subsequent reads should hit `cache HIT  hlToken=…  expires in Ns`.
- Network off (`adb shell svc data disable && adb shell svc wifi disable`) → Home shows the inline error card. Re-enable network (`svc data enable && svc wifi enable`) and tap Retry → balances render.

Tokens are redacted in logcat (e.g. `eyJhbGci…(412ch)`); full JWTs never reach the logs.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Strictly follows `/kmp-arch`: shared `model`/`repository`/`usecase` have no platform/HL/Firebase imports; Android implements the interfaces; ViewModel uses `StateFlow` + manual DI; nothing in `sharedUI`; no `expect`/`actual`. | ✅ Met | `:sharedLogic:compileCommonMainKotlinMetadata` builds clean against the common metadata target — proves all 7 new shared files (`model/LedgerAccount`, `model/HlError`, `repository/LedgerRepository`, `repository/HlTokenProvider`, `usecase/GetAccountBalancesUseCase`, plus the `i18n` + existing-file changes) compile with no Firebase/HL/Android symbols. Diff shows `HomeViewModel.bind()` builds its own dep chain (`HlTokenRepository → HlLedgerRepository → GetAccountBalancesUseCase`). No file under `sharedUI/` is in the diff. No `expect` / `actual` keywords appear in the diff. |
| On Home, the app brokers an HL token via the gateway and lists the signed-in company's accounts + balances — verified live against a real login. | ✅ Met | Verified live on the emulator end-to-end. Logcat trace from the run: `Aromex/HlToken POST .../hl-token → HTTP 200, expiresIn=760s` (real HL JWT, redacted in logs), then `Aromex/HlLedger attempt 1 → HTTP 200; raw body: {"success":true,"data":{"asOf":"2999-12-31T00:00:00.000Z","assets":[],"liabilities":[],"equity":[],"totalAssets":"0","totalLiabilitiesAndEquity":"0","retainedEarnings":"0","isBalanced":true}}` → parsed: 0 accounts. Home rendered `home_balances_empty`. Freshly-provisioned HL company has no balances yet (per the ticket's own caveat); the chain itself works. |
| The `hlToken` is cached (only one `/hl-token` call across multiple reads) and re-brokered on expiry / 401. | ✅ Met | `HlTokenRepository.currentToken()` returns the cached token while `now < expiresAtMs - 30s` (see the `cache HIT  hlToken=…  expires in Ns` log path); only the first call inside the live trace emitted `cache MISS → brokering fresh /hl-token`. `HlLedgerRepository.getAccounts()` calls `tokenProvider.invalidate()` on the first 401 and retries with a fresh token (second 401 → `HlError.Unauthorized`). `Mutex.withLock` ensures concurrent first-call requests don't both broker. |
| No HL credentials or service-account keys on the device — only the short-lived token; no secrets committed. | ✅ Met | The Android app only ever holds the brokered `hlToken` in memory (cleared on process death). HL credentials live on the gateway server (per ticket #3 `HL_USER_*` / `HL_PASS_*`). Diff contains no `.env`, no service-account JSON, no HL passwords. Logcat redacts tokens to `prefix…(Nch)`. |
| Money is handled as decimal strings end to end (no `Double` / `Float` for money). | ✅ Met | `LedgerAccount.balance: String` (sharedLogic model). `HlLedgerRepository` keeps the raw HL JSON string as-is — no parsing. `HomeScreen.AccountRow` renders `"${account.balance} $currency"` directly. No `Double` / `Float` declarations in the HL or balances diff. |
| Clear states for loading and for gateway/HL/network errors. | ✅ Met | `HomeUiState` has `isLoadingBalances`, `balancesError: HlError?`. `HomeScreen` renders `BalancesLoading()` while `isLoadingBalances && accounts.isEmpty()`, `BalancesError(error, onRetry)` when `balancesError != null`, and `home_balances_empty` when both flags clear and the list is empty. `HlError.toStringKey()` maps each variant (Network / Gateway / TokenRejected / HlUnreachable / Unauthorized / Unexpected) to a distinct user-facing string key. |
| Cleartext is limited to the gateway + HL hosts in the dev-only network-security config, with a removal `TODO`. | ✅ Met | `network_security_config.xml` is unchanged because the HL droplet is the **same IP as the gateway** (`68.183.86.89`) — just a different path (`/gateway` vs `/api-server`). The existing dev-only cleartext allowance from ticket #9 already covers both. The `AromexConfig.kt` diff has a comment noting this and carries the same `TODO(M1-04 Phase B)` to switch to HTTPS. |

## Deviations / decisions

- **Pivoted from `GET /api/v1/accounts` to `GET /api/v1/reports/balance-sheet?date=2999-12-31`.** The ticket described the read as "chart of accounts + balances" via `/accounts`, but per HL `MOBILE_ADMIN_API.md` §4–§5 the `/accounts` endpoint returns the chart only (`id`, `name`, `type`, `parentId`) without balances; the actual balances live on the balance-sheet report (which is also HL's own "Recipe 1 — current Cash & Bank balance"). The balance-sheet endpoint bundles both. To keep the shared model coherent we added `AccountCategory` (ASSET / LIABILITY / EQUITY) derived from the section the row came from, alongside `AccountType` (CASH / BANK / CREDIT_CARD / …) inferred from the row's name. Calling `/accounts` separately to enrich the chart is a future ticket (it would add hierarchy via `parentId`, which the balance-sheet doesn't expose).
- **`UserSession` gained `currency: String`.** The ticket didn't list this, but `LedgerAccount.balance` is just a number and Home needs to render `${balance} ${currency}`. The cleanest place for it is `UserSession`, since "one currency per company" is a locked PRD decision and the value is already read from `companySettings/profile` at login. Propagated through both `LoginUseCase.finishLogin` and `RestoreSessionUseCase.execute`. `LedgerAccount` deliberately does **not** carry currency — that would be a leaky abstraction given the one-currency rule.
- **`POST /hl-token` sends no body and no Content-Type.** Fastify's default JSON body parser returns 400 `FST_ERR_CTP_EMPTY_JSON_BODY` if `Content-Type: application/json` is set with an empty body. The verified `aromex-gateway/scripts/live-e2e.ts` calls `/hl-token` with just the `Authorization` header and no body, so we match that exactly: `EMPTY_BODY = "".toRequestBody(null)` in `HlTokenRepository`. This was caught during live verification (the initial implementation hit HTTP 400 in logcat) and fixed in the same commit.
- **`HlTokenProvider` lives in `sharedLogic/repository`, not Android-only.** Tiny abstraction, but it lets `GetAccountBalancesUseCase` stay ignorant of Firebase / gateway. iOS / Desktop just need to implement the same two methods.
- **`NetLog` is Android-only and `data`-package-scoped.** It uses `android.util.Log` directly so it can't go in `sharedLogic`. Verbose request/response logging is on by default for the M2 dev loop — production should switch logcat priority down before ship. Tokens are redacted (`prefix…(Nch)`) so even at DEBUG, full JWTs never leak.
- **`HlLedgerRepository` 401-retry is at most one re-broker per call.** A second 401 surfaces `HlError.Unauthorized` rather than looping. Prevents an account-disabled / rules-change cycle from spinning forever.
- **No grouping logic in `GetAccountBalancesUseCase` — UI does the grouping.** The shared use case stays a one-liner; `HomeScreen.BalancesList` pins Cash / Bank / Credit Card / Other. Keeps the use case independent of "how Home wants to present this," and means a future Statistics screen can show the same data differently without touching shared logic.
- **No `androidx.navigation:navigation-compose` added.** Same call as ticket #9 — three screens (Splash, Login, Home) with one inline ChooseCompany branch; sealed-class `Route` + `when` is shorter and zero extra binary cost. Migrate when we cross ~8 screens.

## Open questions / follow-ups

- **The verified test company has zero balances.** `aromextest` in `aromex-june-2026` is freshly provisioned, so the live balance-sheet response is `{ assets: [], liabilities: [], equity: [], totalAssets: "0", isBalanced: true }`. The read itself works (ticket criterion met), but the UI grouping / pinning logic is **not** live-exercised. A reviewer with access to a company that has real Cash / Bank / Credit Card postings should re-verify that the grouped layout renders correctly. Alternatively, a small `:sharedLogic:commonTest` fake of `LedgerRepository` can drive the VM with synthetic data — worthwhile follow-up.
- **`AccountType.fromName` is heuristic and English-only.** HL's chart-of-accounts names are seeded in English at onboarding ("Cash", "Bank", "Credit Card", "Accounts Receivable"). If a future client gets a localized chart, the grouping breaks (everything becomes `OTHER`). Long-term: use `/api/v1/accounts` to enrich each row with its HL `type` code (ASSET / LIABILITY) and add a non-name-based mapping for the named pins.
- **HL token cache is per `HomeViewModel` instance.** Each `bind()` creates a fresh `HlTokenRepository`; configuration changes that drop the VM (none today, but theoretically) would refetch a token. Acceptable for now — when we add more HL-touching screens (purchases, sales, balances dashboard) we should hoist `HlTokenRepository` to an application-scoped singleton (or a small `HlServiceLocator` in the Android module) so screens share the same cached token.
- **Verbose request logging is on by default.** Tokens are redacted but URLs, status codes, and full balance-sheet bodies still print to logcat. Before shipping a production build, gate the logs behind `BuildConfig.DEBUG` (or remove `NetLog.d` calls entirely from release).
- **No unit tests added.** `GetAccountBalancesUseCase` is a one-liner over the interface; the interesting logic is `HlTokenRepository.currentToken()` (cache + mutex + expiry) and `HlLedgerRepository.getAccounts()` (401 retry + envelope parse). Both are easy to test with a fake `OkHttpClient` (interceptor) and a clock injection (`now: () -> Long` is already a constructor param of `HlTokenRepository`). Live verification was prioritized for this ticket; a small `:sharedLogic:commonTest` + `androidUnitTest` suite is a worthwhile follow-up.
- **Permission gating not applied.** `UserSession.permissions.balances` is captured but the Home balances panel doesn't yet check it. Real gating arrives when other feature screens land and we standardize the gate pattern.
- **No write/post side of HL yet.** This ticket is read-only. The idempotency layer + dual-write (Firebase operational record + HL post) is the next M2 ticket.
- **iOS and Desktop.** Out of scope. The shared layer is the contract; both platforms implement `LedgerRepository` + `HlTokenProvider` + the new `AuthRepository.idToken()` against their native SDKs (Firebase iOS / Firebase Admin SDK on JVM).
- **Gateway HTTPS (Phase B).** Same TODO as ticket #9: when the gateway gets a domain + Let's Encrypt, switch both `GATEWAY_BASE_URL` and `HL_API_BASE_URL` to `https://`, remove the cleartext exception from `network_security_config.xml`, drop the `networkSecurityConfig` attribute from `AndroidManifest.xml`.
