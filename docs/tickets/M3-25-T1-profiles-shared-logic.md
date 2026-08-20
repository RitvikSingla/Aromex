---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M3] Profiles/Entities — T1: shared logic (models + repo interfaces + use cases + shared Ktor HL read client)"
labels: []
assignees: []
---

**Brief:** #25

> **Milestone:** M3 — Profiles / Entities (the HL dual-write spine).
> **Ticket 1 of 4** (T1 shared logic → T2 backend spine → T3 mobile Android+iOS → T4 desktop).
> ⚠️ **Follow the `/kmp-arch` skill** for layering/naming — with ONE blessed deviation called out below
> (HL access is a **shared Ktor** client, not a per-platform impl). Run `/kmp-arch` before planning.

## 📖 Story / Why
M3 builds the ability to manage the people/businesses Aromex buys from and sells to as **one unified
`Entity` (party)** with multi-select roles (customer/supplier/middleman) that are **non-binding labels**,
each backed by exactly **one Humble Ledger customer account** so buying + selling to the same party **net
into a single balance**. M3 is also **the reusable money spine** — the first HL write + HL↔Firebase
dual-write + idempotency — that Purchase/Sales/Transactions all copy.

**This ticket (T1) builds the pure-Kotlin foundation everything else stands on:** the shared data models,
the repository **interfaces**, the **use cases** (with permission enforcement + validation), the money/sign
utilities, and the **shared Ktor client that reads balances from HL** (identical across all three
platforms). No UI, no Firestore SDK code, no Cloud Functions — those are T2–T4.

## 🧭 Context
**What already exists (reuse, don't recreate):**
- `sharedLogic/model/Permissions.kt` → `Permissions` (has a `profiles: PermissionLevel` field) + `PermissionLevel { MANAGE, VIEW, NONE }`.
- `sharedLogic/model/UserSession.kt` → `UserSession` carries `permissions: Permissions` — **pass this into use cases** for permission gating (enforced in shared logic per PRD §7.2, because Desktop's Admin SDK bypasses Firestore rules).
- `sharedLogic/repository/HlTokenProvider.kt` → `suspend currentToken()` / `suspend invalidate()`. **The HL read client consumes this** for its bearer token — it must not know a Firebase-ID-token + gateway POST sits behind it. Its impl is per-platform and comes in T3/T4.
- Existing HL read pattern to mirror for parsing/retry semantics: `androidApp/.../data/HlLedgerRepository.kt` (OkHttp balance-sheet read, 401-invalidate-retry-once, money-as-String). **T1 does the HL read with Ktor in shared instead of OkHttp per-platform.**

**HL contract (verified live at `https://ledger.humblesolutions.in/docs`, OpenAPI at `/docs/json`):**
- `GET /api/v1/customers?limit=…&externalId=…` → `{ success, data: [ Customer ], meta:{…} }` — **one call returns every party's balance.** Join to our entities by `externalId` (== the Aromex entity id).
- `GET /api/v1/customers/{id}` → a single `Customer`.
- `Customer` fields: `id`, `accountId`, `name`, `email`, `phone`, `externalId`, `isActive`, and the **balance** as a signed decimal **string** — *"Positive = customer owes money (RECEIVABLE). Negative = customer has credit / we owe them (CREDIT). Zero = settled."* (Field is `outstanding` today; HL is standardizing on `balance` — read defensively: accept whichever is present.)

**Money & vocabulary rules (from `CLAUDE.md` + Brief #25):**
- **Money is decimal strings, never float** in shared/UI. Any string→number conversion happens ONLY at the HL request boundary (not in T1 — T1 only reads).
- Our balance vocabulary is **`RECEIVABLE / CREDIT / SETTLED`** (per the brief). HL's opening-balance endpoint uses `PAYABLE` for "we owe them" → map `PAYABLE ↔ CREDIT` at the boundary (relevant in T2, not T1).

**⚠️ Blessed architecture deviation (flag for `/review-ticket`):** `CLAUDE.md` says `sharedLogic/repository/`
is **interfaces only** and implementations are **per-platform**. The manager has decided **HL API access uses
Ktor and lives in shared code** (one client for Android/iOS/Desktop, since HL REST is identical everywhere).
So the `EntityLedgerRepository` **interface** lives in `repository/`, and its **Ktor implementation** lives in
a shared **`data/`** package (keeping `repository/` interfaces-only). Zero platform imports remain in common
code — the platform **HttpClient engine is injected via manual DI** (no `expect`/`actual`). Recommend
updating `CLAUDE.md` to record "network/HL access is a shared Ktor client."

## 🔑 Access & prerequisites
> Nothing secret. This ticket is pure shared Kotlin + unit tests — no live backend needed to build or test.
- The repo, JDK 11+, and the ability to run `./gradlew :sharedLogic:build` and the common tests.
- (Optional, for a sanity read against real HL) HL test login `aromex-test@yourco.com` — password via the manager/secure channel. Not required to complete T1.

## ✅ Scope / What to build
**Build setup (first time shared networking is added):**
- [ ] Add to `gradle/libs.versions.toml` + `sharedLogic/build.gradle.kts` `commonMain`: `kotlinx-coroutines-core` (for `Flow`), `kotlinx-serialization-json`, and **Ktor client** (`client-core`, `content-negotiation`, `serialization-kotlinx-json`, `logging`). Apply the `kotlinx-serialization` plugin.
- [ ] Add the **Ktor engine per target sourceSet** *in `sharedLogic`* (`androidMain`/`jvmMain` → `ktor-client-okhttp`, `iosMain` → `ktor-client-darwin`) and build the `HttpClient` **inside** the shared client (no engine passed in). This keeps the HL client fully self-contained in shared and spares Swift/iOS from constructing engines — **no `expect`/`actual`** (Ktor auto-selects the single engine on each target's classpath).

**`sharedLogic/model/`:**
- [ ] `EntityRole { CUSTOMER, SUPPLIER, MIDDLEMAN }` — wire spelling == enum name (UPPERCASE); helper `fromWire(String): EntityRole?`.
- [ ] `HlSyncStatus { PENDING, SYNCED, FAILED }`.
- [ ] `BalanceDirection { RECEIVABLE, CREDIT, SETTLED }` with `companion fun fromBalance(balance: String): BalanceDirection` (sign-only; see util).
- [ ] `EntityBalance(net: String, direction: BalanceDirection)` — `net` is the absolute decimal string.
- [ ] `OpeningBalance(amount: String, direction: BalanceDirection)` — `amount` positive decimal string; `direction` restricted to RECEIVABLE or CREDIT (validate).
- [ ] `Entity(id, name, phones: List<String>, email: String?, address: String?, roles: Set<EntityRole>, notes: String?, isWalkIn: Boolean, isActive: Boolean, hlCustomerId: String?, hlAccountId: String?, syncStatus: HlSyncStatus)` — read model, defaults on all fields. **No balance field** (balance is read live from HL, never stored).
- [ ] `EntityInput(name, phones, email?, address?, roles: Set<EntityRole>, notes?, opening: OpeningBalance? = null)` — create/edit input (opening only meaningful on create).

**`sharedLogic/repository/` (interfaces only):**
- [ ] `EntityRepository` (Firestore-backed; impl per-platform in T3/T4):
  - `fun observeEntities(): Flow<List<Entity>>` — live list (backs PENDING→SYNCED UX).
  - `suspend fun createEntity(input: EntityInput): String` — writes the operational doc with `syncStatus = PENDING`; returns the new entity id (== HL externalId). Does **not** call HL (the T2 Cloud Function does).
  - `suspend fun updateEntity(id: String, input: EntityInput)`.
  - `suspend fun archiveEntity(id: String)` — soft archive (`isActive = false`).
- [ ] `EntityLedgerRepository` (HL reads; **shared Ktor impl** in `data/`):
  - `suspend fun getBalances(): Map<String, EntityBalance>` — keyed by `externalId`; one bulk `GET /customers`.
  - `suspend fun getBalance(externalId: String): EntityBalance?` — single read for the detail screen.

**`sharedLogic/data/` (the blessed shared impl):**
- [ ] `KtorEntityLedgerRepository(tokenProvider: HlTokenProvider, baseUrl: String) : EntityLedgerRepository` — builds its own `HttpClient` from the per-target engine; Ktor calls to HL, `Authorization: Bearer <currentToken()>`; parse `Customer` DTO → `EntityBalance` (read `balance` or `outstanding`, whichever present; derive `direction` + absolute `net`); **on 401 → `invalidate()` + retry once** (mirror the existing OkHttp repo's semantics). Throw the shared HL error type on transport/auth/upstream failures.

**`sharedLogic/usecase/`:**
- [ ] `SaveEntityUseCase(entityRepo)` — `execute(session: UserSession, input: EntityInput, existingId: String? = null): String`. Requires `session.permissions.profiles == MANAGE` (else throw); validates name non-blank, ≥1 phone, valid email if present, `opening` amount is a valid **positive** decimal string with direction RECEIVABLE|CREDIT; create vs update by `existingId`.
- [ ] `ObserveEntitiesUseCase(entityRepo)` — requires `profiles` ∈ {VIEW, MANAGE}; returns `observeEntities()` (optionally filtered to `isActive`).
- [ ] `GetEntityBalancesUseCase(ledgerRepo)` — `execute(): Map<String, EntityBalance>` (view-gated); the merge with entities happens in platform ViewModels.
- [ ] `ArchiveEntityUseCase(entityRepo)` — MANAGE-gated; **rejects archiving a reserved Walk-in entity** (`isWalkIn == true`).

**`sharedLogic/util/`:**
- [ ] `Money` — `isValidPositiveDecimal(s: String): Boolean`, `isZero(s: String): Boolean`, and a sign helper used by `BalanceDirection.fromBalance` (compare sign/zero **without** parsing to Double/Float).

**Tests (`commonTest`):**
- [ ] `BalanceDirection.fromBalance`: `"500.00"`→RECEIVABLE, `"-500.00"`→CREDIT, `"0"`/`"0.00"`/`"-0.00"`→SETTLED, leading `+`, whitespace.
- [ ] `EntityRole.fromWire` round-trips; unknown → null.
- [ ] `Money.isValidPositiveDecimal` accepts/rejects the right strings.
- [ ] `SaveEntityUseCase`: throws when `profiles != MANAGE`; throws on blank name / no phone / invalid opening amount / opening direction == SETTLED.
- [ ] `ArchiveEntityUseCase`: throws on a Walk-in entity; requires MANAGE.
- [ ] `KtorEntityLedgerRepository` with Ktor `MockEngine`: parses a bulk `/customers` body → correct `Map<externalId, EntityBalance>`; 401 → invalidate + retry once → success.

## 🎯 Acceptance Criteria
- [ ] `./gradlew :sharedLogic:build` and the common tests pass on all targets (android/jvm/ios).
- [ ] `sharedLogic` has **zero platform imports** in common code; **no `expect`/`actual`**; no Firebase/Firestore SDK anywhere in T1; `repository/` contains **interfaces only** (the Ktor impl is in `data/`).
- [ ] Money is **decimal `String`** everywhere; no `Double`/`Float` touches money; `BalanceDirection` is derived by sign without float parsing.
- [ ] `EntityRole`, `HlSyncStatus`, `BalanceDirection` serialize as **UPPERCASE == enum name**.
- [ ] Use cases enforce the `profiles` scope from `UserSession` and the stated validations; Walk-in cannot be archived.
- [ ] `KtorEntityLedgerRepository` reads HL via Ktor, injects the bearer from `HlTokenProvider`, joins by `externalId`, and does 401→invalidate→retry-once. Verified with `MockEngine` (no live HL needed).
- [ ] The blessed shared-Ktor deviation from `CLAUDE.md` is called out in code comments + this ticket.

## 🚫 Out of scope
- Any **platform** code: Firestore `EntityRepository` impls, `HlTokenProvider` impls, ViewModels, DI wiring, UI — those are **T3 (Android+iOS)** and **T4 (Desktop)**.
- The **Cloud Functions**, gateway `/internal/hl-token`, Firestore rules, provisioning (Opening Balance Equity / Inventory / COGS / Walk-in creation) — **T2**.
- Any **HL write** (customer create, opening-balance post) — done server-side in T2; T1 only **reads** balances.
- Real UI of any kind (bare test UI comes in T3/T4; polished UI is a later effort).

## 🔗 Dependencies
- None. T1 is the foundation; **T2, T3, T4 depend on T1.**

## 📚 References
- **Brief:** #25 · `docs/briefs/B25-profiles-entities.md`
- **PRD:** `docs/PRD.md` §9.5 (Profiles/Entities), §6 (HL integration, §6.3 sync reliability), §7.2 (permissions)
- **HL API:** `https://ledger.humblesolutions.in/docs` (OpenAPI `/docs/json`) — customers module
- **Design decisions (this brief):** memory `b25-profiles-entities-design`, `hl-compatibility-audit`
- **Skill:** `/kmp-arch` (layering authority)

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
