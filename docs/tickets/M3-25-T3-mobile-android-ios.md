---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M3] Profiles/Entities — T3: mobile (Android + iOS) repo impls + ViewModels + bare test UI"
labels: []
assignees: []
---

**Brief:** #25

> **Milestone:** M3 — Profiles / Entities.
> **Ticket 3 of 4** (T1 shared logic → T2 backend spine → **T3 mobile** → T4 desktop).
> ⚠️ **Follow `/kmp-arch`** for layering/naming/manual-DI. Build **Android first as the reference**, then
> mirror iOS natively.
> 🧪 **UI is BARE MINIMUM / test-only** — see "UI is test-only" below.

## 📖 Story / Why
T1 gave us the shared contract; T2 gave us the server-side spine (HL create + dual-write + reconcile).
T3 wires the **two mobile platforms** to it: implement the shared repository interfaces with each platform's
Firebase SDK, build the ViewModels (live entity listener + HL balance merge), and put a **bare, unstyled UI**
in front so the whole path is exercisable end-to-end — **add a party → it appears PENDING → flips SYNCED →
its net balance shows**. Polished UI is a **separate later effort**; this ticket only needs enough UI to
drive and verify the feature.

## 🧭 Context
**Per-platform work (Android in Kotlin/Compose, iOS in Swift/SwiftUI) — each implements the SAME shared
interfaces from T1 using its native SDKs:**
- `EntityRepository` (Firestore): `observeEntities()` backed by a **Firestore snapshot listener** → emits `Flow`/publishes; `createEntity` writes the doc with `syncStatus:"PENDING"` (T2's Cloud Function does the HL create — the client never calls HL to write); `updateEntity`; `archiveEntity` (soft, `isActive=false`).
- `HlTokenProvider` impl (reuse the existing mobile pattern): fresh Firebase **ID token** → gateway `POST /hl-token` → cache ~15 min → refresh on expiry/401. Android already has `HlTokenRepository`/`HlLedgerRepository` to mirror; iOS mirrors it in Swift.
- The **shared Ktor `EntityLedgerRepository`** (from T1) is consumed as-is for balance reads — each platform just provides the `HlTokenProvider` + base URL via manual DI (the Ktor engine is already wired per-target in `sharedLogic`).

**ViewModels (per platform, manual DI, no framework):**
- Android: `AndroidViewModel` + `StateFlow`; iOS: `@MainActor ObservableObject` + `@Published`.
- On init: subscribe to `ObserveEntitiesUseCase` (live list) **and** fetch balances once via `GetEntityBalancesUseCase`; **merge by `externalId`** in the VM. Refresh balances on appear / pull-to-refresh / after a save. Search/filter (name·phone·role) run **client-side over the in-memory list** (no re-fetch).
- Detail: fetch a fresh single balance (`getBalance(externalId)`).

**Reuse, don't recreate:** login/session, `HlTokenProvider` pattern, config (`AromexConfig`), and the existing HL-read repos are already in `androidApp` — model the new repos on them.

## 🧪 UI is test-only (READ THIS)
The manager has explicitly deferred real UI. Build the **minimum** to exercise the feature — plain native
components, no theming/polish/brand work:
- **List:** entities with name, roles, `syncStatus` (PENDING/SYNCED/FAILED), and net balance + direction (a color or a `+/–` is enough — no design system).
- **Add/Edit:** a plain form (name, phones, email, address, role checkboxes, notes, optional opening amount + direction).
- **Detail:** the party's fields + current net balance.
- A basic search box + role filter.
Loading/empty/error can be plain text. **Do NOT** invest in layout, theming, dark mode, insets, animation —
that's the later UI ticket. This ticket's bar is *functional*, not *pretty*.

## 🔑 Access & prerequisites
> Via the manager / secure channel. Never commit secrets.
- A working **mobile login** whose company has an HL company (the aromex-test user) — so the session + `profiles` permission + HL token path work.
- **T2 deployed** to the aromex-test Firebase project (Cloud Functions live) so writes actually sync PENDING→SYNCED. If T2 isn't deployed yet, coordinate with the manager.
- Android: the existing app builds/runs. iOS: Xcode + the `iosApp` project builds; the `SharedLogic` framework (with Ktor) links.
- Gateway + HL base URLs (public, already in `AromexConfig`/iOS config).

## ✅ Scope / What to build
**Android** (`androidApp`):
- [ ] `BackendEntityRepository` (Firestore SDK): snapshot listener → `Flow<List<Entity>>`; `createEntity` (writes `PENDING`); `updateEntity`; `archiveEntity`. Map Firestore ↔ shared models (roles UPPERCASE, Firestore `Timestamp` for time fields).
- [ ] Provide `HlTokenProvider` + base URL to the shared `KtorEntityLedgerRepository` via manual DI in the VM.
- [ ] `EntitiesViewModel` (list: live entities + merged balances, client-side search/filter) + `EntityFormViewModel` (add/edit via `SaveEntityUseCase`) + detail state (single balance). Enum `EntitiesFilter` in the VM layer (not shared).
- [ ] Bare Compose screens (list · add/edit · detail) + navigation registration.

**iOS** (`iosApp`):
- [ ] `BackendEntityRepository` (Firestore Swift SDK) implementing the shared interface — same behavior as Android.
- [ ] `HlTokenProvider` impl in Swift (ID token → gateway `/hl-token` → cache/refresh), mirroring Android.
- [ ] `EntitiesViewModel` + `EntityFormViewModel` (`@MainActor ObservableObject`), same merge/filter logic.
- [ ] Bare SwiftUI views (list · add/edit · detail) + `NavigationStack` wiring.

**Both:**
- [ ] Permission-aware behavior: read the `profiles` scope from the session; the use cases already enforce MANAGE for writes — surface a plain "no permission" path.
- [ ] Verify the async spine end-to-end on a device/simulator (see AC).

## 🎯 Acceptance Criteria
- [ ] On **both** Android and iOS: adding a party writes a `PENDING` doc; the list shows it **PENDING**, then it flips to **SYNCED** live (T2's CF) with **no manual refresh** — the live listener works.
- [ ] Each row shows the party's **net balance + direction** (green they-owe-you / red you-owe-them / neutral settled), read from HL via the **shared Ktor** client and merged by `externalId`.
- [ ] Adding an **opening balance** results in the expected net balance appearing after sync.
- [ ] Editing a party updates Firestore (and HL name/email/phone via the CF); **archiving** removes it from the active list (soft delete); **Walk-in cannot be archived/renamed**.
- [ ] Search + role filter run **client-side** with no re-fetch; a re-open/pull refreshes balances.
- [ ] Money is rendered from **decimal strings** (no float); roles persist as **UPPERCASE**; time fields are Firestore `Timestamp`.
- [ ] A `profiles:view` user can browse but not add/edit; a `profiles:none` user can't see the feature.
- [ ] `/kmp-arch` respected: native UI per platform (no shared UI), manual DI, repo impls per platform, shared Ktor read client consumed as-is.

## 🚫 Out of scope
- **Polished/production UI**, theming, dark mode, insets, responsive layout, animation, brand kit — a **separate later UI ticket** owns all of it. T3 UI is throwaway-grade test scaffolding.
- **Desktop** — that's **T4**.
- Shared models/use cases (**T1**), Cloud Functions/gateway/rules/provisioning (**T2**).
- Any client-side HL **write** (the CF owns writes).

## 🔗 Dependencies
- **T1** (shared contract + Ktor read client) and **T2** (Cloud Functions so writes sync; rules; Walk-in provisioned). T3 needs both; ideally T2 is deployed to aromex-test before verifying the async path.

## 📚 References
- **Brief:** #25 · `docs/briefs/B25-profiles-entities.md`
- **PRD:** `docs/PRD.md` §9.5, §7.2 (permissions), §5 (caching/live-read guidance)
- **`/kmp-arch`** skill (layering + caching strategy authority)
- **Mirror:** `androidApp/.../data/HlTokenRepository.kt`, `HlLedgerRepository.kt`, `AromexConfig.kt`; `androidApp/.../ui/home/HomeViewModel.kt`
- **Design decisions:** memory `b25-profiles-entities-design`, `hl-compatibility-audit`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
