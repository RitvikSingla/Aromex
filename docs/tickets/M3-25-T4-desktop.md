---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M3] Profiles/Entities — T4: desktop (Admin-SDK repo impls + ViewModels + bare test UI)"
labels: []
assignees: []
---

**Brief:** #25

> **Milestone:** M3 — Profiles / Entities.
> **Ticket 4 of 4** (T1 shared logic → T2 backend spine → T3 mobile → **T4 desktop**).
> ⚠️ **Follow `/kmp-arch`.** Desktop is the odd platform: Firestore via the **Admin SDK**, so **Firestore
> rules DON'T apply** — permission enforcement rides entirely on the shared use cases.
> 🧪 **UI is BARE MINIMUM / test-only** (same as T3).

## 📖 Story / Why
Bring the Profiles/Entities feature to **Desktop (Compose-Desktop / JVM)** at parity with mobile. The twist:
Desktop reaches Firestore through the **Firebase Admin SDK with a service-account key fetched at runtime**
(PRD §5, §7.2), which **bypasses Firestore Security Rules**. That makes the **shared use cases the ONLY
line of permission enforcement on Desktop** — so this ticket must lean on T1's `profiles`-scope checks, not
on rules. Same async spine, same bare test UI as T3.

## 🧭 Context
**Desktop data access (different from mobile):**
- Firestore via **Admin SDK** (service account fetched post-auth from the gateway `firestoreToken` route; stored in OS secure storage). This bypasses rules — **the T2 `entities` rules are a mobile backstop only; on Desktop, enforcement = the shared `SaveEntityUseCase`/`ArchiveEntityUseCase` permission gates.**
- Admin-SDK writes **still trigger** the T2 `onEntityWrite` Cloud Function (triggers fire regardless of writer) — so the PENDING→SYNCED dual-write works identically on Desktop.
- HL balance reads use the **shared Ktor `EntityLedgerRepository`** (T1), same as mobile — Desktop provides a JVM `HlTokenProvider` + base URL via manual DI (Ktor's JVM engine is already wired in `sharedLogic`).

**Reuse:** `desktopApp` already has `HlLedgerRepository`, `AromexConfig`, `HomeViewModel`, and the
Admin-SDK/Firestore access from the desktop login work — model the new repo on those.

## 🧪 UI is test-only
Same bar as T3: plain Compose-Desktop components to **exercise** the feature (list · add/edit · detail ·
search/role-filter), showing name, roles, `syncStatus`, and net balance + direction. No theming, no
responsive/desktop-reflow polish, no dark mode — the real UI is a **later ticket**. Functional, not pretty.

## 🔑 Access & prerequisites
> Via the manager / secure channel. Never commit secrets.
- A working **desktop login** for the aromex-test company (so the session + `profiles` scope + service-account fetch + HL token path work).
- **T2 deployed** to the aromex-test Firebase project (Cloud Functions live) so writes sync PENDING→SYNCED.
- The gateway `firestoreToken`/service-account path already used by the existing desktop app; gateway + HL base URLs (public, in `AromexConfig`).
- **Service-account key never committed** — fetched at runtime, stored in OS secure storage (as the existing desktop app does).

## ✅ Scope / What to build
**Desktop** (`desktopApp`):
- [ ] `BackendEntityRepository` (Firestore **Admin SDK**) implementing the shared `EntityRepository`: `observeEntities()` via an Admin-SDK snapshot listener → `Flow<List<Entity>>`; `createEntity` (writes `syncStatus:"PENDING"`); `updateEntity`; `archiveEntity` (soft). Map Firestore ↔ shared models (roles UPPERCASE, `Timestamp` time fields).
- [ ] JVM `HlTokenProvider` impl (mirror the desktop HL-token path) + wire the shared `KtorEntityLedgerRepository` via manual DI.
- [ ] `EntitiesViewModel` + `EntityFormViewModel` (Compose-Desktop, `StateFlow`), same merge-by-`externalId` + client-side search/filter as mobile; `EntitiesFilter` enum in the VM layer.
- [ ] Bare Compose-Desktop screens (list · add/edit · detail) + navigation registration.
- [ ] **Permission enforcement via the shared use cases** — since rules don't apply here, ensure every write path goes through `SaveEntityUseCase`/`ArchiveEntityUseCase` (which check `profiles == MANAGE`); surface a plain "no permission" path for `view`/`none`.

## 🎯 Acceptance Criteria
- [ ] On Desktop: adding a party writes a `PENDING` doc via Admin SDK; the T2 Cloud Function fires and the list flips **PENDING→SYNCED** live (no manual refresh).
- [ ] Rows show **net balance + direction** read from HL via the **shared Ktor** client, merged by `externalId`.
- [ ] Opening balance, edit, and **soft archive** behave as on mobile; **Walk-in cannot be archived/renamed**.
- [ ] **Permission is enforced by the shared use cases** (not rules): a `profiles:view` desktop user cannot add/edit even though Admin SDK could technically write; `profiles:none` can't see the feature. (Explicitly verify this — it's the desktop-specific risk.)
- [ ] Search + role filter are **client-side**, no re-fetch; balances refresh on open/refresh.
- [ ] Money from **decimal strings**; roles **UPPERCASE**; time fields `Timestamp`.
- [ ] No service-account key or secret committed; `/kmp-arch` respected (native Compose-Desktop UI, manual DI, per-platform repo impl, shared Ktor read client consumed as-is).

## 🚫 Out of scope
- **Polished/production UI**, theming, dark mode, desktop responsive reflow — a **later UI ticket**.
- **Mobile** (**T3**), shared logic (**T1**), Cloud Functions/gateway/rules/provisioning (**T2**).
- Any client-side HL **write** (the CF owns writes).

## 🔗 Dependencies
- **T1** (shared contract + Ktor read client) and **T2** (Cloud Functions, provisioning). Best verified after T2 is deployed to aromex-test. Can follow **T3** (reuse the merge/filter VM logic), but does not strictly depend on it.

## 📚 References
- **Brief:** #25 · `docs/briefs/B25-profiles-entities.md`
- **PRD:** `docs/PRD.md` §5 (Desktop Admin SDK + caching), §7.2 (permissions enforced in shared logic — Desktop bypasses rules), §9.5
- **`/kmp-arch`** skill
- **Mirror:** `desktopApp/.../data/HlLedgerRepository.kt`, `AromexConfig.kt`, `desktopApp/.../ui/home/HomeViewModel.kt`; gateway `src/routes/firestoreToken.ts`
- **Design decisions:** memory `b25-profiles-entities-design`, `hl-compatibility-audit`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
