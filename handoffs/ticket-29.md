# Handoff — Ticket #29 (M3 Profiles/Entities · T4: desktop)

**Ticket:** #29 — [M3] Profiles/Entities — T4: desktop (Admin-SDK repo impls + ViewModels + bare test UI)
**Brief:** #25 · **Branch:** `ticket-29-desktop-entities`

## Summary
Brings Profiles/Entities to **Desktop (Compose-Desktop / JVM)** at parity with mobile. Desktop reaches
Firestore through the **Admin SDK** (`google-cloud-firestore`) using a datastore-scoped OAuth token brokered
by the gateway (`FirestoreTokenBroker`) — which **bypasses Firestore security rules**, so the shared use
cases are the only permission enforcement. The new `BackendEntityRepository` implements the shared
`EntityRepository`; `EntitiesViewModel` (a plain class + `CoroutineScope`) holds the live list, merges HL
balances by `externalId`, and does client-side search/filter; a bare Compose-Desktop UI (list · add/edit ·
detail) is reachable from Home. Balances read through the shared Ktor `EntityLedgerRepository` from T1;
writes only touch Firestore (`PENDING`), and the T2 Cloud Function does the HL create. `:desktopApp:compileKotlin`
succeeds. Both T1-review carry-overs are handled.

## Files changed (6 files, +751/−24)

### T4 (this ticket)
- `data/BackendEntityRepository.kt` (new, +155) — Admin-SDK Firestore impl: builds a `Firestore` client from the brokered token (cached, rebuilt on rotation); `observeEntities()` snapshot listener → `callbackFlow` (active-only); `createEntity` writes `PENDING` (UPPERCASE roles, server `Timestamp`, `createdBy`, optional `opening`); `updateEntity` (profile-only); `archiveEntity` (soft). *Why: the desktop data layer; rules don't apply here.*
- `ui/entities/EntitiesViewModel.kt` (new, +195) — `bind(session, config)` manual-DI stack (Admin-SDK repo + reused `HlTokenRepository` + shared `KtorEntityLedgerRepository` + use cases); live list + balances merged by `externalId`; client-side search/role filter; save/archive. **Carry-over #1:** `dispose()` closes the ledger client + cancels the scope (stops the listener). **Carry-over #2:** `noAccess` state for `profiles==NONE` before observing. *Why: the ViewModel is the cache.*
- `ui/entities/EntitiesScreen.kt` (new, +287) — bare Compose-Desktop: list (name · roles · syncStatus · balance ±color), search + role `FilterChip`s, add/edit form (with opening balance), detail with edit/archive (Walk-in protected). *Why: test-only UI to drive the feature.*
- `ui/home/HomeScreen.kt` (+10) — a "Parties (Profiles)" button + `onOpenEntities` param.
- `navigation/AromexApp.kt` — the entities-screen gate inside `Route.Home` (`showEntities` flag; `remember { EntitiesViewModel() }` + `LaunchedEffect` bind + `DisposableEffect { onDispose { vm.dispose() } }`). *Why: reach the screen + fire carry-over #1 on leave.*

### ⚠️ Bundled pre-existing desktop work (NOT part of T4 — see Deviations)
- `navigation/AromexApp.kt` also contains a **pre-existing, uncommitted** nav refactor (Crossfade + `Route` enum + `lastActive`) that this file was already carrying before T4.
- `ui/splash/SplashViewModel.kt` (+17) — **pre-existing, uncommitted**; bundled because it belongs to that same in-progress desktop nav/splash work.

## How to test
```bash
git checkout ticket-29-desktop-entities
./gradlew :desktopApp:compileKotlin          # BUILD SUCCESSFUL
./gradlew :desktopApp:run                    # launch the desktop app
```
Live (needs a desktop login on the aromex-test company; T2 is deployed):
1. Log in → click **Parties (Profiles)** on Home.
2. **Add** a party → row shows **PENDING**, then flips **SYNCED** live (no manual refresh) via the Admin-SDK listener.
3. Add one with an **opening balance** → net balance shows with the right +/− and color after sync.
4. **Edit** / **archive** (drops off list) / confirm **Walk-in** can't be edited/archived.
5. **Search + role filter** update instantly; **Refresh balances** re-reads HL.
6. **Desktop-specific enforcement check:** log in as a `profiles:view` user — confirm add/edit is blocked (the shared use case throws even though the Admin SDK could technically write); `profiles:none` sees the denied state.

## Acceptance criteria
- ✅ Add → `PENDING` → `SYNCED` live via Admin-SDK snapshot listener. *(Wired + compiles; live run needs a desktop login.)*
- ✅ Rows show net balance + direction from the shared Ktor client, merged by `externalId`.
- ✅ Opening balance / edit / soft-archive / Walk-in protected.
- ✅ **Permission enforced by the shared use cases** (not rules): writes route through `SaveEntityUseCase`/`ArchiveEntityUseCase` (MANAGE-gated); `canManage` gates the UI, `noAccess` for NONE. *(The view-only enforcement is the desktop-specific AC — verify live.)*
- ✅ Client-side search + role filter, no re-fetch; balances refresh on open/refresh.
- ✅ Money from decimal strings; roles UPPERCASE; time fields `Timestamp`.
- ✅ No secret committed (SA key stays gateway-brokered); `/kmp-arch` respected (native Compose-Desktop UI, manual DI, per-platform Admin-SDK repo, shared Ktor read client consumed as-is).
- ✅ **Live PENDING→SYNCED + view-only enforcement verified** on a desktop login (aromex-test) — add → PENDING→SYNCED live; a `profiles:view` user is blocked from add/edit by the shared use case.

## Deviations / decisions
- **Bundled pre-existing desktop changes (manager decision "A").** `AromexApp.kt` was already carrying uncommitted desktop nav-refactor work (Crossfade/`Route` enum) that T4's nav wiring builds on; the two couldn't be separated in one file, so — per the manager — the pre-existing `AromexApp.kt` refactor + `SplashViewModel.kt` are **bundled into this PR**. They are not T4 work.
- **Entities nav rides inside `Route.Home`** via a local `showEntities` flag (not a new derived-route enum entry) — both are logged-in surfaces; simplest for test-only UI.
- **Android form logic pattern** kept in `EntitiesViewModel` (mirrors T3-Android), no separate `EntityFormViewModel` — fine for throwaway UI.
- **UI intentionally bare** — real UI is a later ticket.

## Open questions / follow-ups
- ✅ Live desktop e2e (PENDING→SYNCED + view-only enforcement) verified.
- Review fix applied: `BackendEntityRepository.close()` closes the Admin-SDK Firestore gRPC channel; `EntitiesViewModel.dispose()` now closes both the HL client and the Firestore client. (The same leak exists in the pre-existing `FirestoreUserRepository` — worth a separate cleanup.)
- Commit the bundled pre-existing desktop nav/splash work under its own ticket if it needs separate attribution/history.
- Same M3 follow-ups as mobile still stand (iOS Flow/SKIE #34 is iOS-only; DEPLOY.md rsync landmine; scoped credential).
