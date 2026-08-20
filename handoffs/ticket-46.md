# Handoff — Ticket #46

**Ticket:** #46 — [M4] Inventory — T4: desktop (Admin-SDK repo impls + ViewModels + bare test UI)

## Summary
Brings Inventory to **Desktop (Compose-Desktop / JVM)** at parity with the mobile T3 work, minus the camera (Desktop is manual IMEI entry). Two Admin-SDK repositories implement the shared T1 interfaces (`InventoryRepository`, `AttributeRepository`) using `google-cloud-firestore` authenticated with the gateway-brokered service-account token — which **bypasses Firestore rules**, so the shared use cases are the *only* permission enforcement on Desktop. The race-safe add-stock **transaction contract** (`docs/SCHEMA.md` Part 2) is ported behavior-for-behavior from Android: find-or-create `products/{skuKey}`, per-unit `imeiIndex` guard → `DuplicateImeiException`, release on sold/archive, re-key on IMEI change — all reads before writes. Two plain-class desktop ViewModels (`EntitiesViewModel` style, manual DI, `dispose()`) drive an in-memory-grouped/searched product list and an add-stock form with add-new-inline. A bare Compose-Desktop test UI (list → drill-in → add-stock → edit/archive) is reachable via a new Parties/Inventory toggle on the signed-in Home. `sharedLogic` is untouched.

## Files changed

### Desktop repositories (`desktopApp/.../data/`)
- `BackendInventoryRepository.kt` **(new)** — Admin-SDK `InventoryRepository`: token-keyed cached `Firestore` client (`Mutex`, `Dispatchers.IO`, `close()`) modeled on `BackendEntityRepository`; snapshot-listener `Flow`s for products + in-stock serials; `runTransaction` for addStock/addUnits (`imeiIndex` guard → `DuplicateImeiException`), setSerialStatus/archiveSerial (release index), updateSerial (re-key), single-doc updateProduct/archiveProduct. Includes an `awaitTxn` helper that unwraps the `ExecutionException`-wrapped `DuplicateImeiException` so callers see the domain exception.
- `BackendAttributeRepository.kt` **(new)** — Admin-SDK `AttributeRepository`: `observeAttributes` snapshot listener; `addAttribute` deduped case-insensitively on `(type, parentId, nameKey)` via the shared `AttributeName.matchKey`.

### Desktop ViewModels (`desktopApp/.../ui/inventory/`)
- `InventoryListViewModel.kt` **(new)** — live products + in-stock serials via `ObserveInventoryUseCase`; **groups serials by `productId` in memory** for per-SKU counts; client-side search over attribute names + in-stock IMEIs; mutations through `UpdateProductUseCase`/`ArchiveProductUseCase`/`SetUnitStatusUseCase`/`ArchiveUnitUseCase`/`UpdateUnitUseCase`; `noAccess`/`canManage` from the `inventory` scope; `dispose()` cancels the scope + closes the repo.
- `AddStockViewModel.kt` **(new)** — attribute pickers (model filtered by brand) with **add-new-inline** (`AddAttributeUseCase`); unit builder with **manual IMEI** (no scanner); `AddStockUseCase`/`AddUnitsUseCase`; `startAddUnits` for add-to-existing-SKU; `DuplicateImeiException` → `imeiError`; `dispose()` closes both repos.

### Desktop UI + navigation
- `ui/inventory/InventoryScreen.kt` **(new)** — bare, unstyled Compose-Desktop test UI: product list (SKU label + "N in stock" + archived tag + search) → drill-in (units with IMEI/cost/condition/status/location, set-status/archive/edit) → add-stock (5 pickers with ＋ add-new, price, manual-IMEI unit rows, staged list, save with duplicate-IMEI field error). Edit a SKU's default selling price; archive a SKU.
- `navigation/AromexApp.kt` **(modified)** — the signed-in `Route.Home` branch now hosts the two inventory VMs (bound with `LaunchedEffect`, released in `DisposableEffect`) and a lightweight **Parties / Inventory** `TabButton` toggle that swaps between the existing `EntitiesScreen` and the new `InventoryScreen`; sign-out still routes through `home::signOut`.

## How to test
1. **Build:** `./gradlew :desktopApp:compileKotlin` and `./gradlew :sharedLogic:jvmTest` (shared stays green/untouched).
2. **Run:** `./gradlew :desktopApp:run`, sign in with the aromex-test desktop login that has `inventory = MANAGE` (T2 indexes deployed), and click the **Inventory** toggle on Home.
3. **Add + group:** Add stock → pick brand/model/capacity/color/carrier (use ＋ Add new for a missing value; model list filters by brand), set a price, add 3 typed IMEIs with cost/condition/location → Save. The SKU shows **"3 in stock."** Add the same model again → units land under the **same SKU** (no duplicate).
4. **Duplicate:** add a unit whose IMEI is already in stock → field error, nothing written.
5. **Release:** on a unit, Mark SOLD or Archive → count drops; re-add the same IMEI → succeeds.
6. **Price:** open a SKU → Edit price → the new price shows.
7. **Permission (rules bypassed):** with an `inventory != MANAGE` session, add/edit is blocked by the use case (`PermissionDeniedException`).

> **Note on how this was verified:** because a Compose-Desktop GUI can't be driven programmatically, criteria 3–7 were verified end-to-end against the real aromex-test Firestore with a temporary headless harness that restored the persisted desktop session and exercised the repos through the shared use cases — **10/10 checks passed** (add → "3/4 in stock" grouping, atomic duplicate rejection, SOLD-releases-then-re-add, price edit, and the non-MANAGE permission block with rules bypassed). The harness and its throwaway Gradle task were removed after the run and are not in this diff.

## Acceptance criteria
- [x] `BackendInventoryRepository` + `BackendAttributeRepository` with the **Admin SDK** — client transactions per T2's contract, snapshot-listener Flows, normalized-dedupe `addAttribute`.
- [x] `InventoryListViewModel` (live products + in-stock serials; **group by `productId` in memory**; client-side search) + `AddStockViewModel` (pickers + add-new-inline + unit builder with **manual IMEI**; `DuplicateImeiException` surfaced). Manual DI, `StateFlow`, injected scope; `UpdateProductUseCase` + `UpdateUnitUseCase` wired.
- [x] Bare test UI (Compose-Desktop): list → drill-in → add-stock (manual IMEI) → edit/archive/set-status, reachable from where the desktop entities test screen is.
- [x] E2E: add SKU, add 3 typed IMEIs → "3 in stock" with per-unit cost/condition; re-adding the same model groups under the same SKU (verified via harness).
- [x] Duplicate in-stock IMEI rejected (`DuplicateImeiException`) with nothing partially written; sold/archived releases the IMEI so it can be re-added (verified).
- [x] **Permission enforcement works with rules bypassed** — an `inventory != MANAGE` session cannot add/edit (blocked by the T1 use case), proven on Desktop specifically (verified).
- [x] A SKU's default selling price editable (via `UpdateProductUseCase`); attributes stay immutable (no attribute-edit path).
- [x] add-new-inline works (model filtered by brand); stock counts + search computed client-side over cached lists (no re-fetch).
- [x] Repos use the Admin SDK, implement the T1 interfaces, match T2's transaction contract; no shared UI, no `expect`/`actual`.
- [x] `sharedLogic` untouched.

## Deviations / decisions
- **Navigation via an in-Home toggle.** The desktop `Route.Home` previously rendered `EntitiesScreen` directly; to keep `EntitiesScreen` untouched while making the inventory test screen reachable "from where the entities test screen is," a minimal Parties/Inventory `TabButton` toggle was added at the Home level. Both VMs are bound eagerly (like the entities VM) for test simplicity.
- **Reads via `callbackFlow` directly (no shared `InventoryObserve.kt`).** As anticipated in the T3 handoff, Desktop builds Kotlin `Flow`s natively (like `BackendEntityRepository`), so it does not use the iOS-only shared observe adapter — keeping `sharedLogic` untouched.
- **`updateSerial` re-key path implemented but not surfaced in the UI.** Per the schema, a mistyped IMEI is corrected by void (archive → release index) + re-add; the test UI never drives an in-place IMEI edit. The repo still implements the documented re-key contract (Desktop bypasses the T2 rule that pins `imei`).
- **Verification method** (headless harness) documented above — added and removed within this ticket; not part of the shipped diff.

## Post-review fixes (manager review of PR #50)
- **Self-refreshing Admin-SDK token** — both repos now build the `Firestore` client **once** with an `OAuth2CredentialsWithRefresh` whose handler re-brokers via `FirestoreTokenBroker`, so long-lived snapshot listeners no longer die (UNAUTHENTICATED) when the ~1h token expires. Replaces the fixed-expiry `OAuth2Credentials` seed. (`BackendInventoryRepository.kt`, `BackendAttributeRepository.kt`.) Re-verified 10/10 via the headless harness. *(Note: `BackendEntityRepository` still uses the old fixed-token pattern — a shared follow-up.)*
- **Add-stock UI hardening** (`InventoryScreen.kt`): Save is gated until all 5 SKU attributes are picked (avoids submitting an incomplete SKU); "Add unit to list" now validates IMEI (`Imei.isValid`) + cost (`Money.isValidPositiveDecimal`) before staging; edit fields are keyed on the server value so they reset after a successful edit.

## Open questions / follow-ups
- **Real/polished Inventory UI** (theming, dark mode, desktop reflow) is a later ticket — this UI is intentionally bare/test-only.
- **Cross-device scan hand-off** (phone → desktop), scan-to-sale-cart, bulk-scan — deferred per Brief #41 (Desktop is manual entry).
- **Attribute rename backfill** (denormalized `name` in `Product.attributes`/`Serial.location`) remains a PO-accepted later concern.
- **Interactive GUI smoke test** not performed by an automated tool; the Inventory tab is live in the running app for a manual visual pass if desired.
