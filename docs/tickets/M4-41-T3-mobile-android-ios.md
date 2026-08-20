---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M4] Inventory — T3: mobile (Android + iOS) repo impls + ViewModels + scan-to-add + bare test UI"
labels: []
assignees: []
---

**Brief:** #41

> **Milestone:** M4 — Inventory + Scanner.
> **Ticket 3 of 4** (T1 shared logic → T2 Firebase spine → **T3 mobile** → T4 desktop).
> ⚠️ **Follow `/kmp-arch`** (per-platform impls of the shared interfaces, manual DI, no framework). Build
> **Android first as the reference**, then mirror iOS natively.
> 🧪 **UI is BARE MINIMUM / test-only** — see "UI is test-only" below.

## 📖 Story / Why
T1 gave us the shared contract; T2 gave us the rules + indexes + the race-safe transaction spec. **T3 wires
the two mobile platforms to it:** implement `InventoryRepository` + `AttributeRepository` with each platform's
Firebase SDK (**running the atomic add-stock transaction client-side**), build the ViewModels (live product +
in-stock-serial streams, client-side grouping/search), and add the **"+ Scanner" half of the milestone** —
**camera → IMEI barcode → fills the add-unit field**. A **bare, unstyled UI** drives the whole path:
**add a phone SKU, scan 3 IMEIs → it shows "3 in stock," each unit with its own cost/condition; adding the
same model again groups under the same SKU.** Polished UI is a **separate later ticket**.

## 🧭 Context
**Per-platform work (Android in Kotlin/Compose, iOS in Swift/SwiftUI) — each implements the SAME T1 interfaces
using its native SDKs. Model the new repos on the existing `BackendEntityRepository` on each platform:**
- Android: `androidApp/.../data/BackendEntityRepository.kt` (Firestore KTX, `snapshotListener`→`callbackFlow`, `withContext(Dispatchers.IO)`, `FieldValue.serverTimestamp()`, doc→model mapping) — **the reference pattern.**
- iOS: `iosApp/iosApp/repository/BackendEntityRepository.swift` (Firestore Swift SDK).

**`InventoryRepository` impl — the atomic transaction is the crux (per T2's contract + `docs/SCHEMA.md`):**
- `addStock` / `addUnits` run inside a **Firestore client transaction** (Android `db.runTransaction { }`, iOS `db.runTransaction`): read `products/{skuKey}` → create if absent; per unit read `imeiIndex/{imei}` → if present abort with `DuplicateImeiException(imeis)`, else create `serials/{autoId}` + `imeiIndex/{imei}`. `serialId` is a Firestore auto-id **stored in the doc**; `productId` is the skuKey.
- `setSerialStatus`(SOLD)/`archiveSerial`/`updateSerial`(imei change) run transactions that **release/re-key `imeiIndex`** per the contract.
- `observeProducts` / `observeInStockSerials` are **snapshot listeners** → `Flow` (Android) / an observation bridge (iOS — reuse the M3 SKIE/Flow path from **#34**, `ticket-34-skie-adoption`).
- `AttributeRepository.addAttribute` does the **normalized dedupe query** (same type[+parent], normalized name) → return existing id or create.

**ViewModels (per platform, manual DI, no framework — mirror M3's mobile VMs):**
- Android `AndroidViewModel` + `StateFlow`; iOS `@MainActor ObservableObject` + `@Published`.
- On init: subscribe to `ObserveInventoryUseCase` (products **and** in-stock serials). **Group serials by `productId` in memory** → each SKU's stock count. Search/filter (brand/model/IMEI/color/carrier) run **client-side over the in-memory list** — no re-fetch (`/kmp-arch` caching).
- Add-stock VM: holds the picked attributes (with **add-new-inline** via `AddAttributeUseCase`), the unit list being built, and the **scanned IMEI** feeding the current unit; calls `AddStockUseCase`; surfaces `DuplicateImeiException` as a field error.

**Scanner (the "+ Scanner" half — LOCAL capture only):**
- **Android:** CameraX preview + **Google ML Kit barcode scanning** (`com.google.mlkit:barcode-scanning`) — capture-frame model; on a detected barcode, return the string and dismiss. Handle the **CAMERA runtime permission**. Add ML Kit + CameraX to `androidApp/build.gradle.kts` + `gradle/libs.versions.toml` (GMS is already present via Firebase).
- **iOS:** **AVFoundation** `AVCaptureSession` + `AVCaptureMetadataOutput` (code128/EAN — IMEI labels). Add the **`NSCameraUsageDescription`** Info.plist key. (Deployment target is 18.2, but AVFoundation keeps us version-agnostic.)
- Scanned value **only fills the IMEI field** in add-unit. **No Firebase scanner channel, no cross-device hand-off, no scan-to-sale-cart** — those are deferred (Brief #41 🧊).
- Put the platform camera behind a small **`ImeiScanner` seam** so the ViewModel/UI just receives an IMEI string (keeps camera SDKs out of shared + testable UI).

**Reuse, don't recreate:** login/session, `AromexConfig`, `firestoreFor(config)`, the Firestore-listener→Flow
bridge, and manual-DI wiring already exist in `androidApp`/`iosApp` from M1–M3 — model the new code on them.

## 🧪 UI is test-only (READ THIS)
The manager has **deferred real UI**. Build the **minimum** native UI to exercise + verify the feature:
- A **product list** showing each SKU (brand·model·capacity·color·carrier) + **in-stock count** + status.
- **Drill-in** to a SKU → its units (IMEI · cost · condition · status · location).
- **Add stock**: pick attributes (with add-new-inline), add units (type **or scan** the IMEI, cost, condition, location), save.
- **Edit/archive** a unit (incl. set status) and **archive** a SKU.
No theming, no dark mode, no responsive polish, no empty/loading art — plain components that make the path
runnable. The **real Inventory UI is a later ticket**. Functional, not pretty.

## 🔑 Access & prerequisites
> Via the manager / secure channel. Never commit secrets.
- A working **mobile login** for the aromex-test company (session with `inventory` scope) — reuse the M3 test login.
- T2's **rules + indexes deployed** to aromex-test (so mobile writes/queries are permitted).
- A **physical device or emulator with a camera** (or a printed / on-screen Code128 IMEI barcode) to test scanning; the emulator's virtual camera can display a barcode image.
- Android: `google-services.json` already in place; iOS: `GoogleService-Info.plist` already in place (from onboarding).

## ✅ Scope / What to build
- [ ] **Android** `BackendInventoryRepository` + `BackendAttributeRepository` (Firestore KTX; client transactions for add/status/archive per T2 contract; snapshot-listener Flows).
- [ ] **iOS** `BackendInventoryRepository` + `BackendAttributeRepository` (Firestore Swift SDK; same transaction contract; Flow observation via the #34 path).
- [ ] **Android** `ImeiScanner` (CameraX + ML Kit) + CAMERA permission flow; add deps to the version catalog.
- [ ] **iOS** `ImeiScanner` (AVFoundation) + `NSCameraUsageDescription`.
- [ ] **Android + iOS** ViewModels: `InventoryListViewModel` (live products+serials, in-memory grouping/search) and `AddStockViewModel` (attribute pickers + add-new-inline + unit builder + scan + `DuplicateImeiException` handling). Manual DI.
- [ ] **Bare test UI** on both platforms: list → drill-in → add-stock (with scan) → edit/archive, per "UI is test-only".
- [ ] Wire navigation to reach the inventory test screen from wherever M3's entities test screen is reachable.

## 🎯 Acceptance Criteria
- [ ] End-to-end on **both** Android and iOS: add an iPhone SKU, **scan 3 IMEIs** → list shows **"3 in stock"**; each unit carries its own cost + condition; **adding the same model again groups under the same SKU** (no duplicate SKU row).
- [ ] Adding a **duplicate in-stock IMEI** is rejected with a clear field error (surfaced from `DuplicateImeiException`) and **nothing partial is written** (atomic).
- [ ] Selling/archiving a unit **releases its IMEI** — the same IMEI can be **re-added** as a fresh in-stock unit afterward (PO #2).
- [ ] **add-new-inline** works: typing a brand/model/capacity/color/carrier/location that doesn't exist creates it (model list is **filtered by the picked brand**) and it's immediately usable.
- [ ] Stock counts + search (brand/model/IMEI/color/carrier) are computed **client-side over the cached lists** — no re-fetch on filter (`/kmp-arch`).
- [ ] The camera scan **fills the IMEI field** on both platforms; camera permission is requested/handled; denial degrades to manual entry.
- [ ] Repos implement the T1 interfaces with **native SDKs**; permission enforcement rides the T1 use cases; transactions match T2's contract. No shared UI, no `expect`/`actual`.
- [ ] `sharedLogic` is untouched except as consumed (any gap → fix in T1, not here).

## 🚫 Out of scope
- **Desktop** — T4.
- **Real/polished UI**, theming, dark mode, responsive layout — a later ticket.
- **Firebase scanner channel**, cross-device hand-off (phone→desktop), **scan-to-add-to-sale-cart** — deferred (Brief #41 🧊).
- **Bulk-scan**, detailed grading (A/B/C, battery %) — deferred.
- Cloud Functions / HL / valuation — not part of M4.

## 🔗 Dependencies
- **T1 (#43)** (models/interfaces/use cases) and **T2 (#44)** (rules + indexes deployed) must be in.
- Reuses the **#34** SKIE/Flow-observation path on iOS.

## 📚 References
- **Brief:** #41 · **Schema + transaction contract:** `docs/SCHEMA.md` Part 2
- **PRD:** `docs/PRD.md` §9.6 (Inventory), §9.7 (Scanner), §7.2 (permissions)
- **FEATURES:** `docs/FEATURES.md` §6 (Inventory UX), §7 (Scanner) — behavior reference for the *later* real-UI ticket
- **Prior art:** `docs/tickets/M3-25-T3-mobile-android-ios.md`, `androidApp/.../data/BackendEntityRepository.kt`, `iosApp/iosApp/repository/BackendEntityRepository.swift`, memory `ticket-34-skie-adoption`
- **Skill:** `/kmp-arch`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
