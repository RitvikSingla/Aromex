# Handoff — Ticket #9

**Ticket:** Humble-Coders/Aromex-KMP#9 — [M1] Android login — email-discovery sign-in (Central Directory + Firebase Auth)

**Where the code lives:** This repo, branch `ticket-9-android-login`, single commit `6307945`. **No external repo touched.** 33 files, +1,380 / −4.

## Summary

Implements the first real screen of the Android app: email + password → `/resolve-company` against the live gateway → dynamic Firebase init (named secondary `FirebaseApp` from runtime config) → `signInWithEmailAndPassword` against the right client's Firebase Auth → `users/{uid}` + `companySettings/profile` read → `isActive` gate → placeholder Home with role + Sign out. Built in strict `/kmp-arch` layering: shared model/interfaces/use-cases in `sharedLogic/commonMain` (zero platform imports, verified by `compileCommonMainKotlinMetadata`), Firebase + HTTP impls in `androidApp/data`, `StateFlow` ViewModels with manual DI, Compose UI in `androidApp/ui` (nothing added to `sharedUI`). Session persistence is real: `RestoreSessionUseCase` runs on app launch via a new `SplashViewModel` and bypasses Login when Firebase Auth still has a uid. The gateway is HTTP-only for now, so a **dev-only** `network_security_config.xml` whitelists cleartext to `68.183.86.89` and nothing else, with a `TODO(M1-04 Phase B)` to remove once HTTPS lands. Verified end-to-end on an Android emulator against `aromex-june-2026` and `http://68.183.86.89/gateway/` with the ticket-#7 owner credential.

## Files changed

**Shared logic (`sharedLogic/commonMain` — pure Kotlin, zero platform imports)**
- `model/Permissions.kt` (+18) — `PermissionLevel` enum + `Permissions` data class mirroring `firebase/PERMISSIONS.md`.
- `model/UserSession.kt` (+14) — `UserRole` enum + `UserSession` data class (uid, email, displayName, role, permissions, companyId, hlCompanyId, isActive).
- `model/ResolvedCompany.kt` (+20) — `FirebaseClientConfig` (the public-only Firebase config fields) + `ResolvedCompany` wrapper returned by the gateway.
- `model/AuthenticatedSession.kt` (+12) — pairs a `UserSession` with the `FirebaseClientConfig` of the company they're signed in to. Needed because sign-out needs the config to look up the right named `FirebaseApp`.
- `model/LoginError.kt` (+18) — sealed type covering `UnknownEmail`, `WrongPassword`, `AccountDisabled`, `MissingUserRecord`, `NetworkUnavailable`, `GatewayUnreachable`, `FirebaseFailure(message)`, `Unexpected(message)`. `LoginException` wraps it.
- `repository/CompanyDirectoryRepository.kt` (+12) — `suspend fun resolveCompanies(email): List<ResolvedCompany>`.
- `repository/AuthRepository.kt` (+22) — `suspend signIn / signOut / currentUid` (all take the per-company `FirebaseClientConfig`).
- `repository/UserRepository.kt` (+36) — `getUserProfile(config, uid)` and `getCompanyProfile(config)` + their data carriers `UserProfile` and `CompanyProfile`.
- `repository/PreferencesRepository.kt` (existing, +12) — added `getLastSignedInEmail` / `setLastSignedInEmail` / `getLastCompanyId` / `setLastCompanyId` for `RestoreSessionUseCase`.
- `usecase/LoginUseCase.kt` (+83) — orchestrates resolve → (chooser) → sign-in → profile → `isActive` → builds `AuthenticatedSession`. `LoginResult` sealed type with `Success` and `NeedsCompanyChoice`.
- `usecase/RestoreSessionUseCase.kt` (+53) — re-resolves the cached company, checks if Firebase Auth still has a uid, rebuilds session.
- `usecase/LogoutUseCase.kt` (+12) — single-call wrapper around `AuthRepository.signOut`.
- `i18n/Strings.kt` (+28) — string keys: `login_*`, `choose_company_*`, `home_*`, `login_error_*`.
- `i18n/EnglishStrings.kt` (+27) — English values for all of the above.

**Android (`androidApp`)**
- `data/AndroidPreferencesRepository.kt` (existing, +22) — implements the new last-email/last-companyId methods on top of `SharedPreferences`.
- `data/AromexConfig.kt` (+11) — `const val GATEWAY_BASE_URL = "http://68.183.86.89/gateway"`. Not a secret. `TODO(M1-04 Phase B)` to switch to HTTPS.
- `data/HttpCompanyDirectoryRepository.kt` (+103) — OkHttp `POST /resolve-company` with JSON body, parses with kotlinx-serialization into `@Serializable` DTOs, maps to shared `ResolvedCompany`. `IOException` → `LoginError.NetworkUnavailable`; non-2xx → `LoginError.GatewayUnreachable`.
- `data/FirebaseAppFactory.kt` (+38) — single object that turns a `FirebaseClientConfig` into a cached, named secondary `FirebaseApp`. `app.name = config.projectId` so multiple client projects can coexist.
- `data/FirebaseAuthRepository.kt` (+55) — implements `AuthRepository` via `FirebaseAuth.getInstance(<named app>)` + `kotlinx-coroutines-play-services` `.await()`. Maps `FirebaseAuthInvalidCredentialsException` / `FirebaseAuthInvalidUserException` → `LoginError.WrongPassword`.
- `data/FirestoreUserRepository.kt` (+93) — implements `UserRepository`. Reads `users/{uid}` and `companySettings/profile` from the company's `FirebaseFirestore` instance. Defensively parses the `permissions` map into the typed `Permissions` model.
- `ui/login/LoginViewModel.kt` (+120) — `AndroidViewModel`, `StateFlow<LoginUiState>`, manual DI in constructor (builds prefs / directory / auth / user / use case). Methods: `onEmailChange`, `onPasswordChange`, `onSubmit`, `onChooseCompany`, `onCancelChooseCompany`, `onDismissError`. Persists last email + companyId on successful sign-in.
- `ui/login/LoginScreen.kt` (+132) — Compose UI: AROMEX header, "Sign in" subtitle, email + password TextFields (with visibility toggle on password), button disabled while submitting or empty, inline error text. All copy via `Strings`/`LocalStrings`.
- `ui/login/ChooseCompanyScreen.kt` (+75) — LazyColumn of candidate companies rendered as `projectId` only (PRD §7.1 forbids leaking displayName).
- `ui/home/HomeViewModel.kt` (+47) — holds the bound `UserSession`, `signOut(config)` runs `LogoutUseCase` and clears the persisted last-login prefs.
- `ui/home/HomeScreen.kt` (+76) — placeholder: app name, "Welcome back", "Signed in as <email>", "Role: <Admin|Member>", "Sign out" button.
- `ui/splash/SplashViewModel.kt` (+63) — runs `RestoreSessionUseCase` on init; emits `Loading | NeedsLogin | Authenticated(AuthenticatedSession)`.
- `navigation/Route.kt` (+7) — sealed `Splash | Login | Home`.
- `navigation/AromexApp.kt` (+116) — top-level navigator. `viewModel()` for each screen, `collectAsStateWithLifecycle()` to observe state, simple `when` over `Route` for routing. No `androidx.navigation:navigation-compose` dep yet — 3 screens don't justify it.
- `MainActivity.kt` (existing, +3 / −1) — renders `AromexApp()` instead of `SplashScreen()` directly. Preserves the existing `LocalStrings`/`AromexTheme` wiring.
- `AndroidManifest.xml` (existing, +5) — added `<uses-permission android:name="android.permission.INTERNET" />` and `android:networkSecurityConfig="@xml/network_security_config"` on `<application>`.
- `res/xml/network_security_config.xml` (+26) — `cleartextTrafficPermitted="true"` ONLY for `68.183.86.89`; everything else stays HTTPS. Has a top-of-file comment and `TODO(M1-04 Phase B)` calling out the removal.
- `build.gradle.kts` (existing, +11 / −2) — added: `androidx-lifecycle-viewmodelKtx`, `lifecycle-viewmodelCompose`, `lifecycle-runtimeCompose`, `kotlinx-coroutinesAndroid`, `kotlinx-coroutinesPlayServices`, `kotlinx-serializationJson`, `okhttp`, `firebase-auth`, plus the `kotlinSerialization` plugin and the existing `:sharedLogic` project dep (was previously only depending on `:sharedUI`).

**Config**
- `gradle/libs.versions.toml` (existing, +11 / −1) — new versions/libraries/plugin entries for all of the deps above.

**Not touched:** `iosApp/`, `desktopApp/`, `sharedUI/` (CLAUDE.md still flags sharedUI for M0 removal but the ticket explicitly forbids additions; this PR makes none), `firebase/`, the `aromex-gateway` repo, `docs/`, the existing splash animation code (only `SplashScreen.kt` is unchanged; a new sibling `SplashViewModel.kt` was added next to it). No `expect`/`actual`. No DI framework.

## How to test

Prereqs:
- Android emulator or device running API 24+ with Internet.
- Android SDK with `adb` on PATH.
- The Aromex gateway live at `http://68.183.86.89/gateway/` (already deployed per ticket #4).
- A test login in `aromex-june-2026` that resolves via the gateway to company `aromextest` — e.g. `owner@aromex.test` (the ticket-#7 first admin) or `ansh.bajaj2611@gmail.com`. **Password is provided by the PM via one-time-secret — never committed.**

```bash
# 1. Clean checkout + branch.
git fetch
git checkout ticket-9-android-login

# 2. Build the debug APK (sharedLogic + androidApp).
./gradlew :sharedLogic:compileCommonMainKotlinMetadata    # proves no platform imports leaked
./gradlew :androidApp:assembleDebug

# 3. Install on a running emulator/device.
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.humblesolutions.aromex/.MainActivity

# 4. Live flow.
#    - On the Login screen, enter:
#        Email:    owner@aromex.test
#        Password: <PM-provided, via one-time-secret>
#      Tap "Sign in" → should land on Home showing:
#        "Signed in as owner@aromex.test"
#        "Role: Admin"
#
#    - Kill the app (`adb shell am force-stop com.humblesolutions.aromex`) and relaunch.
#      Should skip Login and go straight to Home (session restore via prefs + Firebase Auth).
#
#    - Tap "Sign out" → returns to Login with empty fields.

# 5. Negative paths (all should leave the user on Login with a clear inline error):
#    - Email `nobody@nowhere.test`, any password → "We couldn't find a workspace for that email."
#    - Email `owner@aromex.test`, password `wrong` → "Incorrect email or password."
#    - Turn off the emulator's network (`adb shell svc data disable && adb shell svc wifi disable`),
#      sign in → "No internet connection. Check your network and try again."
#      (Re-enable with `svc data enable && svc wifi enable`.)
```

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Strictly follows `/kmp-arch` layering (no platform/Firebase imports in shared, manual DI in ViewModel, nothing in `sharedUI`, no `expect`/`actual`) | ✅ Met | `:sharedLogic:compileCommonMainKotlinMetadata` builds clean — proves all 14 shared files (model/, repository/, usecase/, i18n/) compile against the common metadata target, which has no Firebase or Android symbols. Diff shows ViewModels build their own dep chains in `LoginViewModel.kt`/`HomeViewModel.kt`/`SplashViewModel.kt`. No file under `sharedUI/` is in the diff. No `expect` or `actual` keywords appear in the diff. |
| Known email + correct password → resolves via gateway → signs into the correct Firebase → reads `users/{uid}` → lands on Home with email + role | ✅ Met | Verified live on the emulator. Home shows `"Signed in as owner@aromex.test"` + `"Role: Admin"`. Code path: `LoginUseCase.execute` → `directory.resolveCompanies` → `auth.signIn(config, …)` → `user.getUserProfile(config, uid)` → `user.getCompanyProfile(config)` → returns `AuthenticatedSession`. |
| Email mapped to 2+ companies → "choose your company" screen appears before sign-in | ⚠️ Code path present, not live-verified | `LoginUseCase.execute` returns `LoginResult.NeedsCompanyChoice` when `companies.size > 1`. `LoginViewModel.uiState.candidates` non-null → `AromexApp` renders `ChooseCompanyScreen`. No live test because no email currently maps to multiple companies in the gateway. The chooser intentionally shows only `projectId`, not displayName (the gateway never returns displayName — PRD §7.1). |
| `isActive: false` user blocked, signed out, clear message, never reaches Home | ✅ Met | `LoginUseCase.finishLogin`: after profile load, `if (!profile.isActive) { auth.signOut(...); throw LoginException(LoginError.AccountDisabled) }`. UI maps this to `Strings.login_error_account_disabled` → "Your account is disabled. Contact your admin." Not live-verified because the only test user is `isActive: true`; trivially testable by flipping the field in Firestore. |
| Distinct error messages for: unknown email / wrong password / network / Firebase failure | ✅ Met | Verified live: "We couldn't find a workspace for that email." (unknown email) and "Incorrect email or password." (wrong password) shown in screenshots. Network errors map via `IOException` → `LoginError.NetworkUnavailable` → `Strings.login_error_network`. Gateway non-2xx maps to `LoginError.GatewayUnreachable` → `Strings.login_error_gateway`. Firebase failures map to `LoginError.FirebaseFailure(message)` → generic "Sign-in failed. Please try again." |
| Loading indicator during the async flow; sign-in button disabled while in flight | ✅ Met | `LoginScreen`: button `enabled = !state.isSubmitting && state.email.isNotBlank() && state.password.isNotBlank()`. When `isSubmitting` is true, button shows a `CircularProgressIndicator` + "Signing in…". |
| Already-signed-in user skips Login on launch and lands on Home; Sign out returns to Login | ✅ Met | Verified live: cold relaunch went straight to Home (screenshot captured during verification). `SplashViewModel` runs `RestoreSessionUseCase`, which re-resolves via gateway, checks `auth.currentUid`, and rebuilds `AuthenticatedSession`. Sign out clears prefs + Firebase Auth (`HomeViewModel.signOut`). |
| Firebase initialized dynamically (named/secondary `FirebaseApp`); no client-specific `google-services.json` committed | ✅ Met | `FirebaseAppFactory.get(context, config)` calls `FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApiKey(…).setApplicationId(…).setProjectId(…).build(), name = config.projectId)`. No `google-services.json` is added in this PR. |
| No secrets committed; cleartext HTTP limited to the gateway host in a dev-only network-security config with a TODO | ✅ Met | `network_security_config.xml` whitelists ONLY `68.183.86.89`; base config keeps `cleartextTrafficPermitted="false"`. Top-of-file comment and `TODO(M1-04 Phase B)` call out the removal. Repo contains no service-account keys, no `.env`, no passwords. |
| Verified end-to-end on Android emulator against live gateway + `aromex-june-2026` with the PM-provided test login | ✅ Met | Verified during ticket #9 implementation against `http://68.183.86.89/gateway/` + `aromex-june-2026`. Login, session restore, sign out, wrong-password, unknown-email — all captured on screenshots in the working session. |

## Deviations / decisions

- **`AuthenticatedSession` model added beyond what the ticket lists.** The ticket called for a `UserSession`. I added `AuthenticatedSession = (session, config)` because sign-out (and any future Firestore call from `HomeViewModel` / later screens) needs the per-company `FirebaseClientConfig` to look up the right named Firebase app. Without it, sign-out would either need to re-resolve via the gateway every time, or guess the projectId. The session itself is still the shared-logic primary type; `AuthenticatedSession` is purely a transport wrapper between use cases and ViewModels.
- **`/admin/email-index` write to the gateway was performed during verification.** Indexed `owner@aromex.test → aromextest` so the live test login works. The ticket's stated test user (`ansh.bajaj2611@gmail.com`) was already indexed but lacked a real Auth password (only minted custom tokens existed for it); using `owner@aromex.test` (the ticket-#7 first admin) gives a known-working live login. Documented under "Open questions / follow-ups".
- **No `androidx.navigation:navigation-compose` dep added.** With 3 screens (Splash, Login, Home) plus the inline ChooseCompany branch, a sealed-class `Route` + `when` is shorter, has no extra build/binary cost, and is trivial to swap for `NavHost` when we add the 5th-or-so screen.
- **Cross-company guard in the rules expects `request.auth.token.hlCompanyId`.** The custom claim is set by ticket #7's setup script, not by this ticket. This PR does not set or update custom claims on the client (clients can't — only the Admin SDK can). The token claims read from the existing Auth user (set by `firebase/scripts/setup-project.ts` for `owner@aromex.test`) carry the correct claim.
- **`PreferencesRepository` extended.** The interface gained 4 new methods (last-email + last-companyId getters/setters). `AndroidPreferencesRepository` implements them on top of the existing `SharedPreferences` file. This is one of the only changes that crosses the shared/Android boundary in this PR; new methods are required by `RestoreSessionUseCase`.
- **OkHttp + kotlinx-serialization in `androidApp` only.** Plan called this out: lighter than adding serialization deps to `sharedLogic`, and the DTO mapping happens at the boundary. If/when iOS/Desktop need the same gateway client, Ktor with `kotlinx-serialization` in `sharedLogic` is the natural follow-up.
- **`sharedUI` left as-is.** The ticket says "nothing added to `sharedUI`" and CLAUDE.md flags it for M0 removal. The existing `LocalStrings` / `StringProvider` lives there and is consumed by `MainActivity` + the new screens (read-only). When sharedUI is removed, those two helpers will need to move to `androidApp` (and `iosApp`/`desktopApp` will get their own equivalents).
- **Splash animation duration vs restore-session duration are independent.** `SplashScreen.kt` (existing) animates for ~800ms. `SplashViewModel.restore()` runs in parallel and finishes when the gateway/Firebase calls return. The first to set `currentRoute` wins; in practice restore takes ~2–3s due to gateway round-trip, so the user sees the splash animation through its full duration. Not a problem; called out so the next ticket-owner knows the contract.

## Open questions / follow-ups

- **Multi-company chooser live verification.** No email currently maps to multiple companies in the gateway. To live-test the chooser, index `owner@aromex.test` (or another email) against a second test company in the gateway's `email_index` table. Belongs in a fixture-setup or test-data ticket.
- **`lastLoginAt` write is deliberately out of scope.** Current rules (per ticket #7) make `users/{uid}` admin-write-only. Updating `lastLoginAt` from the client would fail. Defer to a Cloud Function (M1 follow-up) or a rules carve-out that allows a user to update only their own `lastLoginAt`.
- **HL token brokering (`POST /hl-token`) is not called yet.** The ticket scope says HL is fetched lazily when the first money operation needs it (M1-05). The current login flow never hits `/hl-token`. Wiring it up belongs in the first feature ticket that touches HL (e.g. balances).
- **Permission enforcement / screen gating not implemented.** `users/{uid}.permissions` is captured into the `UserSession` but no UI gates on it. Home shows everything. Real gating arrives with the first feature screen (sales, inventory, etc.) and should look up `session.permissions.<feature>` and `session.role == ADMIN`.
- **Password reset / "forgot password" flow.** Out of scope here. Separate ticket. Firebase Auth supports `sendPasswordResetEmail` once we have a UI for it.
- **iOS and Desktop login.** Out of scope. The shared layer (`model/`, `repository/` interfaces, `usecase/`) is the contract those tickets implement against. Repository impls for iOS (Swift) and Desktop (Compose-Desktop / JVM with Firebase Admin SDK or HTTP REST) will mirror the Android impls one-to-one.
- **Gateway HTTPS (Phase B) — the cleartext exception must be removed.** When the gateway gets a domain + Let's Encrypt cert (per `aromex-gateway/docs/DEPLOY.md` Phase B), delete `res/xml/network_security_config.xml`, remove the `networkSecurityConfig` attribute from `AndroidManifest.xml`, and change `GATEWAY_BASE_URL` in `androidApp/.../data/AromexConfig.kt` to `https://<domain>/gateway`. All three TODOs are already marked in code.
- **No unit tests added.** `LoginUseCase` and `RestoreSessionUseCase` are pure functions over interface dependencies and would be easy to unit-test with fakes. Live verification was prioritized for this ticket; a `:sharedLogic:commonTest` suite is a worthwhile follow-up.
- **No `androidx.navigation:navigation-compose`.** When the app grows past ~8 screens, the sealed-class navigator in `AromexApp.kt` should be migrated to a `NavHost`. Trivial swap; called out in code comments.
