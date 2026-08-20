# Handoff — Ticket #43

**Ticket:** #43 — [M4] Inventory — T1: shared logic (generic Product/Serial/Attribute models + repo interfaces + use cases)
**Brief:** #41 · **Branch:** `ticket-43-inventory-shared-logic` (off `master`)

## Summary
Built the pure-Kotlin foundation for M4 Inventory in `sharedLogic` — the layer that Purchase (M5) and Sales
depend on. Added the generic data model (`Product` = SKU, `Serial` = unit, `AttributeValue` = managed
vocabulary) with the four enums, the deterministic `SkuKey` used as a product's identity, the two repository
**interfaces** (`InventoryRepository`, `AttributeRepository`) that document the race-safe transaction contract
for the platform impls (T3/T4) to implement, and nine permission-gated, input-validating use cases. A phone is
modeled as a `Product` with `trackingMode = SERIALIZED` — there is no "Phone" type (North Star). No Firestore
SDK, no UI, no scanner (those are T2–T4). 30 `commonTest` unit tests cover the SkuKey, enums, validation, and
permission gating; the full multiplatform build (Android + iOS + JVM) passes.

## Files changed (35 files, +1154, all additions under `sharedLogic/`)

**`model/` — enums**
- `TrackingMode.kt` — `SERIALIZED|QUANTITY|VARIANT|SERVICE`; only SERIALIZED used, others carried for POS retrofit.
- `Condition.kt` — `NEW|USED` unit condition.
- `SerialStatus.kt` — `IN_STOCK|RESERVED|SOLD` stock lifecycle (kept separate from the `isActive` soft-archive flag).
- `AttributeType.kt` — `BRAND|MODEL|CAPACITY|COLOR|CARRIER|LOCATION` with a **lowercase `wire`** + `SKU_DEFINING` (the 5, excluding LOCATION).

**`model/` — read models**
- `AttributeRef.kt` — `{attributeId, name}` denormalized pointer used inside products/serials.
- `AttributeValue.kt` — a managed-vocabulary row (`type`, `name`, `parentId` for model→brand, `isActive`).
- `Product.kt` — the SKU; `productId == skuKey`; generic `attributes` map; editable `defaultSellingPrice`; no stored stock count.
- `Serial.kt` — one physical unit (imei, per-unit cost, condition, status, location, `isActive`, `saleId`).

**`model/` — inputs + exception**
- `NewProduct.kt` / `NewUnit.kt` — add-stock inputs (SKU attrs vs per-unit fields; location is per-unit).
- `SerialEdits.kt` / `ProductEdits.kt` — edit inputs (only `defaultSellingPrice` is editable on a product).
- `AddStockResult.kt` — return of add-stock (productId + created serial ids).
- `DuplicateImeiException.kt` — the typed failure for an in-stock IMEI clash (thrown by the T3/T4 transaction, declared here).

**`util/`**
- `SkuKey.kt` — deterministic, order-independent join of the 5 SKU-attribute ids (why: atomic dedupe / no duplicate SKUs).
- `Imei.kt` — 14–16 digit sanity check reused by the add/update use cases.

**`repository/` — interfaces only**
- `InventoryRepository.kt` — observe/add/edit/archive; KDoc specifies the race-safe transaction (skuKey find-or-create + imeiIndex claim/release) so all platforms implement it identically.
- `AttributeRepository.kt` — observe vocab + deduped `addAttribute` (add-new-inline).

**`usecase/` — permission-gated business logic (depend only on interfaces)**
- `InventoryValidation.kt` — shared `inventory`-scope gates + unit/batch validation (why: enforced in shared logic, the only gate on Desktop).
- `AddStockUseCase.kt`, `AddUnitsUseCase.kt`, `UpdateUnitUseCase.kt`, `UpdateProductUseCase.kt`, `SetUnitStatusUseCase.kt`, `ArchiveUnitUseCase.kt`, `ArchiveProductUseCase.kt`, `ObserveInventoryUseCase.kt`, `AddAttributeUseCase.kt`.

**`commonTest/.../inventory/`**
- `InventoryTestFakes.kt` — fake repos + `sessionWith(inventory)` + attribute/unit builders.
- `SkuKeyTest.kt`, `InventoryEnumsTest.kt`, `AddStockUseCaseTest.kt`, `AddAttributeUseCaseTest.kt`, `UpdateProductUseCaseTest.kt`, `ObserveInventoryUseCaseTest.kt` — 30 tests total.

## How to test
```bash
git checkout ticket-43-inventory-shared-logic
# All targets compile (Android + iOS arm64/simulator + JVM):
./gradlew :sharedLogic:build
# The inventory unit suite (30 tests), fresh run:
./gradlew :sharedLogic:jvmTest --tests "com.humblesolutions.aromex.inventory.*" --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`, all 30 inventory tests green. Spot-checks: no platform/Firestore imports in
`commonMain`, no `expect`/`actual`, `repository/` is interfaces-only, money is `String` (no Double/Float),
enums serialize UPPERCASE `.name` except `AttributeType.wire` (lowercase).

## Acceptance criteria
- ✅ `:sharedLogic:build` + common tests pass on all targets (android/jvm/ios) — verified.
- ✅ Zero platform imports / no `expect`/`actual` / no Firebase SDK in common; `repository/` interfaces-only.
- ✅ Generic naming — `Product`/`Serial`/`AttributeValue`; no "Phone" type; `trackingMode` has all four modes, only SERIALIZED exercised.
- ✅ `productId == SkuKey.build(...)`, deterministic + order-independent; money is `String` (validated via `Money`).
- ✅ Use cases enforce the `inventory` scope; validations hold; `DuplicateImeiException` declared as the typed in-stock-IMEI failure.
- ✅ `InventoryRepository` KDoc expresses the transactional find-or-create + imeiIndex claim/release contract for T3/T4.
- ✅ Enums UPPERCASE `.name` except `AttributeType.wire` (lowercase) — asserted in `InventoryEnumsTest`.
- ✅ SKU default selling price editable (`UpdateProductUseCase`/`updateProduct`); attributes immutable (no attribute-edit path).
- ✅ Capacity is a managed `AttributeType.CAPACITY` (PO-approved, #41 review).

## Deviations / decisions
- **`@Throws` omitted on the suspend use cases.** The ticket suggested `@Throws(PermissionDeniedException::class)`,
  but Kotlin/Native rejects `@Throws` on a `suspend` function unless it also lists `CancellationException`. Followed
  the existing M3 convention instead: suspend use cases carry no `@Throws`; the annotation stays on
  `ObserveInventoryUseCase`'s non-suspend Flow methods (mirroring `ObserveEntitiesUseCase`, ticket #34). Behavior
  is unchanged — the permission gate still throws `PermissionDeniedException`.
- **`fromWire` returns nullable on all four enums** (unknown → null), per the ticket AC. Platform mappers choose the
  default when a stored value is missing/garbage.
- **`SkuKey` uses a fixed canonical order** (`AttributeType.SKU_DEFINING`) rather than map order, which is what makes
  the key order-independent; ids are joined with `_`.

## Post-review addition (manager review of PR #47)
- **Case-insensitive attribute dedupe added as a shared layer.** New `util/AttributeName` (`normalize` for the
  display name, `matchKey` for a case-folded dedupe key); `AttributeValue` gains a stored `nameKey`;
  `AttributeRepository.addAttribute` now dedupes on `(type, parentId, nameKey)` so `"Apple"`/`"apple"` collapse
  to one row. Defined once in shared code so all platforms fold identically (the picker is the first guard, this
  is the guarantee under it). Tests: `AttributeNameTest` + `caseVariant_dedupesToSameId` /
  `sameNameDifferentBrand_areDistinctModels` in `AddAttributeUseCaseTest`. `docs/SCHEMA.md` updated with `nameKey`.

## Open questions / follow-ups
- The Firestore **transaction** itself (skuKey find-or-create + imeiIndex atomicity/release) is **not** in T1 — it is
  declared as the interface contract and implemented + emulator-tested in **T2 (#44)** rules/indexes and **T3/#45 /
  T4/#46** repo impls.
- No `Product`-level `updateProduct` test beyond price validation (attributes are immutable by design; nothing to test there).
- Real Inventory UI is deferred to later tickets (only bare test UI, in T3/T4).
