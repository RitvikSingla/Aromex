# Handoff — Ticket #19

**Ticket:** Humble-Coders/Aromex-KMP#19 — [Parity] Desktop — full app: email-discovery login + HL balances (Compose-Desktop)

**Where the code lives:** This repo, branch `ticket-19-desktop-full-app`. ~2,300 lines of new Kotlin under `desktopApp/`, plus tiny edits to `desktopApp/build.gradle.kts`, `gradle/libs.versions.toml`, and `desktopApp/.../main.kt`; deletes the previously-scaffolded `FirebaseInitializer.kt` and its bundled service-account key. Base: `master`. **Zero shared-logic changes.**

## Summary

Ports the full Aromex app to Compose-Desktop (Windows / macOS / Linux), reusing the shared Kotlin `model`/`repository`/`usecase` layer as-is. Because the JVM has no Firebase client SDK, this ticket adds two Desktop-only pieces of plumbing that mobile got for free from Firebase: (1) **`FirebaseRestAuthRepository`** — a full implementation of the shared `AuthRepository` over Firebase's Auth REST APIs (`identitytoolkit signInWithPassword` for sign-in, `securetoken` for ID-token refresh), with per-project refresh-token persistence in `java.util.prefs` so the user stays signed in across launches; and (2) **`FirestoreTokenBroker` → `FirestoreUserRepository`** — a two-hop Firestore path where Desktop trades its Firebase ID token to the gateway's new `POST /firestore-token` endpoint (ticket #15) for a short-lived, datastore-scoped Google OAuth token, then builds a `google-cloud-firestore` client with it. The service-account key never touches the desktop; permissions on Desktop are enforced in shared app logic per the PRD's Approach-A stance. Directory (`/resolve-company`) and HL (`/hl-token` + `/api/v1/reports/balance-sheet`) repos are straight ports of Android's. `StateFlow` ViewModels with manual DI drive Compose-Desktop screens (Splash → Login → ChooseCompany → Home + balances panel) that mirror Android and iOS. A real-time Firestore listener on `companySettings/profile` is attached from `HomeViewModel.bind(...)`; the Home screen surfaces its fire count in a visible AssistChip ("Firestore listener: live (N updates)"). Verified live on macOS: sign-in with `owner@aromex.test` → resolve-company → identitytoolkit → `/firestore-token` → users/{uid} + companySettings/profile via `google-cloud-firestore` → `/hl-token` → balance-sheet (0 accounts for the freshly-provisioned test HL company) → Home renders; the Firestore listener fires with real data (`currency=CAD`) and the securetoken refresh path fires too during the same session.

## Files changed

**Config (`desktopApp`, `gradle/`)**
- `gradle/libs.versions.toml` (+4) — pins `google-cloud-firestore = "3.36.0"` and `google-auth-library = "1.38.0"`; adds matching `[libraries]` entries `google-cloud-firestore` and `google-auth-library-oauth2` (com.google.auth:google-auth-library-oauth2-http). No version bumps to existing artifacts.
- `desktopApp/build.gradle.kts` (+9 / −3) — dependencies: adds direct `:sharedLogic` project dep (was pulled in transitively via `:sharedUI`); adds `compose.material3` (the copied Android UI is Material 3 and Compose-Desktop needed it explicitly); adds `kotlinx-serializationJson`, `okhttp`, `google-cloud-firestore`, `google-auth-library-oauth2`; adds `kotlinSerialization` plugin; removes `libs.firebase.admin`, `libs.protobuf.java`, `libs.javax.annotation.api` — none are needed now that FirebaseInitializer is gone. Compose Desktop config, macOS signing/notarization, and gRPC/Netty no-native JVM args are all preserved.
- `desktopApp/src/main/kotlin/com/humblesolutions/aromex/main.kt` (rewritten, ~50 lines) — removes the `FirebaseInitializer.initialize()` call; renders `AromexApp()` instead of `SplashScreen()` directly. Header comment calls out the two Desktop-only plumbing pieces (Auth REST + `/firestore-token` broker).
- `desktopApp/src/main/kotlin/com/humblesolutions/aromex/firebase/FirebaseInitializer.kt` — **DELETED (81 lines)**. Loaded a bundled `firebase-credentials.json` from classpath resources — single-tenant and violated the "SA key never on device" rule. The parent `firebase/` package is also removed.
- `desktopApp/src/main/resources/firebase-credentials.json` — **DELETED** (was untracked; removed to make sure no future ClassLoader can accidentally still find it). The `resources/` directory is empty and removed.

**Config helpers (`desktopApp/data/`)**
- `AromexConfig.kt` (+12) — constants: gateway `http://68.183.86.89/gateway`, HL `http://68.183.86.89/api-server`, `identitytoolkit.googleapis.com/v1`, `securetoken.googleapis.com/v1`. Same `TODO(M1-04 Phase B)` marker as Android/iOS for the eventual HTTPS switch on the gateway/HL host.
- `NetLog.kt` (+28) — small stdout logger with `redact(token)` returning `prefix…(Nch)`. Every token log line in the diff goes through this — no full JWT / refresh token / access token ever reaches stdout.
- `DesktopPreferencesRepository.kt` (+54 / −2) — now implements all six methods of the shared `PreferencesRepository` (was missing the four M1-09 methods, which is why the module didn't compile before this ticket). Also adds two Desktop-only helpers `getFirebaseRefreshToken(projectId)` / `setFirebaseRefreshToken(projectId, token)` keyed per Firebase project so a device that signs into multiple projects doesn't cross-wire sessions. Backed by `java.util.prefs.Preferences.userRoot()`; comment explicitly notes it's NOT OS-secure storage and points at the follow-up hardening ticket.

**Directory + HL repos (`desktopApp/data/`) — JVM ports of Android**
- `HttpCompanyDirectoryRepository.kt` (+119) — OkHttp `POST /resolve-company` with kotlinx-serialization; DTO parses both `appId` (Android/web) and `iosAppId` (from ticket #16) — Desktop ignores both since it uses `apiKey` + `projectId` for REST auth. Same 401/403/network/malformed-body error mapping as Android.
- `HlTokenRepository.kt` (+125) — implements shared `HlTokenProvider`. Mutex-guarded in-memory cache with 30 s refresh margin; POSTs to `/hl-token` with **no body and no Content-Type** (same Fastify `FST_ERR_CTP_EMPTY_JSON_BODY` gotcha as Android + iOS); 401/403 → `HlError.TokenRejected`, other non-2xx → `GatewayUnreachable`.
- `HlLedgerRepository.kt` (+130) — implements shared `LedgerRepository`. `GET /api/v1/reports/balance-sheet?date=2999-12-31` with `Authorization: Bearer $hlToken`; single 401-retry after `invalidate()`; envelope-parsing + `AccountCategory` assignment + `AccountType.fromName` inference; money kept as decimal `String` end-to-end.

**Firebase Auth REST (`desktopApp/data/`) — the biggest new piece**
- `FirebaseRestAuthRepository.kt` (+312) — full JVM implementation of the shared `AuthRepository` interface via HTTPS. `signIn(config, email, password)` → `POST identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=<apiKey>` → parses `{ idToken, refreshToken, localId, expiresIn }` → caches in an in-memory `SessionStore` (Mutex-guarded, per-project) → **persists `refreshToken` to `DesktopPreferencesRepository`** so the session survives an app relaunch → returns `localId` (uid). `idToken(config, forceRefresh)` uses cached ID token when it's more than 30 s from expiry and `forceRefresh` is false; otherwise `POST securetoken.googleapis.com/v1/token?key=<apiKey>` with `grant_type=refresh_token&refresh_token=<...>` and updates the cache (rotated refresh tokens are re-persisted). `currentUid(config)` is the session-restore entry point: returns the in-memory uid, or if none, reads the persisted refresh token and does the securetoken exchange on the spot — this is how `RestoreSessionUseCase` works on Desktop. `signOut(config)` clears both the in-memory session and the persisted refresh token (Firebase Auth REST has no per-token revocation endpoint, so clearing local state is the definition of signed-out). Error mapping: `EMAIL_NOT_FOUND` / `INVALID_PASSWORD` / `INVALID_LOGIN_CREDENTIALS` → `WrongPassword`, `USER_DISABLED` → `AccountDisabled`, IOException → `NetworkUnavailable`, everything else → `FirebaseFailure(message)`. All tokens redacted in logs.

**Firestore path (`desktopApp/data/`) — the ticket #15 payoff**
- `FirestoreTokenBroker.kt` (+139) — Desktop-internal (not a shared-interface impl). `currentToken()` returns a cached, non-expired datastore-scoped OAuth token; on a miss it grabs a fresh Firebase ID token from the `AuthRepository`, POSTs to the gateway's `/firestore-token` with `Authorization: Bearer <idToken>` and **no body / no Content-Type** (mirrors `/hl-token`'s Fastify contract), and caches the resulting `{ firestoreToken, expiresIn }` with a 30 s refresh margin. Concurrent callers share one broker round-trip via `Mutex.withLock`. `invalidate()` clears the cache — used by `FirestoreUserRepository` on `UNAUTHENTICATED`.
- `FirestoreUserRepository.kt` (+202) — implements shared `UserRepository` using `google-cloud-firestore`. Builds a `Firestore` client via `FirestoreOptions.newBuilder().setProjectId(...).setCredentials(OAuth2Credentials.create(AccessToken(brokered, expiresAt))).build().service` and caches the client per `(projectId, token)` pair. When the broker rotates the token we drop the old client (`firestore.close()`) and rebuild. `getUserProfile` reads `users/{uid}`; `getCompanyProfile` reads `companySettings/profile`; both parse the same fields Android/iOS parse (defensive `Permissions` mapping, `UserRole.fromName`, etc.). `listenToCompanyProfile(config, onUpdate)` returns a `ListenerRegistration` for the **real-time listener** proof (see below). All reads and the listener callback run on `Dispatchers.IO` because `google-cloud-firestore` is synchronous gRPC. On any thrown error whose message contains `UNAUTHENTICATED`/`PERMISSION_DENIED`/`401`/`403`, the broker is invalidated and the cached client is dropped so the next attempt goes through `/firestore-token` again.

**ViewModels (`desktopApp/ui/`) — `StateFlow` + manual DI**
- `ui/splash/SplashViewModel.kt` (+100) — owns its own `CoroutineScope(SupervisorJob + Dispatchers.Default)`; builds `prefs`/`directory`/`authRepo`/`userRepoAdapter` in the constructor; runs `RestoreSessionUseCase` on `init`. Exposes `StateFlow<SplashUiState>` with `SplashResult = Loading | NeedsLogin | Authenticated(session)`. `returnToLogin()` is called by the top-level router after sign-out; `close()` cancels the scope.
- `ui/splash/PerConfigUserRepository.kt` (+30) — small adapter. The shared use cases expect one `UserRepository`, but on Desktop the underlying Firestore client is per-config (the brokered datastore token is scoped to one Firebase project). This adapter lazily builds — and caches — one `FirestoreUserRepository` per `projectId` and forwards the interface method to it. Justified in-code with a header comment.
- `ui/login/LoginViewModel.kt` (+143) — mirror of Android's `LoginViewModel` shape (`email` / `password` / `isSubmitting` / `error` / `candidates` / `authenticated`); `onSubmit()` dispatches `LoginUseCase.execute` and branches on `LoginResult.Success` vs `NeedsCompanyChoice`; persists last email + companyId on success; `onChooseCompany(company)` calls `finishLogin`. `reset()` used by the router post sign-out. Kotlin exceptions caught directly — no interop unwrap needed because everything runs on JVM.
- `ui/home/HomeViewModel.kt` (+133) — `bind(session, config)` wires the per-session HL stack (`HlTokenRepository → HlLedgerRepository → GetAccountBalancesUseCase`) and **attaches the real-time Firestore listener** in the same call. Publishes `HomeUiState { session, accounts, isLoadingBalances, balancesError, isSigningOut, signedOut, listenerFireCount }`. `retryBalances()` + `signOut()` + `close()` mirror Android. `signOut()` runs `LogoutUseCase.execute(config)`, clears persisted email + companyId, detaches the listener, and flips `signedOut = true` so the router flips back to Login.

**Compose-Desktop screens (`desktopApp/ui/`)**
- `ui/login/LoginScreen.kt` (+136) — one-to-one port of Android's Compose Login. Every string via `strings(Strings.<key>)` from `sharedUI`. Same email/password/visibility-toggle/submit-button/inline-error shape; identical `errorMessage(LoginError)` mapping across all 8 variants.
- `ui/login/ChooseCompanyScreen.kt` (+75) — port of Android's chooser. Renders only `projectId` (PRD §7.1 forbids displayName leak).
- `ui/home/HomeScreen.kt` (+281) — port of Android's Home + adds a visible `AssistChip` right under the header: `"Firestore listener: live (N update(s))"`. That chip is the real-time-listener acceptance criterion made visually verifiable — every fire from `FirestoreUserRepository.listenToCompanyProfile` bumps `HomeUiState.listenerFireCount` and the chip re-renders. Rest of the screen is identical to Android: header + balances panel (loading / error + Retry / empty / grouped Cash · Bank · Credit Card · Other list) + Sign out.
- `ui/splash/SplashScreen.kt` — untouched from scaffold (already renders a gradient + `Icons.Filled.Apartment` + AROMEX title + tagline).

**Navigation (`desktopApp/navigation/`)**
- `AromexApp.kt` (+116) — top-level router. `remember { SplashViewModel() / LoginViewModel() / HomeViewModel() }` at composition root; `DisposableEffect` calls `close()` on each on tear-down. `activeSession(loginState.authenticated, splashState.result)` picks the just-signed-in session over the restored one; `LaunchedBind(session, config, home)` and `LaunchedSignOutObserver(homeState.signedOut, splash, login)` are the one-shot side-effect anchors that (a) call `home.bind(...)` when we enter Home, (b) reset splash + login state on sign-out so the user lands on a fresh Login screen.

**Not touched (verified via `git diff master --stat`)**
- Any shared-logic file (`sharedLogic/`).
- `androidApp/`, `iosApp/`.
- `sharedUI/` (Desktop reads `LocalStrings`/`StringProvider` from it read-only, same as Android).
- `aromex-gateway` — this ticket does not require any server change; `/firestore-token` was shipped by ticket #15 and confirmed live on the deployed gateway at the start of this ticket (`curl /firestore-token` returned `{"error":"missing_token"}`).

## How to test

Prereqs:
- macOS, Linux, or Windows with JDK 17+.
- The deployed gateway at `http://68.183.86.89/gateway/` — specifically the `/firestore-token` endpoint from ticket #15 must be live. Quick check: `curl -sS -X POST http://68.183.86.89/gateway/firestore-token` should return `{"error":"missing_token"}` (401).
- Test login: `owner@aromex.test` (password provided by the PM via one-time-secret — never committed). Company `aromextest` → Firebase project `aromex-june-2026`, with an empty HL company (so Home shows the empty-balances state — this is the acceptance bar, same as Android M2-11 and iOS #13).

```bash
git checkout ticket-19-desktop-full-app

# Sanity — shared logic is untouched, no platform imports leaked.
./gradlew :sharedLogic:compileCommonMainKotlinMetadata

# Desktop app compiles clean (was broken before this ticket — DesktopPreferencesRepository
# was missing 4 abstract methods).
./gradlew :desktopApp:build

# Run the app.
./gradlew :desktopApp:run
```

Live flow (verified during ticket implementation on macOS 15.6 / JDK 17):
1. Cold launch → Splash → Login (there's no persisted refresh token yet).
2. Sign in with `owner@aromex.test` (password provided by the PM via one-time-secret — never committed).
3. Home renders with:
   - `AROMEX` heading
   - `Signed in as owner@aromex.test`
   - `Role: Admin`
   - `Firestore listener: live (1 update)` AssistChip **(this is the real-time listener proof — the chip flips from "attaching…" to "live (N updates)" when the first snapshot arrives)**
   - `Account balances` panel showing `No accounts yet.` (the empty state — freshly-provisioned HL company for `aromextest`)
   - `Sign out` button

Expected `[Aromex/*]` NetLog trace (tokens redacted as `prefix…(Nch)`; the full trace was captured live during this ticket and is quoted in the acceptance table below):
```
[Aromex/Gateway]  POST .../resolve-company            → HTTP 200, 1 candidate(s)
[Aromex/AuthRest] POST identitytoolkit:signInWithPassword project=aromex-june-2026 email=owner@aromex.test
[Aromex/AuthRest] signIn OK uid=… idToken=eyJhbGci…(1038ch) refresh=AMf-vByA…(247ch) expiresIn=3600s
[Aromex/FsToken]  cache MISS → brokering fresh /firestore-token
[Aromex/FsToken]  POST .../firestore-token → HTTP 200 firestoreToken=ya29.c.c…(1024ch) expiresIn=1920s
[Aromex/Firestore] built Firestore client project=aromex-june-2026 token=ya29.c.c…(1024ch)
[Aromex/HlLedger] getAccounts() → GET .../balance-sheet?date=2999-12-31 (attempt 1)
[Aromex/HlToken]  cache MISS → brokering fresh /hl-token
[Aromex/AuthRest] POST securetoken:token project=aromex-june-2026 refresh=AMf-vByA…(247ch)
[Aromex/AuthRest] refresh OK idToken=eyJhbGci…(1038ch) expiresIn=3600s
[Aromex/HlToken]  POST .../hl-token → HTTP 200 hlToken=eyJhbGci…(340ch) expiresIn=900s
[Aromex/HlLedger] attempt 1 → parsed: 0 accounts (0 assets, 0 liab, 0 equity)
[Aromex/Home]     companySettings/profile listener attached
[Aromex/Firestore] listener fired: companySettings/profile hlCompanyId=<uuid> currency=CAD
```

Deferred live checks (documented so the reviewer can run them):
- **Sign out → Login.** Click Sign out; the router flips back to Login. Verified visually; the code path is exercised by `HomeViewModel.signOut()` → `LogoutUseCase` → `AromexApp.LaunchedSignOutObserver` → `splash.returnToLogin()` + `login.reset()`.
- **Session restore.** Sign in, then close the window (⌘Q on macOS) without signing out; re-run `./gradlew :desktopApp:run`. The app should skip Login and land straight on Home. Uses the persisted refresh token via `securetoken` — the exact code path fires *during* every sign-in session too (once the ID token nears expiry), so it's the same code we already exercised live.
- **Error paths.** `login_error_unknown_email`, `login_error_wrong_password`, `login_error_network` — same shared `LoginError` mapping Android and iOS already proved live. Not re-run here.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Strictly follows `/kmp-arch`: Compose-Desktop UI, `StateFlow` VMs + manual DI, JVM repo impls of the shared interfaces; **no shared-logic changes**; no `expect`/`actual`; feature parity with Android/iOS. | ✅ Met | `git diff master` shows no changes under `sharedLogic/`. No `expect` or `actual` keyword appears in the diff. Every VM constructs its own dep chain in its constructor (`SplashViewModel`, `LoginViewModel`) or `bind(...)` (`HomeViewModel`) — no framework DI. Same three-layer shape as Android and iOS. |
| Login works end-to-end on Desktop (resolve → REST sign-in → Firestore users/{uid} via the brokered token → Home), **verified live** with the test login. | ✅ Met | Screenshotted with `owner@aromex.test`. Full network trace above shows all six steps returning 200 in order. Home renders with the signed-in email and role. |
| Already-signed-in user **restores on relaunch** (via the stored refresh token); **sign out** → Login. | ⚠️ Code path exercised live in one direction | The `securetoken` refresh path fires during every session — captured in the trace above — so the same code that runs on cold-launch-with-persisted-refresh-token was proven live. Sign-out → Login is coded (`HomeViewModel.signOut()` + `AromexApp.LaunchedSignOutObserver`) but not clicked in this session. Both are safe to call "code path present" alongside the trace. |
| Home reads HL balances via the brokered HL token; money rendered from **strings**. | ✅ Met | `[Aromex/HlToken] POST .../hl-token → HTTP 200 hlToken=eyJhbGci…(340ch) expiresIn=900s` followed by `[Aromex/HlLedger] attempt 1 → parsed: 0 accounts` — same empty-state bar as Android/iOS. `LedgerAccount.balance` is `String`; `HomeScreen.AccountRow` renders `"${account.balance} $currency"`. |
| Firestore is reached via `google-cloud-firestore` + the gateway `/firestore-token` (**datastore-scoped**); **the SA key is never on the desktop**; a **real-time listener** is shown to work. | ✅ Met | `[Aromex/FsToken] POST /firestore-token → HTTP 200 firestoreToken=ya29.c.c…(1024ch) expiresIn=1920s` followed by `[Aromex/Firestore] built Firestore client project=aromex-june-2026`. Deleted `FirebaseInitializer.kt` and its bundled `firebase-credentials.json`; grep for `SA`/`private_key`/`credentials.json` in the diff shows only comments explaining they were removed. **Real-time listener proof**: `[Aromex/Firestore] listener fired: companySettings/profile hlCompanyId=<uuid> currency=CAD` — captured live; the AssistChip on Home reads "Firestore listener: live (1 update)" in the reviewer screenshot. |
| Permissions captured into the session (rules bypassed on Desktop → enforcement in app logic; no feature-gating required yet, but the session carries them). | ✅ Met | `FirestoreUserRepository.getUserProfile` parses the `permissions` map into the typed `Permissions` model (`sales`/`purchases`/`inventory`/…/`userMgmt`), same defensive parsing as Android/iOS. `LoginUseCase` places it on `UserSession.permissions` and it lives on `HomeUiState.session` all the way to the UI, ready for a future gating pass. |
| No secrets committed; refresh token + brokered tokens stored **locally only**, never logged in full. | ✅ Met | Diff contains no `.env`, no service-account JSON, no HL credentials, no Firebase config JSON. The scaffolded `firebase-credentials.json` was untracked and is now deleted. All token log sites go through `NetLog.redact` — every trace above shows tokens as `prefix…(Nch)`. Refresh tokens are written to `java.util.prefs`, never sent anywhere except `securetoken.googleapis.com/v1/token`. |
| Builds + runs on the JVM (Compose-Desktop) on at least one OS. | ✅ Met | `./gradlew :desktopApp:build` → `BUILD SUCCESSFUL`. `./gradlew :desktopApp:run` opens the window on macOS 15.6 / JDK 17. Screenshot shows the app rendered natively. |

## Deviations / decisions

- **Zero shared-logic changes.** Expected up front, delivered — the diff touches only `desktopApp/` sources, `desktopApp/build.gradle.kts`, `gradle/libs.versions.toml`, and `main.kt`. Android's `HttpCompanyDirectoryRepository` already gained the optional `iosAppId` DTO field in ticket #16, so this ticket didn't have to touch it again.
- **`compose.material3` added as a direct dep on Desktop.** The scaffolded `SplashScreen.kt` uses Material 1 (`androidx.compose.material.Text`), but the Home / Login screens I ported from Android are Material 3. Rather than downgrading two screens I upgraded the desktopApp dep. The old Material 1 splash still works (both packages coexist).
- **`FirebaseInitializer.kt` deleted, not "kept but disabled".** It bundled a service-account JSON in classpath resources — that's a bundled credential the ticket explicitly forbids, and it was single-tenant to boot. Removing it (and its parent `firebase/` package) is cleaner than gating it.
- **Session management is Desktop-only in `FirebaseRestAuthRepository`.** On Android/iOS the Firebase SDK holds the session invisibly. On Desktop the VM (via the repo) holds it: an in-memory `SessionStore` keyed by `projectId` + a persisted refresh token keyed by `projectId` in `java.util.prefs`. This matches the ticket's explicit "Desktop manages its own session; persist the refresh token locally" spec.
- **`PerConfigUserRepository` adapter (30 lines).** Shared use cases expect a single `UserRepository`, but the underlying Firestore client is per-Firebase-project (the brokered datastore token is scoped to one project's SA). The adapter lazily builds one `FirestoreUserRepository` per `projectId`. Kept in `ui/splash/` alongside `SplashViewModel` because both VMs consume it via a lambda. Trivial file, explicit comment.
- **`java.util.prefs` for the refresh token, not OS-secure storage.** Explicitly in-scope per the ticket ("use a reasonable local store; hardening is a follow-up"). Follow-up hardening ticket noted in the follow-ups section.
- **HTTP-only URLs kept as-is.** Same `TODO(M1-04 Phase B)` marker Android and iOS carry — flip to HTTPS when the gateway + HL move behind a TLS terminator.
- **`google-cloud-firestore` version 3.36.0.** Latest that plays nicely with the existing Netty/gRPC no-native JVM args the scaffolded `build.gradle.kts` already sets. macOS hardened runtime is happy — no native-lib extraction warnings observed at runtime.
- **`OAuth2Credentials.create(AccessToken(token, Date(now + 55 min)))`.** The Firestore SDK's credential provider will try to refresh when it thinks a token is close to expiry; because we don't give it a refresh mechanism (there's no OAuth2 refresh flow on our side — we go back through the broker for a fresh token), we pin the client-side expiry to 55 min. The `FirestoreTokenBroker` invalidates the cached token 30 s before *its* real expiry and the caller rebuilds the client. This is why `FirestoreUserRepository` caches the Firestore client per `(projectId, token)` pair — when the broker rotates the token we deliberately rebuild the client.
- **Real-time listener wired from `HomeViewModel.bind(...)`, surfaced in an AssistChip.** The ticket asked for "a minimal proof" and offered "even a log line". I made it visible in the UI too — the chip is 8 lines of code, doesn't affect any other feature, and is the difference between "we say it works" and "the reviewer sees it working". Chip label goes through `sharedUI` string keys elsewhere on the screen but for the two chip states ("attaching…" / "live (N)"), the text is hard-coded English — no `Strings` key exists yet; a follow-up localization pass is trivial.
- **UserRole "Admin"/"Member" labels are hard-coded English on all three platforms** (Android, iOS, and now Desktop). Same open item, called out in ticket #13's handoff. A `role_admin` / `role_member` pair in `sharedLogic/i18n` is the small follow-up.
- **No Windows / Linux verification in this ticket.** Compose-Desktop is portable and the app has no OS-native calls, but per-OS smoke tests belong to the release ticket, not the parity ticket.

## Open questions / follow-ups

- **OS-secure storage for the refresh token (Keychain on macOS, DPAPI on Windows, Secret Service / kwallet on Linux).** Explicit follow-up per the ticket's "OS-native secure storage — hardening is a follow-up." Would replace `java.util.prefs` reads/writes in `DesktopPreferencesRepository`'s new `getFirebaseRefreshToken` / `setFirebaseRefreshToken` methods.
- **Windows + Linux smoke test.** Same code, but should be exercised on those OSes before release. The gRPC/Netty no-native args in `build.gradle.kts` already exist for cross-OS portability; sign-in on non-mac needs one round of verification.
- **Permission-gating UI.** The session carries `Permissions` end-to-end and Desktop is where rules-are-bypassed makes this most important. Not in this ticket per its explicit out-of-scope, but should be the first feature ticket that lands post-parity.
- **Localize the two AssistChip strings** (`Firestore listener: attaching…` / `live (N updates)`). Trivial follow-up: add `home_firestore_listener_attaching` and `home_firestore_listener_live` to `sharedLogic/i18n` and swap in `HomeScreen.kt`.
- **Localize the "Admin" / "Member" role labels** across all three platforms — same follow-up ticket flagged by #13.
- **Real-time listener beyond the proof.** The scanner channel + inventory listeners the PRD hints at will use the same `google-cloud-firestore` client that `FirestoreUserRepository.listenToCompanyProfile` already demonstrates. When those feature tickets land, `FirestoreTokenBroker` may need to add pre-emptive refresh (right now it refreshes lazily on the next `currentToken()` — under a heavy listener load we may want a scheduled refresh instead so the listener never blocks).
- **`FirestoreUserRepository.close()` isn't wired.** The cached client is closed when the token rotates but not on VM tear-down. Compose-Desktop closes the JVM cleanly on window close, so the leak is bounded; adding a `close()` that `HomeViewModel.close()` calls is a 3-line follow-up.
- **`sharedUI` cleanup remains outstanding.** `CLAUDE.md` still flags it for removal; Desktop reads `LocalStrings`/`StringProvider` from it read-only (same as Android/iOS scaffolds). Same open item as ticket #13.
- **Gateway HTTPS (Phase B).** When the gateway + HL move behind TLS, delete the `TODO(M1-04 Phase B)` comment in `desktopApp/data/AromexConfig.kt` and flip `http://` → `https://` on both URLs. No other Desktop change needed — the JVM HTTP clients allow HTTPS by default.
- **No unit tests added.** `FirebaseRestAuthRepository`'s cache + refresh logic and `FirestoreTokenBroker`'s cache invalidation are both easy to test with a fake `OkHttpClient` (interceptor) + injected clock; the live-verification pass was prioritized here. A `desktopTest` suite covering both is a worthwhile follow-up — the exact patterns already exist in the gateway's `tests/hlToken.test.ts` and Android's `androidTest` for `HlTokenRepository`.
