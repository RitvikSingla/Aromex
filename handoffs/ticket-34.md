# Handoff — Ticket #34

**Ticket:** #34 — [M3 follow-up / Tech] Adopt SKIE — iOS consumes shared Flows natively; remove InertFlow stub + duplicated list logic

## Summary
Adopted **SKIE 0.10.13** (Touchlab) on `sharedLogic` — the SKIE release that adds **Kotlin 2.4.0** support (this project is on 2.4.0), confirmed by linking the iOS framework in-project. SKIE is compile-time only, so Android/Desktop artifacts are unaffected. On iOS, the entities list now flows through the **shared `ObserveEntitiesUseCase`**: the `InertFlow` stub and the duplicated permission-gate + `isActive` filter in `EntitiesViewModel.swift` are gone, replaced by a shared `entitiesCallbackFlow` adapter that turns the native Firestore listener into a Kotlin `Flow` the use case consumes. To let iOS `try`/`catch` the `profiles == NONE` denial (instead of crashing on a Kotlin/Native non-suspend throw), `ObserveEntitiesUseCase.execute` is annotated `@Throws`. Finally, because SKIE renames Kotlin `suspend` interface members for Swift *implementors*, every iOS repository's suspend conformance methods were renamed to SKIE's `__`-prefixed form (consumers still call the clean async wrappers).

## Files changed

### Config / build
- `gradle/libs.versions.toml` — pin `skie = "0.10.13"` + register the `co.touchlab.skie` plugin alias.
- `sharedLogic/build.gradle.kts` — apply the SKIE plugin; add a `skie { analytics { enabled.set(false) } }` block to avoid build-time analytics network calls.

### Shared logic (`sharedLogic`)
- `repository/EntityObserve.kt` (new) — `entitiesCallbackFlow(subscribe:)` adapter + `EntityObservation` handle + `ObserveEntitiesException`; lets a platform without a Flow-producing SDK (iOS) feed the shared observe path while the repo interface stays `Flow` (Android/Desktop untouched).
- `usecase/ObserveEntitiesUseCase.kt` — annotate `execute` with `@Throws(PermissionDeniedException::class)` so Kotlin/Native exports it as a throwing Swift function; no behavior change for Android/Desktop.

### iOS (`iosApp`)
- `repository/BackendEntityRepository.swift` — delete the `InertFlow` stub and the native `listen(...)` driver; implement `observeEntities()` via the shared `entitiesCallbackFlow` adapter feeding a Firestore snapshot listener; add `FirestoreEntityObservation` (detaches the listener on cancel); rename the suspend write conformances to `__createEntity` / `__updateEntity` / `__archiveEntity`.
- `viewmodel/EntitiesViewModel.swift` — consume the shared `ObserveEntitiesUseCase` Flow as a SKIE `AsyncSequence`; remove the duplicated `profiles == NONE` gate and `isActive` filter (now sourced from shared code); derive `permissionDenied` by catching the shared `PermissionDeniedException`; replace the `ListenerRegistration` with an `observeTask` cancelled in `deinit`; drop the unused `FirebaseFirestore` import.
- `repository/FirebaseAuthRepositoryImpl.swift` — rename suspend conformances to `__signIn` / `__signOut` / `__currentUid` / `__idToken`.
- `repository/FirestoreUserRepository.swift` — rename to `__getUserProfile` / `__getCompanyProfile`.
- `repository/HlLedgerRepositoryImpl.swift` — rename to `__getAccounts`.
- `repository/HlTokenRepositoryImpl.swift` — rename to `__currentToken` / `__invalidate`.
- `repository/HttpCompanyDirectoryRepository.swift` — rename to `__resolveCompanies`.
- `repository/UserDefaultsPreferencesRepository.swift` — rename the six get/set prefs conformances to `__`-prefixed.

### Not part of ticket #34 (separate commit on this branch)
- `a3fcec3 UI refresh` — `AromexGradientHeader.kt/.swift`, `LoginScreen.kt`, `SplashScreen.kt`, `SplashView.swift`, `LoginView.swift`. A visual refresh committed separately; unrelated to the SKIE work.

## How to test
1. **iOS builds:** `cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' -configuration Debug build` → `BUILD SUCCEEDED`.
2. **Shared tests:** `./gradlew :sharedLogic:jvmTest` → pass (commonTest on JVM).
3. **Android unaffected:** `./gradlew :androidApp:compileDebugSources` → success.
4. **Desktop unaffected:** `./gradlew :desktopApp:compileKotlin` → success.
5. **iOS end-to-end (simulator, test login):** login via email discovery → Home shows HL balances → entities list live PENDING→SYNCED with net balances → add / edit / archive → Walk-in cannot be archived → `profiles` permission `none` / `view` / `manage` states all behave.

## Acceptance criteria
- ✅ **SKIE adopted + pinned compatible with Kotlin 2.4.0** — `skie = "0.10.13"`; framework links in-project. Main path used (not fallback).
- ✅ **iOS list driven by shared `ObserveEntitiesUseCase`; `InertFlow` gone; duplicated gate + `isActive` filter removed** — see `BackendEntityRepository.swift` + `EntitiesViewModel.swift` diffs.
- ⏳ **Whole iOS app still works end-to-end (simulator, test login)** — **not yet run**; owner (Rishi) to run the E2E pass with the test credentials. Builds clean; runtime flows unverified.
- ✅ **Reads/writes unchanged in behavior** — write methods only renamed (`__`-prefix); bodies unchanged.
- ✅ **Android + Desktop unaffected** — both compile; `sharedLogic` `jvmTest` (commonTest) passes. SKIE only touches the iOS framework.
- ✅ **Versions pinned + documented; no secrets** — pin in `libs.versions.toml`; no secrets added.

## Deviations / decisions
- **Produce-side mechanism:** used the ticket's preferred path (SKIE + a shared `entitiesCallbackFlow` adapter) rather than the callback-method Fallback. The repo interface stays `observeEntities(): Flow<List<Entity>>`, so Android/Desktop needed no changes.
- **`@Throws` on the use case:** required so Kotlin/Native exports `execute` as a throwing Swift function; without it a `profiles == NONE` throw would crash iOS. Annotation-only; no effect on JVM callers.
- **`__`-prefixed suspend conformances:** a SKIE consequence for Swift types implementing Kotlin suspend interfaces — consumers still call the clean SKIE `async` wrappers. This is the bulk of the "check everything still works" surface.
- **Sealed-class switches were NOT changed:** SKIE's sealed interop is additive — `HlError.Unexpected(message:)`, `error is HlError.X`, `result as? LoginResult.Success`, `BalanceDirection.receivable` all still compile. The ticket anticipated switch rewrites here; none were needed (confirmed by a clean iOS build).
- **Analytics disabled** in the `skie {}` block to keep builds/CI network-free.

## Open questions / follow-ups
- **E2E simulator pass** is the one open acceptance item — owner to run it before merge.
- **UI-refresh commit** (`a3fcec3`) rides along on this branch by request; reviewers should treat it as out-of-scope for #34 (or it can be split to its own PR if preferred).
- Unblocks the real, polished **Profiles UI** ticket (separate brief).
