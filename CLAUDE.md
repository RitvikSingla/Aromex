# CLAUDE.md — Aromex (KMP rebuild)

> Read this before working on any ticket. It captures the architecture, the system
> boundaries, and how we work — the things that aren't obvious from the code.
> Full spec: `docs/PRD.md`. Existing app's behavior (feature-parity reference): `docs/FEATURES.md`.

## What Aromex is
Accounting + operations software for **mobile-phone distributors/retailers**. This repo is the
ground-up **Kotlin Multiplatform** rebuild (Android, iOS, Desktop/Windows), productized to be
**sold to multiple distributor companies**. Money/bookkeeping lives in an external accounting
backend (**Humble Ledger**); this app owns **operations** (inventory, sales/purchase workflows,
scanning) and the UI.

## North star — phone-first now, general POS later (build retrofittable)
Aromex ships **phone-first**, but the goal is to generalize it into a **general POS** where phones are one
**mode** (alongside quantity/general retail, other serialized goods, services). Build every feature so that
retrofit is cheap — **generalize the data model and naming now; implement only the phone mode today:**
- **Model inventory generically:** a `Product` / `InventoryItem` with a **`trackingMode`**
  (`SERIALIZED | QUANTITY | VARIANT | SERVICE`). Implement `SERIALIZED` (IMEI) now; a phone is
  `SERIALIZED` + a phone attribute profile (brand/model/capacity/carrier) — **not** a hardcoded top-level
  "Phone" entity.
- **Name core types/collections generically** (`products`, `inventory`, `sales`, `serials`) — "phone" is a
  mode/attribute, never the domain noun. (Per-client Firebase + online-only + no migration → naming is cheap
  now, costly to rename against live data.)
- **Sale lines reference a generic product + unit** (a serial OR a quantity), even though the v1 picker is
  IMEI-only.
- **Don't over-generalize:** retrofittable ≠ build the general POS now. Generalize schema + vocabulary; keep
  flows/UI phone-specific where that's the right UX today. No speculative abstractions.

## Architecture — Native UI + Shared Kotlin logic (STRICT)
Three layers. **No shared UI. No `expect`/`actual`. No DI framework. Manual DI.**
- `sharedLogic/` (Kotlin, zero platform imports): `model/` data classes · `repository/`
  **interfaces only** (all `suspend`) · `usecase/` business logic that depends **only** on
  interfaces · `util/`, `config/`.
- `androidApp/` — Jetpack Compose UI + ViewModels (`StateFlow`) + repository impls (Android SDKs).
- `iosApp/` — SwiftUI UI + ViewModels (`@MainActor ObservableObject`) + repository impls (iOS SDKs).
- `desktopApp/` — Compose-Desktop UI + ViewModels + repository impls (JVM: REST + Firestore Admin SDK).
- Each platform writes its **own UI** and its **own repository implementations** of the shared
  interfaces. Only `sharedLogic` is shared. Android and Desktop both use Compose but **do not share
  UI code** — separate per platform.
- **Exception — HL (Humble Ledger) REST access is a *shared* Ktor client**, not a per-platform impl.
  HL's REST API is identical on every platform, so its read clients live in `sharedLogic/data/` (e.g.
  `KtorEntityLedgerRepository`) implementing the shared repo interface. This stays free of platform
  imports (the Ktor engine is added per target inside `sharedLogic`, auto-selected — no `expect`/`actual`).
  Firestore access remains per-platform (native SDK on mobile, Admin SDK on Desktop). A shared HL
  client that holds an `HttpClient` must expose `close()`, and per-ViewModel consumers must call it on
  clear (or share one app-scoped client) to avoid leaking connections.
- ⚠️ The scaffold currently includes a `sharedUI` (Compose Multiplatform) module. We chose
  **native UI**, so `sharedUI` is being removed (M0 cleanup). **Do not add UI to `sharedUI`.**

**Adding a feature, in order:** shared model → shared repo interface → shared use case →
platform repo impls → platform ViewModels (manual DI) → platform UI → navigation. Never skip the
use case layer; never put platform imports or backend code in `sharedLogic`.

**Caching:** fetch a dataset once on ViewModel init, hold it in memory, and filter/search/paginate
client-side (no re-fetch). Use live reads only for real-time surfaces (balances, scanner channel).

## Two backends — know what goes where
- **Money → Humble Ledger (HL):** external, shared, **multi-tenant double-entry** accounting backend
  (Postgres + REST). Each client company = one HL `company`. HL owns the ledger, balances
  (Cash / Bank / Credit Card / AR / AP), customers, suppliers, sales / purchases / payments /
  expenses / refunds, invoices, tax, and financial reports. The app reaches HL **only through our
  gateway** (HL credentials never live on a device). Posts are **idempotent** by
  `(appId = "aromex", sourceId = <our operational record id>)`.
- **Operations → per-client Firebase:** each client has their **own Firebase project**
  (Auth + Firestore + Cloud Functions) holding inventory/IMEI, products, scanning, **users +
  permissions**, company settings, and the operational record of each sale/purchase (line items +
  IMEIs, which HL does not store). On **Desktop**, Firestore is reached via the Firebase Admin SDK
  with a service-account key fetched at runtime — so **permissions are enforced in shared app logic**,
  not Firestore rules.
- **Gateway / Central Directory (ours):** a small vendor-owned service that maps a login **email →
  the right company's Firebase config** and brokers short-lived HL access after verifying the user.

## Key product rules
- **One currency per company**, fixed at setup. No multi-currency, no FX (those old features are cut).
- **Tax is configurable per company:** GST + PST (two lines), HST/GST-only (one line), or none.
- **Online-only.** No offline / sync.
- **Multi-tenant by isolation:** separate Firebase project per client; HL multi-tenant by company.
- **Money is never floating point** in shared/UI code — treat HL money as decimal **strings**.
- **Ledger is immutable** (HL): edits/deletes are reverse-and-repost underneath; the UI shows
  net/current values, with change history opt-in.
- **Auth:** email-based workspace discovery (no company code); permissions are capability scopes
  (`manage` / `view` / `none`) enforced in shared app logic.

## Infrastructure / deploy
- **Humble Ledger** runs on a vendor-managed server (Node/Fastify + Postgres); its base URL,
  credentials, and per-company config are delivered through the gateway / secure config —
  **never hardcode them here.**
- **Gateway** is a small service we host; tickets that build/deploy server components state where
  they deploy.
- **Per-client Firebase** projects are provisioned manually per the runbook (PRD §8.1).
- Secrets (service-account keys, HL credentials, tokens) live in secure storage / the gateway and
  are **never committed**.

## How we work (ticket workflow)
- **Always `git checkout main && git pull` before starting a ticket**, so every `ticket-N` branch builds on the latest merged work. (`/start-ticket` does this for you.)
- Each task is a **GitHub issue** using our template. Start it with **`/start-ticket <#>`** — it
  reads the ticket + this file + the referenced PRD sections, then **plans and confirms before
  writing code.**
- When done, run **`/handoff <#>`** to write `handoffs/ticket-<#>.md` from the **real diff**, then
  open a PR (the PR template links the handoff).
- The manager reviews with **`/manager-review <PR#>`**.
- If a ticket conflicts with this file or the PRD, **stop and ask** — don't guess.

## References
- `docs/PRD.md` — full product + architecture spec (source of truth).
- `docs/FEATURES.md` — behavior of the existing app (feature-parity reference).
- Humble Ledger API — HL `MOBILE_ADMIN_API.md` / Swagger (ask the team for access).
