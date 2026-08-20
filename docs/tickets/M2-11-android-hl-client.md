# [M2] Android — HL client: brokered token + read account balances onto Home

> **Platform: Android first** (iOS & Desktop later, once stable).
> ⚠️ **Follow the `/kmp-arch` skill strictly** — it's the authority on this app's layering, naming, and
> method conventions. Run it before planning; do not deviate.
> Milestone: **M2 — HL integration + tax extension**. This is the first M2 ticket (the HL client backbone).

## 📖 Story / Why
M1 got users *in* (login → Firebase session). M2 connects the app to the **money**. The first step is the
**app-side HL client**: the piece that securely reaches Humble Ledger (HL) using the **short-lived token
the gateway brokers** — HL credentials never touch the device. We prove the whole `app → gateway → HL`
chain works by reading the signed-in company's **chart of accounts + balances** and showing them on Home.
Every later money feature (purchases, sales, balances dashboard, reports) sits on this client.

## 🧭 Context
**The HL access flow:**
1. The app already has a **Firebase ID token** (from the M1-09 login, via the company's named `FirebaseApp`).
2. App → **gateway `POST /hl-token`** with header `Authorization: Bearer <Firebase ID token>` → returns
   `{ "hlToken": "<JWT>", "expiresIn": 900 }` (verified live).
3. App calls **HL directly** with `Authorization: Bearer <hlToken>` — e.g. `GET /api/v1/accounts` to list
   the chart of accounts + balances.

**Live endpoints / config (public, not secrets):**
- Gateway: `http://68.183.86.89/gateway` → `POST /hl-token`
- HL API base: `http://68.183.86.89/api-server` → `GET /api/v1/accounts` (+ the read recipes in
  `MOBILE_ADMIN_API.md`). HTTP-only for now — same dev-only cleartext caveat as the gateway (extend the
  existing `network_security_config.xml` to allow the HL host too).

**Rules that matter here:**
- **Money is never floating point** — treat HL money as **decimal strings** in shared/UI code.
- **HL credentials never live on the device** — the app only ever holds the short-lived `hlToken`.
- **Cache the token** for its ~15-min life; re-broker (fetch a fresh Firebase ID token → `/hl-token`)
  only on expiry or a `401`. Don't call `/hl-token` on every request.

## 🏛️ Architecture — follow `/kmp-arch` exactly
All repo methods `suspend`; no `expect`/`actual`; manual DI; nothing in `sharedUI`; no platform/HL/Firebase
imports in `sharedLogic`.
- **`sharedLogic/model`** — `LedgerAccount` (id, name, type, `balance: String`), and any read DTO →
  domain mapping types. Money fields are `String`.
- **`sharedLogic/repository` (interfaces)** — `LedgerRepository.getAccounts(): List<LedgerAccount>`;
  a token-access seam (e.g. `HlTokenProvider.currentToken(): String` / `IdTokenProvider`) so the use case
  depends only on interfaces.
- **`sharedLogic/usecase`** — `GetAccountBalancesUseCase` (fetches accounts, optionally groups Cash / Bank
  / Credit Card for display). Depends only on interfaces.
- **`androidApp` repo impls** —
  - `HlTokenRepository`/provider: gets a fresh **Firebase ID token** from the company's named
    `FirebaseApp`, calls gateway `/hl-token`, **caches** the `hlToken` for `expiresIn`, refreshes on
    expiry/401.
  - `HlLedgerRepository`: OkHttp calls to HL (`GET /api/v1/accounts`) with the cached token; parses with
    kotlinx-serialization; maps money to `String`.
- **`androidApp` ViewModel + UI** — Home loads balances on init (`StateFlow`), shows the account
  balances (a simple list/cards) in place of / alongside the current placeholder.

## 🔑 Access & prerequisites
> PM provides via secure channel. Don't commit anything.
- A **working login whose company has an HL company** — e.g. GTR (`mohit@humblesolutions.in`) or the
  Aromex test user. The gateway already brokers HL tokens for both (verified). *(A freshly-registered HL
  company has zero balances — the chart of accounts still lists, which is enough to verify the read.)*
- HL API base URL: `http://68.183.86.89/api-server` — public, in this ticket.
- **No HL credentials / service-account keys on device** — only the brokered `hlToken`.

## ✅ Scope / What to build
- [ ] Shared `model` (`LedgerAccount`, money as `String`) + `LedgerRepository` interface + a token/ID-token
      seam + `GetAccountBalancesUseCase`.
- [ ] Android: fresh Firebase **ID-token** access (extend the auth repo), `HlTokenRepository`
      (`/hl-token` + cache + refresh), `HlLedgerRepository` (`GET /api/v1/accounts`).
- [ ] Home shows the company's **account balances** (Cash / Bank / Credit Card + others), money rendered
      from strings.
- [ ] Loading + error states (gateway/HL unreachable, 401/403, expired token → re-broker once).
- [ ] Extend `network_security_config.xml` to permit cleartext to the **HL host** too (dev-only, with the
      same removal `TODO`).

## 🎯 Acceptance Criteria
- [ ] Strictly follows `/kmp-arch`: shared `model`/`repository`/`usecase` have **no** platform/HL/Firebase
      imports; Android implements the interfaces; ViewModel uses `StateFlow` + manual DI; nothing in
      `sharedUI`; no `expect`/`actual`.
- [ ] On Home, the app brokers an HL token via the gateway and **lists the signed-in company's accounts +
      balances** — verified live against a real login.
- [ ] The `hlToken` is **cached** (only one `/hl-token` call across multiple reads) and **re-brokered** on
      expiry / `401`.
- [ ] **No HL credentials or service-account keys on the device** — only the short-lived token; no secrets
      committed.
- [ ] **Money is handled as decimal strings** end to end (no `Double`/`Float` for money).
- [ ] Clear states for loading and for gateway/HL/network errors.
- [ ] Cleartext is limited to the gateway + HL hosts in the dev-only network-security config, with a
      removal `TODO`.

## 🚫 Out of scope
- **Writes/posts to HL** (purchases, sales, payments) and the **idempotency layer** — later M2/M5/M6 tickets.
- **GST/PST tax extension** and **chart-of-accounts provisioning** — separate M2 tickets (provisioning is
  done at onboarding).
- **Full Home/dashboard polish** — M7. This ticket shows balances minimally to prove the read.
- **iOS & Desktop** — after Android is stable.

## 🔗 Dependencies
- Builds on **M1-09** (login provides the Firebase session + ID token) and the **live gateway `/hl-token`**
  (verified end-to-end for Aromex and GTR).

## 📚 References
- `docs/PRD.md` §6 (HL integration / accounting model), §12 (M2).
- Humble Ledger **`MOBILE_ADMIN_API.md`** — auth (`§2`), find Cash/Bank account ids (`§4`), balance read
  recipes (`§5`, `§9`), errors/pagination (`§10`). (Ask the PM for access.)
- `CLAUDE.md` — "Two backends", money-as-strings, idempotency, and **`/kmp-arch`** (the layering authority).
- `handoffs/ticket-3.md` — the `/hl-token` contract + HL `/auth` behavior.

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
