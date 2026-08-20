# Handoff — Ticket #45

**Ticket:** #45 — [M4] Inventory — T3: mobile (Android + iOS) repo impls + ViewModels + scan-to-add + bare test UI

## Summary
Wires both mobile platforms to the inventory contract from T1 (#43) and the Firestore rules/indexes from T2 (#44). Each platform gets native-SDK implementations of the shared `InventoryRepository` and `AttributeRepository`, running the **race-safe add-stock transaction client-side** (find-or-create `products/{skuKey}` + per-unit `imeiIndex` guard, releasing the index on sold/archive) per `docs/SCHEMA.md` Part 2. Adds `InventoryListViewModel` (live products + in-stock serials, in-memory grouping/search — no re-fetch) and `AddStockViewModel` (attribute pickers with add-new-inline, unit builder, scanned IMEI, `DuplicateImeiException` field error), plus the **"+ Scanner" half**: camera → IMEI barcode → fills the add-unit field, behind a small `ImeiScanner` seam (Android CameraX + ML Kit; iOS AVFoundation). A bare, unstyled UI on each platform drives the whole path (list → drill-in → add-stock w/ scan → edit/archive). One small shared addition (`InventoryObserve.kt`) lets iOS produce the Kotlin `Flow`s; nothing else in `sharedLogic` changed.

## Files changed

### sharedLogic (the only shared change)
- `sharedLogic/.../repository/InventoryObserve.kt` **(new)** — `InventoryObservation` + `productsCallbackFlow`/`serialsCallbackFlow`/`attributesCallbackFlow`, mirroring `EntityObserve.kt` (#34) so iOS (which can't build a Kotlin `Flow` from Swift) can feed the shared `ObserveInventoryUseCase`. Android/Desktop don't use it.

### Android
- `androidApp/.../data/BackendInventoryRepository.kt` **(new)** — Firestore KTX impl of `InventoryRepository`: snapshot-listener `Flow`s for products + in-stock serials; `runTransaction` for addStock/addUnits (imeiIndex guard → `DuplicateImeiException`), setSerialStatus/archiveSerial (release index), updateSerial (re-key), single-doc updateProduct/archiveProduct.
- `androidApp/.../data/BackendAttributeRepository.kt` **(new)** — `observeAttributes` + `addAttribute` with case-insensitive dedupe on `(type, parentId, nameKey)`.
- `androidApp/.../scanner/ImeiScanner.kt` **(new)** — the camera seam (`Result` = Scanned/Cancelled/PermissionDenied) keeping camera SDKs out of shared/testable code.
- `androidApp/.../scanner/ImeiScannerScreen.kt` **(new)** — CameraX preview + ML Kit barcode analyzer; CAMERA runtime-permission flow; denial degrades to manual entry.
- `androidApp/.../ui/inventory/InventoryListViewModel.kt` **(new)** — live products+serials via `ObserveInventoryUseCase`, in-memory grouping by `productId` for stock counts, client-side search, edit/archive/status mutations.
- `androidApp/.../ui/inventory/AddStockViewModel.kt` **(new)** — attribute pickers (model filtered by brand) with add-new-inline (`AddAttributeUseCase`), unit builder + scanned IMEI, `AddStockUseCase`/`AddUnitsUseCase`, `DuplicateImeiException` → field error.
- `androidApp/.../ui/inventory/InventoryScreen.kt` **(new)** — bare list → drill-in → add-stock (with scan) → edit/archive/price UI.
- `androidApp/build.gradle.kts`, `gradle/libs.versions.toml` — CameraX + ML Kit barcode-scanning deps.
- `androidApp/src/main/AndroidManifest.xml` — `CAMERA` permission + optional camera feature.
- `androidApp/.../navigation/Route.kt`, `.../navigation/AromexApp.kt`, `.../ui/home/HomeScreen.kt` — Inventory route + Home entry point.

### iOS
- `iosApp/.../repository/BackendInventoryRepository.swift` **(new)** — Firestore Swift SDK impl; SKIE `__`-prefixed suspend members; same transaction contract; observe via the shared adapters → `SkieSwiftFlow`. Duplicate IMEI signalled with an `NSError` (Kotlin exceptions can't be thrown from Swift).
- `iosApp/.../repository/BackendAttributeRepository.swift` **(new)** — `observeAttributes` + deduped `addAttribute`.
- `iosApp/.../scanner/ImeiScanner.swift` **(new)** — AVFoundation `AVCaptureSession` + `AVCaptureMetadataOutput` (code128/EAN/code39); camera permission handling.
- `iosApp/.../viewmodel/InventoryListViewModel.swift`, `.../viewmodel/AddStockViewModel.swift` **(new)** — `@MainActor ObservableObject` mirrors of the Android VMs.
- `iosApp/.../ui/InventoryView.swift` **(new)** — bare SwiftUI list → drill-in → add-stock (with scan) → edit/archive.
- `iosApp/.../ui/HomeView.swift` — Inventory entry point (fullScreenCover).
- `iosApp/iosApp/Info.plist` — `NSCameraUsageDescription`.

### Docs
- `docs/tickets/M4-41-T1..T4-*.md` — milestone ticket definitions (were untracked locally; committed alongside this work — see Deviations).

## How to test
1. **Build (both platforms):**
   - Android: `./gradlew :androidApp:assembleDebug`
   - iOS: `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 16e' build`
   - Shared: `./gradlew :sharedLogic:jvmTest`
2. **Run + sign in** with a mobile test login for aromex-test that has `inventory` scope (T2 rules/indexes deployed). Open **Inventory** from Home.
3. **Add stock:** tap Add stock → pick brand/model/capacity/color/carrier (use **＋ Add new** for a value that doesn't exist; model list filters by brand), set a selling price, add 3 units — type or **scan** each IMEI (a printed/on-screen Code128 works), set cost/condition/location — Save. The SKU shows **"3 in stock."**
4. **Group:** add the same model again → units land under the **same SKU row** (no duplicate).
5. **Duplicate:** try to add a unit whose IMEI is already in stock → **field error**, nothing written.
6. **Release:** on a unit, **Mark SOLD** or **Archive** → count drops; re-add the same IMEI as a fresh unit → succeeds.
7. **Price:** open a SKU → edit selling price → the new price shows.
8. **Camera denial:** deny the camera prompt → the form still allows manual IMEI entry.

## Acceptance criteria
- [x] Android + iOS `BackendInventoryRepository` + `BackendAttributeRepository` (native SDK; client transactions; snapshot-listener Flows) — in the diff for both platforms.
- [x] Android `ImeiScanner` (CameraX + ML Kit) + CAMERA permission; deps in the catalog — `ImeiScannerScreen.kt`, gradle, manifest.
- [x] iOS `ImeiScanner` (AVFoundation) + `NSCameraUsageDescription` — `ImeiScanner.swift`, Info.plist.
- [x] Both platforms `InventoryListViewModel` + `AddStockViewModel` (manual DI, in-memory grouping/search, add-new-inline, `DuplicateImeiException` handling); `UpdateProductUseCase` + `UpdateUnitUseCase` wired.
- [x] Bare test UI on both platforms: list → drill-in → add-stock (with scan) → edit/archive.
- [x] Navigation to the inventory test screen from Home (next to Entities).
- [x] **E2E (add SKU, scan 3 → "3 in stock"; same model regroups):** confirmed working on both Android and iOS by the developer.
- [x] **Duplicate in-stock IMEI rejected atomically** — repo transaction guard + VM field error (developer-verified).
- [x] **Selling/archiving a unit releases its IMEI; re-add works** — `setSerialStatus(SOLD)`/`archiveSerial` delete `imeiIndex` in-txn (developer-verified).
- [x] **SKU default selling price editable; attributes immutable** — `updateProduct`/`ProductEdits`; no attribute-edit path.
- [x] **add-new-inline** (model filtered by brand) — in both `AddStockViewModel`s + pickers.
- [x] **Counts + search computed client-side over cached lists** — in-memory grouping/filter in both list VMs.
- [x] **Camera fills the IMEI field; permission requested/handled; denial degrades to manual** — both scanners.
- [x] Repos implement T1 interfaces with native SDKs; permission enforcement rides the use cases; no shared UI, no `expect`/`actual`.
- [x] `sharedLogic` untouched except the additive `InventoryObserve.kt` observe adapter (see Deviations).

## Deviations / decisions
- **Shared `InventoryObserve.kt` added.** The ticket says "sharedLogic is untouched except as consumed (any gap → fix in T1)." iOS cannot construct a Kotlin `Flow` from Swift, so — exactly like #34's `EntityObserve.kt` — a shared observe-adapter is required for iOS to feed `ObserveInventoryUseCase`. Added as the single, additive shared change (Android/Desktop don't use it). Approved with the developer before coding.
- **iOS duplicate-IMEI signalling via `NSError`.** A Kotlin exception can't be *thrown* from Swift (`KotlinThrowable` isn't a Swift `Error`), so the Swift repo aborts the transaction with an `NSError` (custom domain + a message marker) and the ViewModel detects it defensively. Atomicity is preserved (the transaction aborts before any write).
- **In-place IMEI edit not exposed in the UI.** T2's rules pin `imei` immutable on a `serials` update, so a mistyped IMEI is corrected by void (archive → release index) + re-add, per the schema. `updateSerial` still implements the documented re-key contract (for the rule-bypassing Desktop impl, T4) but the mobile test UI never drives it.
- **Fixed a reads-before-writes ordering bug** in `setSerialStatus` (return-to-stock branch) on both platforms — the `imeiIndex` existence read now happens before the serial write, as Firestore transactions require.
- **Milestone ticket docs committed.** `docs/tickets/M4-41-T1..T4-*.md` were untracked in the working tree; committed here so they aren't orphaned. They are documentation only (no code references them).

## Open questions / follow-ups
- **Real Inventory UI** is a separate later ticket (this UI is intentionally bare/test-only).
- **Desktop (T4)** implements the same interfaces via the Admin SDK; it will reuse `InventoryObserve.kt` only if it can't build Flows directly (it can, so likely won't).
- **Deferred per Brief #41:** Firebase scanner channel / cross-device hand-off / scan-to-sale-cart, bulk-scan, detailed grading — none included here.
- **Attribute rename backfill** (denormalized `name` in `Product.attributes`/`Serial.location` can go stale) remains a PO-accepted later concern.
