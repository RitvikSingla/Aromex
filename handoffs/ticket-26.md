# Handoff — Ticket #26

**Ticket:** #26 — [M3] Profiles/Entities — T1: shared logic (models + repo interfaces + use cases + shared Ktor HL read client)
**Brief:** #25 · **Branch:** `ticket-26-shared-entities`

## Summary
Built the pure-Kotlin foundation of the Profiles/Entities feature in `sharedLogic`: the party data models, the two repository **interfaces** (`EntityRepository` for Firestore, `EntityLedgerRepository` for HL reads), the permission-gated **use cases**, and a decimal-string `Money` util. HL balance access is implemented **once, shared**, as a Ktor client (`KtorEntityLedgerRepository`) — a deliberate, documented deviation from CLAUDE.md's "per-platform impls" rule, justified because HL REST is identical on every platform. This is the first time `sharedLogic` gains dependencies (coroutines, serialization, Ktor); the Ktor engine is wired per-target inside `sharedLogic` (no `expect`/`actual`, so Swift never constructs an engine). No UI, no Firestore SDK, and no HL writes are in scope — those are tickets #27/#28/#29. Verified with 25 unit tests plus a live integration test against the real HL `aromex-test` company.

## Files changed

### Config / build
- `gradle/libs.versions.toml` — add Ktor 3.0.3 + client libs (core, content-negotiation, kotlinx-json, logging, okhttp/darwin engines, mock) and coroutines-core/test aliases. *Why: first shared networking stack.*
- `sharedLogic/build.gradle.kts` — apply the kotlin-serialization plugin; add commonMain deps + per-target Ktor engines (okhttp for android/jvm, darwin for ios) + commonTest deps. *Why: make the shared Ktor client build & link on every target.*

### Shared logic — models (`sharedLogic/.../model/`)
- `Entity.kt` — the party read model (no balance field; balance is read live from HL). *Why: core domain type.*
- `EntityInput.kt` — create/edit input (separate from read model). *Why: fields diverge from `Entity`.*
- `EntityRole.kt` — CUSTOMER/SUPPLIER/MIDDLEMAN, wire == UPPERCASE name, non-binding labels.
- `HlSyncStatus.kt` — PENDING/SYNCED/FAILED.
- `BalanceDirection.kt` — RECEIVABLE/CREDIT/SETTLED + `fromBalance(signedString)`.
- `EntityBalance.kt` — absolute net string + direction.
- `OpeningBalance.kt` — positive amount string + RECEIVABLE|CREDIT direction.
- `PermissionDeniedException.kt` — thrown by use cases when a scope is missing (reused by later features). *Why: shared-logic permission enforcement (PRD §7.2).*

### Shared logic — repository interfaces (`.../repository/`)
- `EntityRepository.kt` — Firestore ops: `observeEntities(): Flow`, `createEntity` (PENDING), `updateEntity`, `archiveEntity`. Impls are per-platform (T3/T4). *Why: the operational store contract.*
- `EntityLedgerRepository.kt` — HL reads: `getBalances()` (bulk, keyed by externalId), `getBalance()`. *Why: balance-read contract, backed by the shared Ktor impl.*

### Shared logic — data (`.../data/`)
- `KtorEntityLedgerRepository.kt` — the single shared HL read client: bearer from `HlTokenProvider`, `GET /customers` (bulk + single, paginated via `meta.hasMore`), reads `balance`|`outstanding`, derives direction by sign, 401→invalidate→retry-once, throws `HlException`. *Why: HL access shared across all platforms (documented CLAUDE.md deviation).*

### Shared logic — use cases (`.../usecase/`)
- `SaveEntityUseCase.kt` — MANAGE gate + validation (name, phone, email, opening amount/direction); create vs update. *Why: the write entry point (client writes PENDING; CF does HL).*
- `ObserveEntitiesUseCase.kt` — VIEW/MANAGE gate; live list, active-only by default.
- `GetEntityBalancesUseCase.kt` — VIEW/MANAGE gate; bulk + single balance reads.
- `ArchiveEntityUseCase.kt` — MANAGE gate; blocks archiving the Walk-in entity.

### Shared logic — util (`.../util/`)
- `Money.kt` — `signOf`, `isZero`, `isValidPositiveDecimal`, `abs` — string-based, no float. *Why: money-as-strings rule.*

### Tests
- `commonTest/.../entities/` — `TestFakes.kt` (fakes + session builder), `MoneyTest`, `BalanceDirectionTest`, `EntityRoleTest`, `SaveEntityUseCaseTest`, `ArchiveEntityUseCaseTest`, `KtorEntityLedgerRepositoryTest` (Ktor MockEngine). 25 tests.
- `jvmTest/.../entities/LiveHlLedgerIntegrationTest.kt` — self-skipping live check of the real client against real HL (runs only when `HL_*` env vars are set).

### Docs
- `docs/tickets/M3-25-T{1..4}-*.md` — the M3 ticket drafts (T1 = this ticket; T2/T3/T4 are the rest of the milestone plan, mirroring GitHub issues #26–#29).

## How to test
```bash
git checkout ticket-26-shared-entities

# Fast: 25 unit tests (logic, validation, permissions, Ktor parsing + 401 retry via MockEngine)
./gradlew :sharedLogic:jvmTest

# Full: compile + link + test on android / jvm / ios (proves the shared Ktor client links per-target)
./gradlew :sharedLogic:build

# Optional live check against real HL (self-skips without creds):
HL_BASE_URL=https://ledger.humblesolutions.in \
HL_EMAIL=aromex-test@yourco.com HL_PASSWORD=<ask manager> \
./gradlew :sharedLogic:jvmTest --tests '*LiveHlLedgerIntegrationTest*' --rerun-tasks
```
All 25 unit tests + the live test passed locally; `:sharedLogic:build` is green (android, iosArm64, iosSimulatorArm64).

## Acceptance criteria
- ✅ `:sharedLogic:build` + common tests pass on android/jvm/ios.
- ✅ Zero platform imports in common; no `expect`/`actual`; no Firebase SDK; `repository/` is interfaces-only (Ktor impl lives in `data/`).
- ✅ Money is decimal `String` throughout; `BalanceDirection` derived by sign without float parsing.
- ✅ `EntityRole`/`HlSyncStatus`/`BalanceDirection` serialize as UPPERCASE == enum name.
- ✅ Use cases enforce the `profiles` scope + the stated validations; Walk-in cannot be archived.
- ✅ `KtorEntityLedgerRepository` reads HL via Ktor, injects the bearer from `HlTokenProvider`, joins by `externalId`, and does 401→invalidate→retry-once (verified with MockEngine + live).
- ✅ The blessed shared-Ktor deviation from CLAUDE.md is called out in code comments + the ticket.

## Deviations / decisions
- **Ktor engine placement:** the ticket originally floated injecting the engine from platforms; instead the engine is added per-target *inside* `sharedLogic` and the client builds its own `HttpClient` (Ktor auto-selects the single engine on each target's classpath). Keeps the client self-contained and spares Swift from constructing engines. (T1 ticket doc updated to match.)
- **Extra live integration test** (`LiveHlLedgerIntegrationTest`) beyond the ticket scope — exercises the real shipped client against real HL. Self-skips without `HL_*` env vars, so it's CI-safe and needs no committed secrets.
- **Seed data:** two customers (`t1-smoke-recv` +500, `t1-smoke-cred` −300) were created on the throwaway `aromex-test` HL company to make the live test meaningful; left in place so the test stays runnable. Can be archived on request.

## Open questions / follow-ups
- **`balance` vs `outstanding`:** live HL currently returns `outstanding` on customer create but `balance` on the opening-balance/write responses; the client reads whichever is present. HL is standardizing on `balance` — worth confirming so the field can be narrowed later.
- **CLAUDE.md update:** recommend recording "network/HL access is a shared Ktor client" so the deviation is blessed in the architecture doc (flagged for `/review-ticket`).
- **Consumers:** `EntityRepository`/`HlTokenProvider` impls, ViewModels, and UI arrive in #28 (mobile) and #29 (desktop); the Cloud Functions that do the HL write arrive in #27.
