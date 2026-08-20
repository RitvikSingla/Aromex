# Handoff — Ticket #13

**Ticket:** Humble-Coders/Aromex-KMP#13 — [Parity] iOS — full app: email-discovery login + HL balances (SwiftUI, per /kmp-arch)

**Where the code lives:** This repo, branch `ticket-13-ios-full-app` (uncommitted at time of report; the `/handoff` step commits the report and the changes). Base: `master`. Total code-only diff (excluding Pods regeneration + xcodeproj auto-touches): ~1,320 lines of new Swift, plus 24 lines of shared-logic edits and 3 lines on Android.

## Summary

Adds the iOS platform layer for the two features Android already ships (email-discovery login → per-company Firebase → Firestore user profile → Home; brokered HL token → balance-sheet read → grouped balances panel). All shared Kotlin model/repository/use-case code is consumed as-is from Swift via the existing `sharedLogic` CocoaPod. Swift implementations of each repository interface use native iOS SDKs (`URLSession`, `FirebaseAuth`, `FirebaseFirestore`, `UserDefaults`); ViewModels are `@MainActor ObservableObject` with manual DI; UI is native SwiftUI (Login, ChooseCompany, Home); all user-facing text goes through `LocalizationManager.t(Strings.shared.<key>)` from the shared i18n dictionary — no hardcoded strings. Firebase is initialized dynamically per company via a cached named `FirebaseApp` factory; no `GoogleService-Info.plist` remains bundled. Two justified shared-logic tweaks were needed: (1) `@Throws` annotations on the use cases so Kotlin exceptions bridge to Swift `NSError`, and (2) a new optional `iosApplicationId` field on `FirebaseClientConfig` because Firebase iOS SDK's `FirebaseOptions` validates the platform tag (`1:...:ios:...`) and rejects the Android app IDs the gateway currently returns. To satisfy that on the wire, the gateway DB row for `aromextest` was updated (via `POST /admin/companies`) to include `iosAppId` inside its passthrough JSON `firebaseConfig` blob — no gateway code change was needed (the zod schema is `.passthrough()`).

## Files changed

**Shared logic (`sharedLogic/commonMain` — pure Kotlin, zero platform imports)**
- `model/ResolvedCompany.kt` (+4) — adds `FirebaseClientConfig.iosApplicationId: String? = null`. Comment explains why (Firebase iOS SDK rejects `1:...:android:...`) and that Android ignores it.
- `usecase/LoginUseCase.kt` (+3) — `@Throws(LoginException::class, CancellationException::class)` on `execute` and `finishLogin` so Swift receives them as `NSError` instead of the Kotlin/Native runtime treating them as fatal (docs on generated header: "Other uncaught Kotlin exceptions are fatal").
- `usecase/LogoutUseCase.kt` (+3) — `@Throws(LoginException::class, CancellationException::class)` on `execute` (auth.signOut may throw).
- `usecase/GetAccountBalancesUseCase.kt` (+3) — `@Throws(HlException::class, CancellationException::class)` on `execute`.
- `usecase/RestoreSessionUseCase.kt` (+8 / −3) — `@Throws(CancellationException::class)` on `execute`, plus the three previously-uncaught repo calls (`auth.currentUid`, `user.getUserProfile`, `user.getCompanyProfile`, `auth.signOut`) are now wrapped in `runCatching {}` so restore stays best-effort and never throws to the caller. Preserves the existing "return null on any failure" contract from Android.

**Android (`androidApp`) — additive only, backward-compatible**
- `data/HttpCompanyDirectoryRepository.kt` (+3) — DTO gains `iosAppId: String? = null`, mapped to `FirebaseClientConfig.iosApplicationId`. Android does not use this field; the change exists so Android's parse doesn't break when the gateway now returns it.

**iOS — new**
- `iosApp/iosApp/config/AromexConfig.swift` (+8) — `gatewayBaseURL` = `http://68.183.86.89/gateway`, `hlBaseURL` = `http://68.183.86.89/api-server`. `TODO(M1-04 Phase B)` for HTTPS.
- `iosApp/iosApp/repository/FirebaseAppFactory.swift` (+49) — enum with a `[projectId: FirebaseApp]` cache guarded by a serial `DispatchQueue`. Constructs `FirebaseOptions(googleAppID: config.iosApplicationId ?? config.applicationId, gcmSenderID: ...)` then `FirebaseApp.configure(name: projectId, options:)`. Exposes `.auth(for:)` and `.firestore(for:)` helpers using the named app.
- `iosApp/iosApp/repository/HttpCompanyDirectoryRepository.swift` (+86) — `URLSession` `POST /resolve-company`. Decodes the JSON into `ResolvedCompany`; the private DTO maps wire field `appId` → `applicationId` and (new) `iosAppId` → `iosApplicationId`. Maps `URLSession.data(for:)` throws → `LoginError.NetworkUnavailable`, non-2xx / decode failure → `LoginError.GatewayUnreachable`. Throws use `.asError()` on the Kotlin exception so it bridges as `NSError`.
- `iosApp/iosApp/repository/UserDefaultsPreferencesRepository.swift` (+53) — implements the 6-method `PreferencesRepository` (language / last email / last companyId) on `UserDefaults.standard`.
- `iosApp/iosApp/repository/FirebaseAuthRepositoryImpl.swift` (+54) — implements `AuthRepository`. `signIn` uses `Auth.auth(app:).signIn(withEmail:password:)`; error mapping covers `.wrongPassword`, `.invalidCredential`, `.invalidEmail`, `.userNotFound` → `WrongPassword`; `.userDisabled` → `AccountDisabled`; `.networkError` → `NetworkUnavailable`; everything else → `FirebaseFailure`. `idToken` calls `user.idTokenForcingRefresh(forceRefresh)`.
- `iosApp/iosApp/repository/FirestoreUserRepository.swift` (+73) — implements `UserRepository`. Reads `users/{uid}` and `companySettings/profile` from `Firestore.firestore(app:)`; permissions parsed defensively into the typed `Permissions` model (maps `"manage"|"view"|<other>` to `.manage|.view|.none` per field).
- `iosApp/iosApp/repository/HlTokenRepositoryImpl.swift` (+120) — implements `HlTokenProvider`. In-memory cached token + `expiresAt`, guarded by a Swift `actor` (equivalent of Android's `Mutex`); 30 s refresh margin. `currentToken()` returns cached if still valid, else brokers via `POST /hl-token` with just `Authorization: Bearer <firebaseIdToken>` — **no body, no Content-Type** (matches the Fastify contract; adding `Content-Type: application/json` with an empty body triggers `FST_ERR_CTP_EMPTY_JSON_BODY`, per ticket-11 handoff). 401/403 → `TokenRejected`; other non-2xx → `GatewayUnreachable`; decode failure → `Unexpected`. Concurrent brokers share a single in-flight `Task<String, Error>`.
- `iosApp/iosApp/repository/HlLedgerRepositoryImpl.swift` (+112) — implements `LedgerRepository.getAccounts()`. `GET /api/v1/reports/balance-sheet?date=2999-12-31` with `Authorization: Bearer <hlToken>`. On 401 invalidates the cached token and retries exactly once; a second 401 → `HlError.Unauthorized`. Parses the envelope `{ success, data: { assets, liabilities, equity } }` and flattens each section into `LedgerAccount` with `AccountCategory` derived from the section and `AccountType` inferred via `AccountType.companion.fromName(...)`. Money kept as the raw HL `String`.
- `iosApp/iosApp/viewmodel/SplashViewModel.swift` (+53) — builds `prefs`/`directory`/`authRepo`/`userRepo` and a `RestoreSessionUseCase` in `init`; publishes `SplashResult = .loading | .needsLogin | .authenticated(AuthenticatedSession)`. `returnToLogin()` resets the state after sign-out.
- `iosApp/iosApp/viewmodel/LoginViewModel.swift` (+126) — builds its own dep chain; `@Published` state (`email`, `password`, `isSubmitting`, `error`, `candidates`, `authenticated`). `onSubmit` dispatches `LoginUseCase.execute` in a `Task {}`; branches on `LoginResult.Success` vs `LoginResult.NeedsCompanyChoice`; persists last email + companyId on success. `onChooseCompany` calls `finishLogin`. Errors are unwrapped from `NSError.userInfo["KotlinException"]` back to the typed `LoginError` (Kotlin/Native's bridging convention).
- `iosApp/iosApp/viewmodel/HomeViewModel.swift` (+91) — `bind(session, config)` wires per-session HL stack (`HlTokenRepositoryImpl → HlLedgerRepositoryImpl → GetAccountBalancesUseCase`), then triggers `loadBalances()`. `retryBalances`, `signOut`. Publishes `accounts`, `isLoadingBalances`, `balancesError`, `session`, `signedOut`. HL errors unwrapped from `NSError.userInfo["KotlinException"]` back to typed `HlError`.
- `iosApp/iosApp/ui/LoginScreen.swift` (+133) — SwiftUI: AROMEX title, Sign-in subtitle, email + password fields (with password visibility toggle button), Sign-in button (disabled while submitting or blank), inline error text. Error → dictionary-key mapping via `LoginError` subclass checks (`is LoginError.UnknownEmail`, etc.). Every user-facing string is `loc.t(Strings.shared.<key>)`.
- `iosApp/iosApp/ui/ChooseCompanyScreen.swift` (+60) — `List` of candidate companies rendered by `projectId` only (PRD §7.1 forbids `displayName` leak). Header uses `Strings.shared.choose_company_title` / `_subtitle`. Cancel button in the toolbar. Presented as a `.sheet` from `LoginScreen` when `LoginViewModel.candidates != nil`.
- `iosApp/iosApp/ui/HomeScreen.swift` (+222) — header (welcome + `home_signed_in_as` + `home_role`), balances panel (loading / error+Retry / empty / grouped list with Cash / Bank / Credit Card / Other sections), sign-out button. Each account row shows `"\(balance) \(currency)"` right-aligned. All copy via `Strings.shared.*`. `HlError` maps to the six `hl_error_*` string keys.
- `iosApp/iosApp/navigation/AromexApp.swift` (+77) — top-level router. Owns `SplashViewModel`, `LoginViewModel`, `HomeViewModel` as `@StateObject`s. `activeSession()` picks the just-signed-in session over the restored one; when neither is present, shows Login (with the ChooseCompany sheet). On `home.signedOut`, calls `splash.returnToLogin()` + `login.reset()` so the user lands on a fresh Login.

**iOS — modified**
- `iosApp/iosApp/iOSApp.swift` (+7 / −4) — removed the eager `FirebaseApp.configure()` call and the `import FirebaseCore` (dynamic per-company init only); replaced `SplashView()` with `AromexApp()`.
- `iosApp/iosApp/Info.plist` (+14 / −0) — added `NSAppTransportSecurity.NSExceptionDomains["68.183.86.89"]` with `NSExceptionAllowsInsecureHTTPLoads = true` and `NSIncludesSubdomains = false`; `TODO(M1-04 Phase B)` comment inline. Scoped to the single gateway/HL host, mirrors Android's `network_security_config.xml` posture. Also normalized trailing newline.
- `iosApp/iosApp/ContentView.swift` (deleted, −14) — placeholder view that shipped in the scaffold; no longer referenced from anywhere.
- `iosApp/iosApp/GoogleService-Info.plist` (deleted) — single-tenant Firebase config removed. Aromex initializes Firebase per company at runtime from gateway config; a bundled plist would be single-tenant and is explicitly forbidden by the ticket.
- `iosApp/Podfile` (+1) — `pod 'FirebaseAuth'` added alongside the existing `FirebaseFirestore` / `FirebaseStorage`. `Podfile.lock`, `Pods/Manifest.lock`, `Pods.xcodeproj/project.pbxproj`, and the `Target Support Files/Pods-iosApp/*` are the regenerated output of `pod install` (adds `FirebaseAuth 11.15.0`, `GoogleUtilities 8.1.0`, `RecaptchaInterop 101.0.0`).
- `iosApp/iosApp.xcodeproj/project.pbxproj` (+4 / −4) — no manual edits; only what Xcode wrote out. New Swift files land in the target automatically via Xcode 16's `PBXFileSystemSynchronizedRootGroup` (the whole `iosApp/` folder is synchronized, so files under `config/`, `repository/`, `viewmodel/`, `ui/`, `navigation/` are picked up without pbxproj additions).

**Gateway — data only, no code**
- `aromex-gateway` repo (separate; not in this repo's diff): `secrets/aromex-test-firebase-config.json` updated locally to include `iosAppId`. The **deployed** gateway's DB row for `aromextest` was updated via `POST /admin/companies` to include the new `iosAppId` field inside its passthrough `firebaseConfig` JSON. No gateway source code changed — the zod schema is already `.passthrough()`, so the extra field flows through `/resolve-company` verbatim.

**Ticket doc**
- `docs/tickets/P-13-ios-full-app.md` — reverted an accidental single-character `s` prefix on line 1 (title heading). No other changes.

## How to test

Prereqs:
- macOS with Xcode 16.2+ and an iOS simulator booted.
- The Aromex gateway live at `http://68.183.86.89/gateway/` and HL at `http://68.183.86.89/api-server/` — both unchanged, both used from Android today.
- The gateway's `aromextest` company row must include `iosAppId` in `firebaseConfig` (already updated during this ticket).
- Test login: `ansh.bajaj2611@gmail.com` — **password provided by the PM via one-time-secret** (never committed).

```bash
# 1. Clean checkout + branch.
git fetch
git checkout ticket-13-ios-full-app

# 2. Kotlin metadata build proves no platform imports leaked into shared.
./gradlew :sharedLogic:compileCommonMainKotlinMetadata
./gradlew :androidApp:assembleDebug         # sanity: Android still builds (@Throws additions)

# 3. Install pods for iOS.
cd iosApp
pod install

# 4. Build for the simulator.
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -configuration Debug build

# 5. Install + launch on the booted simulator.
SIM_ID=$(xcrun simctl list devices booted | awk -F'[()]' '/Booted/ {print $2; exit}')
APP=$(find ~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphonesimulator -maxdepth 2 -name Aromex.app -type d | head -1)
xcrun simctl install "$SIM_ID" "$APP"
xcrun simctl launch  "$SIM_ID" com.humblesolutions.aromex.Aromex
```

Live flow to exercise on the simulator:
- Cold launch → briefly Splash → Login (verified during implementation; screenshot captured).
- Sign in with the PM-provided credentials → resolve-company → dynamic Firebase init → Auth sign-in → Firestore `users/{uid}` + `companySettings/profile` → Home. **This ticket's freshly-provisioned HL company for `aromextest` has no accounts, so Home should render the empty-balances state** (same acceptance bar as Android M2-11).
- Kill + relaunch → should skip Login → Home directly (restore path uses cached email + companyId).
- Sign out → back to Login with cleared fields.
- Negative paths — all should surface a distinct inline error string:
  - Unknown email → "We couldn't find a workspace for that email."
  - Wrong password → "Incorrect email or password."
  - Simulator offline (`Features → Toggle Wi-Fi/Cellular`) → "No internet connection…" on Login; and on Home tap the balances-error Retry after re-enabling.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Strictly follows `/kmp-arch`: SwiftUI UI, `@MainActor ObservableObject` VMs + manual DI, Swift repo impls of the shared interfaces; no `expect`/`actual`; feature parity with Android. | ✅ Met | Every VM (`SplashViewModel`, `LoginViewModel`, `HomeViewModel`) is `@MainActor`, extends `ObservableObject`, and constructs its own dep chain in `init` — no DI framework. Diff contains no `expect` / `actual` keywords. iOS UI is 100% SwiftUI; no Compose Multiplatform. `:sharedLogic:compileCommonMainKotlinMetadata` still builds clean after the shared-logic edits — the `@Throws` and `iosApplicationId` additions are pure Kotlin, no platform imports. |
| Shared-logic changes limited to minimal + justified Swift-interop tweaks. | ✅ Met | Two additions, both listed in the diff and justified above: (1) `@Throws` on the use cases so Kotlin exceptions bridge to Swift; (2) optional `FirebaseClientConfig.iosApplicationId` to carry the iOS-format Firebase app ID. Both are additive and preserve Android behavior (Android build succeeds unchanged — `:androidApp:assembleDebug` runs BUILD SUCCESSFUL). |
| Login works end-to-end on iOS (resolve → sign-in → user doc → Home), verified live. | ⚠️ Code path present; not live-end-to-end-verified. | The full chain is wired in code and the app compiles + boots to Login without crashing (screenshot captured). The initial `Configuration fails … invalid GOOGLE_APP_ID` crash was root-caused (gateway was returning Android `appId`) and fixed end-to-end by adding `iosApplicationId` to the shared model, adding parse for `iosAppId` in the Swift + Android DTOs, and updating the deployed gateway's DB row for `aromextest`. A live curl to `/resolve-company` now returns the `iosAppId` field. The final sign-in step (entering the PM password in the simulator and reaching Home) was **not** executed because the password is out-of-band and was not provided during this session. |
| Multi-company chooser, inactive-user block, distinct error messages present. | ⚠️ Code path present, not live. | `LoginUseCase.execute` returns `LoginResult.NeedsCompanyChoice` when candidates > 1 → `LoginViewModel.candidates` becomes non-nil → `AromexApp` presents `ChooseCompanyScreen` as a `.sheet`. `LoginUseCase.finishLogin` signs the user back out and throws `LoginError.AccountDisabled` when `!isActive`. Login error mapping in `LoginScreen.errorMessage(_:)` covers all 8 `LoginError` variants. None of these are live-exercised in this session. |
| Already-signed-in user skips Login on launch; sign out returns to Login. | ⚠️ Code path present, not live. | `SplashViewModel.restore()` runs `RestoreSessionUseCase` on init; when it returns a session `AromexApp.activeSession()` routes to Home. `HomeViewModel.signOut()` runs `LogoutUseCase`, clears `lastSignedInEmail` + `lastCompanyId`, then `AromexApp` observes `home.signedOut` → calls `splash.returnToLogin()` + `login.reset()`. |
| Home brokers an HL token (cached + re-brokered on expiry/401) and shows balances; money rendered from strings. | ⚠️ Code path present, not live. | `HlTokenRepositoryImpl` uses a Swift `actor` for the cached-token store with 30 s refresh margin; concurrent brokers share one in-flight `Task`. `HlLedgerRepositoryImpl.fetchAccounts(retryOn401:)` retries at most once. `LedgerAccount.balance` remains `String` end-to-end; `HomeScreen.accountRow` renders `"\(account.balance) \(currency)"`. |
| Firebase initialized dynamically per company (named app); no single-tenant config committed. | ✅ Met | `iOSApp.swift` no longer calls `FirebaseApp.configure()`. `iosApp/iosApp/GoogleService-Info.plist` is deleted. `FirebaseAppFactory.app(for:)` cache-and-creates `FirebaseApp.configure(name: config.projectId, options:)` with the per-company config from the gateway. |
| No secrets committed; ATS cleartext limited to gateway/HL host (dev-only) with removal TODO. | ✅ Met | Diff contains no `.env`, no service-account JSON, no HL passwords, no Firebase JSON. `Info.plist` scopes `NSExceptionAllowsInsecureHTTPLoads=true` to `68.183.86.89` only, with `NSIncludesSubdomains=false` and a `TODO(M1-04 Phase B)` comment. |
| Builds + runs on an iOS simulator/device. | ✅ Met | `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,id=…' -configuration Debug build` → `** BUILD SUCCEEDED **`. `xcrun simctl install` + `xcrun simctl launch` run cleanly on iPhone 16 Pro simulator (iOS 18.5); the app renders Login. |
| No hardcoded UI text (per follow-up instruction from the developer). | ✅ Met | Every user-visible string in `LoginScreen.swift`, `ChooseCompanyScreen.swift`, and `HomeScreen.swift` is `loc.t(Strings.shared.<key>)` or `loc.t(Strings.shared.<key>, arg)`. No `Text("…literal…")` for user copy anywhere in the new files. The only literal strings are debug messages inside thrown `Unexpected` errors and the two English fallback role labels (see follow-up below). |

## Deviations / decisions

- **Firebase iOS app ID had to be plumbed end-to-end.** The gateway's stored `firebaseConfig.appId` for `aromextest` is the **Android** app ID (`1:409115559862:android:1db9dba117d6051623e317`). Firebase iOS SDK's `FirebaseOptions` validates the platform tag and crashes at `configure()` with "invalid GOOGLE_APP_ID". Rather than baking a single-tenant `GoogleService-Info.plist` (forbidden by the ticket) or adding a client-side `projectId → iosAppId` map (single-tenant in practice), the resolution was to (a) extend the shared model with `iosApplicationId`, (b) parse it on Android and iOS DTOs, (c) prefer it in `FirebaseAppFactory`, and (d) update the deployed gateway's DB row via `POST /admin/companies` to include `iosAppId` in the passthrough `firebaseConfig`. The ticket said "no backend changes"; the gateway code was in fact unchanged — only the data row was updated. Confirmed to the user before making the choice.
- **Sign-in flow could not be live-verified end-to-end without the PM password.** The app builds, launches, renders Login, and the Firebase-config wire-up is proven live (see `/resolve-company` now returns `iosAppId`), but the last mile — typing credentials into the simulator and reaching Home — depends on a password provided out of band and was not exercised in this session. The verification-gate crash (invalid GOOGLE_APP_ID) was reproduced and fixed; whatever surfaces after real sign-in (Firestore permissions, HL token brokering, balance-sheet parse) has been implemented but is code-path-only. This is the largest single verification gap in the ticket.
- **`@Throws` was added to shared use cases.** Kotlin/Native's ObjC bridge treats uncaught Kotlin exceptions as fatal unless the function is annotated with `@Throws(<Type>::class)` — the auto-generated header even documents this: "Other uncaught Kotlin exceptions are fatal." Without the annotations, any `LoginException` thrown by `LoginUseCase.execute` (e.g. `UnknownEmail`, `WrongPassword`) would abort the process instead of surfacing as a Swift error. Only the use cases are annotated (not the repository interfaces), because Swift implements the repos and its exceptions cross into Kotlin as wrapped NSError automatically.
- **`RestoreSessionUseCase.execute` internally wraps risky calls in `runCatching`.** The previous shape trusted `auth.currentUid`, `user.getUserProfile`, `user.getCompanyProfile`, and `auth.signOut` to never throw. On iOS those can throw `LoginException` (e.g. Firestore permission failure). To keep restore truly best-effort and matching the "return null on failure" contract Android already relies on, each risky call is now `.getOrNull()`-guarded. Semantics are unchanged for Android.
- **`HlTokenRepositoryImpl` uses a Swift `actor`, not `Mutex`.** Android used `kotlinx.coroutines.sync.Mutex`; the closest idiomatic Swift equivalent is an `actor`. Serialization guarantees are the same. Concurrent broker requests share a single stored `Task<String, Error>` so `/hl-token` is called at most once per broker cycle.
- **HL 401 retry lives in `HlLedgerRepositoryImpl.fetchAccounts(retryOn401:)`.** Recursive tail-call with `retryOn401: false` on the second attempt — matches the Android "one re-broker per call" rule.
- **Manual DI, no framework.** Each ViewModel constructs its full dep chain in `init` (`SplashViewModel`, `LoginViewModel`) or in `bind(session:config:)` (`HomeViewModel`, which needs the config that only becomes available after login). No shared factory, no service locator, no Koin/DI framework — matches `/kmp-arch` exactly.
- **No shared UI, no `expect`/`actual`.** Confirmed — the diff contains neither. iOS gets its own SwiftUI screens; the shared layer is model + interfaces + use cases + i18n only.
- **Ticket doc typo reverted.** `docs/tickets/P-13-ios-full-app.md:1` had an accidental `s` prefix on the H1 heading; reverted.

## Open questions / follow-ups

- **End-to-end live verification of sign-in and balances is deferred.** The `iosAppId` fix removed the crash bar; the next verification pass (password in hand) needs to confirm: (a) `signIn` → real UID; (b) Firestore reads succeed for the signed-in user; (c) `/hl-token` returns 200 and the token is cached in memory across balance reads; (d) `/balance-sheet` returns 200 and Home renders the empty state; (e) sign out → Login; (f) restore-session skips Login on relaunch; (g) all four error paths (unknown email, wrong password, offline network, HL 401). None of these were reachable during implementation because the password is PM-provided out of band.
- **Multi-company chooser has never been live-exercised.** No test email in the gateway currently resolves to more than one company (same open question from Android ticket #9). A fixture-setup ticket that indexes an email against two test companies would let the chooser be exercised. The code path is written and rendered as a `.sheet` on `LoginScreen`.
- **Inactive-user block not live-exercised.** Trivially testable by flipping `users/{uid}.isActive = false` in Firestore for the test user.
- **`GetAccountBalancesUseCase` still has no unit tests** — same open item as Android ticket #11. The interesting behavior (401 retry, token cache mutex, envelope parse) all lives in the platform repos, which are fake-able. A `sharedLogic/commonTest` fixture for the use case plus per-platform tests for the repos is a worthwhile follow-up.
- **`HomeViewModel.roleLabel` returns literal English `"Admin"` / `"Member"`.** These are the only user-facing literals in the new UI. The `home_role` template does route through the dictionary (`"Role: {0}"`), but the substitution value doesn't yet. Adding two string keys (`role_admin`, `role_member`) plus their English values in `sharedLogic/commonMain/i18n/{Strings,EnglishStrings}.kt` is a shared-logic follow-up and applies to Android too (Android currently does the same thing in `HomeScreen.kt`).
- **Xcode SourceKit warnings were noisy during authoring** (`No such module 'SharedLogic'`) because the SharedLogic pod framework needs a full `xcodebuild` to appear in the SourceKit index; every full build is green. Not blocking.
- **Pods regeneration bloats the diff.** `Pods.xcodeproj/project.pbxproj` shows ~65 k line churn — that's the CocoaPods regeneration pattern, not code we authored. If the reviewer prefers a lighter PR, `iosApp/Pods/` could move to `.gitignore` (Android convention). Out of scope for this ticket.
- **Gateway follow-up:** Any new client company registered from now on must include `iosAppId` in its `firebaseConfig` blob during `/admin/companies` upsert. `scripts/register-company.ts` could grow an `--iosFirebaseConfig` flag (or the existing `--firebaseConfig` blob could just include the field). Worth documenting in `aromex-gateway/docs/DEPLOY.md`.
- **Phase B — HTTPS.** Same `TODO(M1-04 Phase B)` marker as Android's `network_security_config.xml`. When the gateway + HL move to `https://<domain>/…`, delete the `NSAppTransportSecurity` block from `iosApp/iosApp/Info.plist` and switch both URLs in `iosApp/iosApp/config/AromexConfig.swift` from `http://` to `https://`.
- **Desktop parity remains out of scope** and is tracked by its own ticket per the milestone doc.
