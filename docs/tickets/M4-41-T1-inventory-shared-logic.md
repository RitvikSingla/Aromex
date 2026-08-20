---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M4] Inventory — T1: shared logic (generic Product/Serial/Attribute models + repo interfaces + use cases)"
labels: []
assignees: []
---

**Brief:** #41

> **Milestone:** M4 — Inventory + Scanner. **The heart is the data model.**
> **Ticket 1 of 4** (T1 shared logic → T2 Firebase spine → T3 mobile → T4 desktop). Mirrors M3's #26–#29.
> ⚠️ **Follow the `/kmp-arch` skill** for layering/naming (shared models → repo interfaces → use cases; no
> platform imports, no `expect`/`actual`, `repository/` is interfaces only). Run `/kmp-arch` before planning.
> 📐 **The schema is already designed and PO-approved — build to `docs/SCHEMA.md` Part 2 exactly.**

## 📖 Story / Why
Aromex needs to manage the **stock a shop holds** — the phones it buys and sells. M4 is the milestone the
whole money path stands on: **Purchase (M5) brings stock in, Sales takes it out**, and both reference this
model. Getting it right *now* is the point — it's built **generic** so a general POS retrofits without a
rewrite: a phone is a `Product` with `trackingMode = SERIALIZED` carrying a phone attribute set, **never a
hardcoded "Phone" entity**.

**This ticket (T1) builds the pure-Kotlin foundation everything else stands on:** the shared data models
(`Product` = SKU, `Serial` = unit, `AttributeValue` = managed vocabulary), the repository **interfaces**, and
the **use cases** (permission gating + validation + the deterministic `skuKey`). No Firestore SDK, no UI, no
scanner — those are T2–T4.

## 🧭 Context
**What already exists (reuse, don't recreate):**
- `sharedLogic/model/Permissions.kt` → `Permissions` already has an **`inventory: PermissionLevel`** field + `PermissionLevel { MANAGE, VIEW, NONE }`. Use it — do not add a new permission.
- `sharedLogic/model/UserSession.kt` → `UserSession` carries `permissions: Permissions`. **Pass this into use cases** for gating (enforced in shared logic per PRD §7.2, because Desktop's Admin SDK bypasses Firestore rules).
- `sharedLogic/util/Money.kt` (from M3) → `isValidPositiveDecimal(String)`, `isZero(String)`. **Reuse for cost / selling-price validation** — money is a decimal **String**, never Double/Float.
- The M3 shared-logic ticket (`docs/tickets/M3-25-T1-*`) is the structural template: enums with UPPERCASE wire names + `fromWire`, read model vs input model split, use cases that depend only on interfaces.

**The schema (source of truth = `docs/SCHEMA.md` Part 2). Three collections:**
- `products/{productId}` — the SKU. **`productId` IS the deterministic `skuKey`** (sorted 5 SKU-defining attribute IDs joined by `_`) → atomic find-or-create (PO decision #1).
- `serials/{serialId}` — one physical unit / one IMEI. FK `productId`. Per-unit `cost` (String), `condition` (NEW/USED), `status` (IN_STOCK/RESERVED/SOLD), `location`, `isActive` (soft-archive, **separate from** SOLD).
- `attributes/{attributeId}` — managed vocabularies, one collection discriminated by `type`, `parentId` wires model→brand.
- `imeiIndex/{imei}` — the **in-stock-only, race-safe** IMEI guard (PO #1+#2): written in the **same transaction** as its serial, **released** on sold/archive so a returned phone can be re-added.

**Locked decisions baked into this ticket (from the interview + PO review comment on #41):**
- **Repo split = `InventoryRepository` (products+serials+imeiIndex, transactionally linked) + `AttributeRepository` (vocabularies).**
- **Race-safe adds run as a CLIENT-SIDE Firestore transaction** (no Cloud Function) — the *impl* is per-platform (T3/T4); T1 defines the interface method + validation + `skuKey`.
- **Capacity is a 6th managed attribute type** (so `"128 GB"` vs `"128GB"` can't fragment the SKU). ⚠️ **This extends the brief's explicit managed-list (brand/model/color/carrier/location) — flagged for the PO at `/review-ticket`.**

## 🔑 Access & prerequisites
> Nothing secret. Pure shared Kotlin + unit tests — no live backend needed to build or test.
- The repo, JDK 11+, ability to run `./gradlew :sharedLogic:build` and the common tests.

## ✅ Scope / What to build
**`sharedLogic/model/` — enums (UPPERCASE wire == enum name; each with `fromWire`):**
- [ ] `TrackingMode { SERIALIZED, QUANTITY, VARIANT, SERVICE }` — only SERIALIZED is used now; the others exist for retrofit (North Star).
- [ ] `Condition { NEW, USED }`.
- [ ] `SerialStatus { IN_STOCK, RESERVED, SOLD }`.
- [ ] `AttributeType { BRAND, MODEL, CAPACITY, COLOR, CARRIER, LOCATION }` — carries a **lowercase `wire`** (`"brand"`…) used as the `attributes/` doc `type` and as the `Product.attributes` map key.

**`sharedLogic/model/` — data classes (defaults on all fields):**
- [ ] `AttributeRef(attributeId: String = "", name: String = "")` — the denormalized `{ attributeId, name }` pointer used inside products/serials.
- [ ] `AttributeValue(attributeId: String = "", type: AttributeType, name: String = "", parentId: String? = null, isActive: Boolean = true)` — a managed-vocabulary row. `parentId` holds a brand's `attributeId` when `type == MODEL`.
- [ ] `Product(productId: String = "", trackingMode: TrackingMode = TrackingMode.SERIALIZED, attributes: Map<AttributeType, AttributeRef> = emptyMap(), defaultSellingPrice: String = "0", isActive: Boolean = true)` — the SKU read model. `productId == skuKey`. **No stock count field** (computed).
- [ ] `Serial(serialId: String = "", productId: String = "", imei: String = "", cost: String = "0", condition: Condition = Condition.NEW, status: SerialStatus = SerialStatus.IN_STOCK, location: AttributeRef = AttributeRef(), isActive: Boolean = true, saleId: String? = null)` — one unit read model.

**`sharedLogic/model/` — input/draft models (what a user supplies, separate from read models):**
- [ ] `NewProduct(attributes: Map<AttributeType, AttributeRef>, defaultSellingPrice: String, trackingMode: TrackingMode = TrackingMode.SERIALIZED)` — the 5 SKU-defining attrs (brand/model/capacity/color/carrier); **location is NOT here** (it's per-unit).
- [ ] `NewUnit(imei: String, cost: String, condition: Condition, location: AttributeRef)`.
- [ ] `SerialEdits(cost: String? = null, condition: Condition? = null, location: AttributeRef? = null, imei: String? = null)` — for "correct details".
- [ ] `AddStockResult(productId: String, createdSerialIds: List<String>)`.

**`sharedLogic/util/`:**
- [ ] `SkuKey.build(attributes: Map<AttributeType, AttributeRef>): String` — deterministic: take the **5 SKU-defining** types (BRAND, MODEL, CAPACITY, COLOR, CARRIER) **in a fixed order**, join their `attributeId`s with `_`. **Order-independent** (same 5 attrs → same key regardless of map iteration). Reject if any of the 5 is missing/blank. (LOCATION is excluded — it's per-unit.) Result is id-safe (attribute ids are alphanumeric).

**`sharedLogic/repository/` (interfaces only — impls are per-platform in T3/T4):**
- [ ] `InventoryRepository`:
  - `fun observeProducts(includeArchived: Boolean): Flow<List<Product>>` — live list.
  - `fun observeInStockSerials(): Flow<List<Serial>>` — live; backs stock counts + drill-in (and Sales later). `status == IN_STOCK && isActive == true`.
  - `suspend fun addStock(skuKey: String, product: NewProduct, units: List<NewUnit>): AddStockResult` — **transactional**: find-or-create `products/{skuKey}`; for each unit, check `imeiIndex/{imei}` absent → create `serials/{id}` + `imeiIndex/{imei}`; **throw `DuplicateImeiException(imeis)`** if any IMEI is already in stock (atomic — nothing partially written).
  - `suspend fun addUnits(productId: String, units: List<NewUnit>): List<String>` — add units to an existing SKU (same imeiIndex transaction).
  - `suspend fun updateSerial(serialId: String, edits: SerialEdits)` — correct details; if `imei` changes, release the old `imeiIndex` + claim the new one **in one transaction**.
  - `suspend fun setSerialStatus(serialId: String, status: SerialStatus)` — RESERVED/SOLD/back to IN_STOCK; leaving stock (SOLD) **deletes** the `imeiIndex` entry in the same transaction; returning to stock re-claims it.
  - `suspend fun archiveSerial(serialId: String)` — `isActive = false` + **release** `imeiIndex` (transaction).
  - `suspend fun archiveProduct(productId: String)` — `isActive = false`.
- [ ] `AttributeRepository`:
  - `fun observeAttributes(): Flow<List<AttributeValue>>` — all vocab; filter by type/parent **in memory**.
  - `suspend fun addAttribute(type: AttributeType, name: String, parentId: String?): String` — **normalized dedupe**: returns the existing id if a same-`type` (and same-`parentId`) value with the normalized name exists, else creates one and returns the new id.
- [ ] `DuplicateImeiException(val imeis: List<String>) : Exception` (shared model/exception, mirroring `PermissionDeniedException`).

**`sharedLogic/usecase/` (constructor takes ONLY repo interfaces; pass `UserSession` to `execute`):**
- [ ] `AddStockUseCase(inventoryRepo)` — MANAGE-gated. Validates: all 5 SKU attributes present with non-blank ids; ≥1 unit; **no duplicate IMEI within the batch**; each `imei` non-blank (basic sanity — digits, length 14–16); each `cost` a valid positive decimal (`Money`); `defaultSellingPrice` valid; `condition` + `location` set. Builds `skuKey`, calls `addStock`.
- [ ] `AddUnitsUseCase(inventoryRepo)` — MANAGE-gated; same unit validation; adds to an existing `productId`.
- [ ] `UpdateUnitUseCase(inventoryRepo)` — MANAGE-gated; validates any provided edit.
- [ ] `SetUnitStatusUseCase(inventoryRepo)` — MANAGE-gated.
- [ ] `ArchiveUnitUseCase(inventoryRepo)` / `ArchiveProductUseCase(inventoryRepo)` — MANAGE-gated.
- [ ] `ObserveInventoryUseCase(inventoryRepo)` — VIEW-or-MANAGE-gated; exposes `observeProducts` + `observeInStockSerials` (the VM groups serials by `productId` for counts — **grouping/councount is client-side, not stored**).
- [ ] `AddAttributeUseCase(attributeRepo)` — MANAGE-gated; normalizes the name (trim + collapse internal whitespace); **requires `parentId` when `type == MODEL`** (model is filtered by brand).

**Tests (`commonTest`):**
- [ ] `SkuKey.build` is **order-independent** (two maps with the same 5 attrs in different order → identical key) and **rejects** a missing SKU attribute; location does not affect the key.
- [ ] `Condition`/`SerialStatus`/`TrackingMode`/`AttributeType` wire round-trip; unknown → null (and `AttributeType.wire` is lowercase).
- [ ] `AddStockUseCase`: throws when `inventory != MANAGE`; throws on missing SKU attr / no units / duplicate IMEI in batch / invalid cost / bad IMEI.
- [ ] `AddAttributeUseCase`: normalizes `" Apple "`→`"Apple"`; requires parent for MODEL; MANAGE-gated.
- [ ] `ObserveInventoryUseCase`: rejects `inventory == NONE`.

## 🎯 Acceptance Criteria
- [ ] `./gradlew :sharedLogic:build` + common tests pass on all targets (android/jvm/ios).
- [ ] `sharedLogic` common code has **zero platform imports**, **no `expect`/`actual`**, **no Firebase/Firestore SDK**; `repository/` is **interfaces only**.
- [ ] Naming is **generic** — `Product`/`Serial`/`AttributeValue`, `products`/`serials`/`attributes`; **no "Phone" type anywhere**. `trackingMode` present with all four modes; only SERIALIZED exercised.
- [ ] `productId == SkuKey.build(...)` and the key is deterministic + order-independent; money fields are decimal **Strings** validated via `Money` (no Double/Float).
- [ ] Every use case enforces the `inventory` scope from `UserSession`; the stated validations hold; `DuplicateImeiException` is the typed failure for a taken in-stock IMEI.
- [ ] The `InventoryRepository` interface expresses the **transactional** add/status/archive contract (find-or-create + imeiIndex claim/release) so T3/T4 implement it identically per `docs/SCHEMA.md`.
- [ ] Enums serialize UPPERCASE == name (except `AttributeType.wire`, deliberately lowercase for the `type` field).
- [ ] The **capacity-as-managed-type** extension of the brief's list is called out in code comments + this ticket for `/review-ticket`.

## 🚫 Out of scope
- Any **platform** code: Firestore impls, the actual transaction code, ViewModels, DI, UI, **the scanner** — T3 (mobile) / T4 (desktop).
- **Firestore security rules + composite indexes** — T2.
- **QUANTITY / VARIANT / SERVICE** behavior — the enum carries them; do not implement.
- **HL / money posting / inventory valuation** — Firebase-only feature; valuation is the open PRD §6.4 question handled with Purchase/Sales.
- Attribute **rename/archive/reorder** — only **add-new-inline** is in scope for M4 (rename backfill is a PO-accepted later concern).
- Real UI.

## 🔗 Dependencies
- None. **T2, T3, T4 depend on T1.**

## 📚 References
- **Brief:** #41 · **Schema (build to this):** `docs/SCHEMA.md` Part 2 + the ID-strategy table
- **PRD:** `docs/PRD.md` §9.6 (Inventory — IMEI-level), §9.7 (Scanner), §7.2 (permissions)
- **FEATURES:** `docs/FEATURES.md` §3 (phone line-item attributes) + §6 (legacy Brand→Model→Phone)
- **Template:** `docs/tickets/M3-25-T1-profiles-shared-logic.md` · **Skill:** `/kmp-arch`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
