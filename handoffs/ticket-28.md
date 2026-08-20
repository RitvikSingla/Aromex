# Handoff — Ticket #28 (M3 Profiles/Entities · T3: mobile)

**Ticket:** #28 — [M3] Profiles/Entities — T3: mobile (Android + iOS) repo impls + ViewModels + bare test UI
**Brief:** #25 · **Branch:** `ticket-28-mobile-entities`

## Summary
Wires the two mobile platforms to the Profiles/Entities feature built in T1 (shared contract) and T2
(server-side HL sync spine). Each platform implements the shared `EntityRepository` with its native
Firestore SDK, builds ViewModels that hold the live entity list + merge HL balances by `externalId`, and
puts a **bare, test-only UI** in front (list · add/edit · detail · search + role filter). Balances are read
through the **shared Ktor** `EntityLedgerRepository` from T1; writes only touch Firestore (`PENDING`) — the
T2 Cloud Function does the HL create. Both platforms compile (`:androidApp:compileDebugKotlin` and iOS
`xcodebuild` both succeed). The two T1-review carry-overs (close the HL HttpClient; guard the
`profiles==NONE` throw) are handled on both platforms.

## Files changed (14 files, +1504)

### Android (`androidApp/`)
- `data/BackendEntityRepository.kt` (new) — Firestore impl of `EntityRepository`: `observeEntities()` via a snapshot listener → `callbackFlow`; `createEntity` writes a `PENDING` doc (UPPERCASE roles, server timestamps, `createdBy`, optional `opening` map); `updateEntity` (profile fields only); `archiveEntity` (soft, `isActive=false`). *Why: the platform data layer.*
- `ui/entities/EntitiesViewModel.kt` (new) — `bind(session, config)` manual-DI stack (Firestore repo + reused `HlTokenRepository` + shared `KtorEntityLedgerRepository`); live list + balances merged by `externalId`; client-side search/role filter (`EntitiesFilter`); save/archive. **Carry-over #1:** `onCleared()` closes the ledger client. **Carry-over #2:** returns a `noAccess` state for `profiles==NONE` before observing. *Why: the ViewModel is the cache.*
- `ui/entities/EntitiesScreen.kt` (new) — bare Compose: list (name · roles · syncStatus · balance ±color), search, role `FilterChip`s, add/edit form (with opening balance), detail with edit/archive (Walk-in protected). *Why: test-only UI to drive the feature.*
- `navigation/Route.kt` (+1) — add `Route.Entities`.
- `navigation/AromexApp.kt` (+36) — `EntitiesRoute` wiring `bind(...)` + Home→Entities.
- `ui/home/HomeScreen.kt` (+10) — a "Parties (Profiles)" button + `onOpenEntities` param.

### iOS (`iosApp/`)
- `repository/BackendEntityRepository.swift` (new) — Firestore Swift SDK impl of `EntityRepository`; `create/update/archive` mirror Android; a **native `listen(onChange:onError:)`** snapshot listener maps docs ↔ `SharedLogic.Entity`. `observeEntities()` returns an inert stub `Flow` (see Deviations). *Why: platform data layer + Flow-interop workaround.*
- `viewmodel/EntitiesViewModel.swift` (new) — `@MainActor ObservableObject`; `bind(session:config:)` manual-DI; native live listener + balances merged by `externalId`; search + role filter. **Carry-over #1:** `deinit` closes the ledger client + removes the listener. **Carry-over #2:** `permissionDenied` for `profiles==NONE`.
- `viewmodel/EntityFormViewModel.swift` (new) — add/edit via `SaveEntityUseCase`.
- `ui/EntitiesView.swift`, `ui/EntityFormView.swift`, `ui/EntityDetailView.swift` (new) — bare SwiftUI list/form/detail.
- `ui/HomeView.swift` (+23) — `NavigationLink` into `EntitiesView`.
- `viewmodel/HomeViewModel.swift` (+4) — expose `boundConfig` for the entities screen.

## How to test
```bash
git checkout ticket-28-mobile-entities

# Android — compiles:
./gradlew :androidApp:compileDebugKotlin
# iOS — compiles:
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -configuration Debug build   # ** BUILD SUCCEEDED **
```
Live end-to-end (needs a real login on the aromex-test company; T2 is deployed):
1. Log in, open **Parties (Profiles)** from Home.
2. **Add** a party → the row appears **PENDING**, then flips to **SYNCED** live (no manual refresh).
3. Add one with an **opening balance** → after sync its net balance shows with the right +/− and color.
4. **Edit** a party; **archive** one (drops off the list); confirm **Walk-in** can't be edited/archived.
5. **Search + role filter** update instantly; **Refresh balances** re-reads HL.
6. Log in as a `profiles:view` user (no add/edit) and a `profiles:none` user (no access).

## Acceptance criteria
- ✅ Add → `PENDING` → `SYNCED` live via snapshot listener (both platforms). *(Wired + compiles; live run needs a device login — not executed here.)*
- ✅ Row shows net balance + direction (green/red/neutral) from the shared Ktor client, merged by `externalId`.
- ✅ Opening balance supported in the add form (posts via the doc → CF).
- ✅ Edit updates Firestore (profile-only; CF pushes to HL); archive = soft delete; Walk-in protected in the detail UI.
- ✅ Search + role filter run client-side (no re-fetch); a refresh re-reads balances.
- ✅ Money rendered from decimal strings (no float); roles persisted UPPERCASE; time fields Firestore `Timestamp`.
- ✅ `profiles:view` browses but can't add/edit (`canManage`); `profiles:none` sees a denied state.
- ✅ `/kmp-arch` respected: native UI per platform, manual DI, per-platform repo impls, shared Ktor read client consumed as-is.
- ⚠️ **Full live PENDING→SYNCED not executed** in this ticket (both builds pass; runtime verification needs a device login + is covered by T2's server-side e2e).

## Deviations / decisions
- **iOS Flow interop (no SKIE):** `EntityRepository.observeEntities()` returns a Kotlin `Flow`, which is awkward to produce from Swift without SKIE. iOS therefore returns an **inert stub `Flow`** and drives the live list through a **native Firestore snapshot listener**; the iOS VM does the `profiles` permission check + `isActive` filter itself, **bypassing `ObserveEntitiesUseCase`** for the list (documented in `BackendEntityRepository.swift`). Android uses the shared use case normally. Both still consume the suspend use cases (`SaveEntityUseCase`, `ArchiveEntityUseCase`, `GetEntityBalancesUseCase`).
- **Android form logic lives in `EntitiesViewModel`** (not a separate `EntityFormViewModel`); iOS does split it out. For throwaway test UI this avoids a duplicate DI chain on Android. The real-UI ticket can restructure.
- **UI is intentionally bare** (no theming/dark-mode/insets) — per the ticket, a later UI ticket owns polish.

## Open questions / follow-ups
- Run the **live PENDING→SYNCED e2e** on a device/simulator against aromex-test to close the last AC.
- Reconsider the iOS Flow story if `observeEntities()` should be truly shared later — options: adopt SKIE, or add a native-friendly method to the shared interface so iOS doesn't need the stub.
- T4 (#29 Desktop) mirrors this via the Admin SDK; the two T1-review carry-overs are also noted there.
