---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M4] Inventory — T4: desktop (Admin-SDK repo impls + ViewModels + bare test UI)"
labels: []
assignees: []
---

**Brief:** #41

> **Milestone:** M4 — Inventory + Scanner.
> **Ticket 4 of 4** (T1 shared logic → T2 Firebase spine → T3 mobile → **T4 desktop**).
> ⚠️ **Follow `/kmp-arch`.** Desktop is the odd platform: Firestore via the **Admin SDK**, so **Firestore
> rules DON'T apply** — permission enforcement rides entirely on the shared use cases (T1).
> 🧪 **UI is BARE MINIMUM / test-only** (same bar as T3). **No scanner on Desktop — manual IMEI entry.**

## 📖 Story / Why
Bring Inventory to **Desktop (Compose-Desktop / JVM)** at parity with mobile (minus the camera). The twist,
same as M3's desktop: Desktop reaches Firestore through the **Firebase Admin SDK with a service-account key
fetched at runtime** (PRD §5, §7.2), which **bypasses Firestore Security Rules**. So T2's rules are a
**mobile backstop only** — on Desktop the **shared `AddStockUseCase` + permission gates (T1) are the ONLY
line of enforcement**. Same atomic transaction contract, same bare test UI as T3.

## 🧭 Context
**Desktop data access (different from mobile):**
- Firestore via the **Admin SDK** (service account fetched post-auth from the gateway `firestoreToken` route, stored in OS secure storage) — **bypasses rules**. Enforcement = the shared use cases' `inventory`-scope checks, **not** rules.
- The **race-safe add-stock transaction is a client-side Admin-SDK transaction** (`Firestore.runTransaction`): read `products/{skuKey}` → create if absent; per unit read `imeiIndex/{imei}` → present ⇒ abort with `DuplicateImeiException`, absent ⇒ create `serials/{autoId}` + `imeiIndex/{imei}`. Release/re-key the index on sold/archive/imei-correction per T2's contract + `docs/SCHEMA.md`.
- Live streams (`observeProducts`/`observeInStockSerials`) via Admin-SDK `addSnapshotListener` → `Flow` (mirror desktop's existing listener bridge).

**Reuse:** `desktopApp` already has `BackendEntityRepository` (Admin-SDK Firestore + transaction/listener
patterns), `AromexConfig`, `FirestoreUserRepository`, and Home/Entities ViewModels from the desktop work
(#19/#29) — **model the new repos + VMs on those.**

## 🧪 UI is test-only
Same bar as T3: plain Compose-Desktop components to **exercise** the feature — product list (SKU + in-stock
count + status) · drill-in to units (IMEI · cost · condition · status · location) · add-stock (pick attributes
with **add-new-inline**; add units by **manual IMEI entry**, cost, condition, location) · edit/archive a unit
and set status · archive a SKU · client-side search. **No scanner, no theming, no dark mode, no
desktop-reflow polish** — the real Inventory UI is a **later ticket**. Functional, not pretty.

## 🔑 Access & prerequisites
> Via the manager / secure channel. Never commit secrets.
- A working **desktop login** for the aromex-test company (so the session + `inventory` scope + service-account fetch path work) — reuse the M3 desktop login.
- T2's **indexes deployed** to aromex-test (Admin-SDK queries still need composite indexes even though rules are bypassed).
- No camera, no HL, no gateway changes needed.

## ✅ Scope / What to build
- [ ] `desktopApp/.../data/BackendInventoryRepository` + `BackendAttributeRepository` implementing the T1 interfaces with the **Admin SDK** — client transactions for add/status/archive per T2's contract; snapshot-listener Flows; `AttributeRepository.addAttribute` normalized dedupe.
- [ ] `InventoryListViewModel` (live products + in-stock serials; **group by `productId` in memory** for counts; client-side search) + `AddStockViewModel` (attribute pickers + add-new-inline + unit builder with **manual IMEI**; `DuplicateImeiException` surfaced). Manual DI, `StateFlow`, `viewModelScope` (mirror desktop's existing VMs).
- [ ] **Bare test UI** (Compose-Desktop): list → drill-in → add-stock (manual IMEI) → edit/archive/set-status, reachable from where the desktop entities test screen is.

## 🎯 Acceptance Criteria
- [ ] End-to-end on Desktop: add an iPhone SKU, add 3 IMEIs (typed) → list shows **"3 in stock"** with per-unit cost/condition; **re-adding the same model groups under the same SKU** (no duplicate).
- [ ] A **duplicate in-stock IMEI** is rejected (`DuplicateImeiException`) with nothing partially written; **sold/archived releases the IMEI** so it can be re-added (PO #2).
- [ ] **Permission enforcement works with rules bypassed** — a session whose `inventory != MANAGE` cannot add/edit (blocked by the T1 use case, proven on Desktop specifically).
- [ ] **add-new-inline** works (model filtered by brand); stock counts + search are computed **client-side** over the cached lists (no re-fetch).
- [ ] Repos use the **Admin SDK**, implement the T1 interfaces, and match T2's transaction contract; no shared UI, no `expect`/`actual`.
- [ ] `sharedLogic` untouched except as consumed (gaps → fix in T1).

## 🚫 Out of scope
- **Android / iOS** — T3.
- **Scanner / camera** — Desktop is manual entry (PRD §4: Desktop receives a mobile scan later via the deferred hand-off channel).
- **Real/polished UI**, theming, dark mode, desktop reflow — later ticket.
- Cloud Functions / HL / valuation — not part of M4.

## 🔗 Dependencies
- **T1 (#43)** (models/interfaces/use cases) and **T2 (#44)** (indexes deployed) must be in. Can run in **parallel with T3**.
- Builds on the desktop Admin-SDK/login foundation from **#19** and the desktop entities work **#29**.

## 📚 References
- **Brief:** #41 · **Schema + transaction contract:** `docs/SCHEMA.md` Part 2
- **PRD:** `docs/PRD.md` §9.6 (Inventory), §7.2 (permissions), §5 (Desktop Admin-SDK access)
- **Prior art:** `docs/tickets/M3-25-T4-desktop.md`, `desktopApp/.../data/BackendEntityRepository.kt`, `handoffs/ticket-19.md`, `handoffs/ticket-29.md`
- **Skill:** `/kmp-arch`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
