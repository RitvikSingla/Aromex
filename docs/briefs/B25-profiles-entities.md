# [Brief] Profiles / Entities — unified party model (M3)

> **Milestone:** M3 — Profiles / Entities. **The walking skeleton for the money path** — the first HL write + the first HL↔Firebase dual-write + idempotency, on the simplest domain. Every later money feature (Purchase, Sales, Transactions) reuses this spine.

## 🎯 What we're building & why
The ability to **create and manage the people/businesses Aromex buys from and sells to** — one **unified `Entity` (party)** with **multi-select roles** (customer / supplier / middleman) that are **non-binding labels**. Every entity is backed by **exactly one Humble Ledger customer account**, so buying and selling to the same party **net into a single balance** automatically (HL: `RECEIVABLE` = they owe us / `CREDIT` = we owe them). Roles never constrain anything — you can always buy from a "customer" or sell to a "supplier".

Why it matters: (1) Purchase and Sales are both **blocked** on entities existing. (2) It's the **safest place to prove the HL write + dual-write + idempotency pattern** before line-items/tax/inventory pile on. (3) It sets the party model the whole product stands on.

## 👤 Who it's for
The shop owner / staff (per permissions) managing their book of customers, suppliers, and middlemen — in phone distribution the *same trader is often both* buyer and seller, so a single netting profile per party is what they actually want (mirrors Tally/Vyapar).

## ✅ What it must do (capabilities)
- **Add an entity:** name, phone(s), email, address, **multi-select roles** (customer/supplier/middleman), notes, and an **optional opening balance** (they already owe you / you owe them).
- **Browse entities:** searchable/filterable list (by name/phone/role), each showing its **current net balance**, color-coded per the app convention (green = they owe you / red = you owe them / gray = settled).
- **Open an entity:** profile details + current net HL balance (RECEIVABLE / CREDIT / SETTLED). *(Full per-entity transaction history comes with later milestones.)*
- **Edit an entity:** including changing roles at any time — roles never block buy/sell.
- **Walk-in:** a built-in reserved **"Walk-in Customer"** entity exists for anonymous paid-in-full sales (can't be deleted/renamed).
- Every entity is **created in HL on save** (one HL customer, idempotent), and stays in sync with its Firebase operational profile.

## 🌟 What "good" looks like
- Add a customer today, **buy from them next month** — same profile, one balance that nets, **no second record**.
- The **HL write path is proven**: save entity → HL customer exists → its balance reads back → a retry never creates a duplicate.
- Entity list + balances update **live** (app convention).
- Nothing in the model is phone-specific — it reads as a generic party you could reuse in a general POS.

## 🚫 Non-negotiables
- **Unified party + roles-as-labels.** One `Entity` with multi-select roles; **roles NEVER constrain** what you can do (buy from a customer, sell to a supplier — both always allowed).
- **One HL customer per entity; HL vendors are NOT used.** Net balance model (RECEIVABLE/CREDIT). Formal AR/AP separation is a *documented, deliberate* deferral.
- **Idempotent HL create + reliable HL↔Firebase dual-write** keyed on a stable id (the Aromex entity id). A retry must never create a duplicate HL customer, and a partial failure must not leave Firebase and HL silently divergent. **This is the reusable spine — get it right here.**
- **Money is decimal strings, never float** (per `CLAUDE.md`).
- **Generic naming** (`Entity`/`Party`, not "Phone") per the `CLAUDE.md` North star — retrofittable to a general POS.
- **Permission-gated**; no secrets committed.

## 🧭 Technical steers (from the PO)
- `[hard]` One HL customer per entity via HL's **idempotent create** (`externalId` = Aromex entity id); vendors module untouched.
- `[hard]` **Eager** creation — HL customer created on entity **save**, not lazily on first transaction.
- `[hard]` Define the **dual-write failure/repair behaviour** (HL create fails → the save must not silently succeed Firebase-only). This is the sync-reliability concern in PRD §6.3; solve it here as the pattern.
- `[preference]` Balance is **read from HL** (RECEIVABLE/CREDIT/SETTLED), not stored/duplicated in Firebase.
- `[preference]` Optional opening balance posts as an **HL opening entry** — confirm the exact HL endpoint (and the endpoint for "post against a customer" both directions) with the HL docs/team.
- `[preference]` Follow `/kmp-arch`: shared `Entity` model + repo interface + use case; per-platform impls + ViewModels.

## 🧊 Happy to defer
- Credit limits, price tiers / customer groups, tax IDs (GSTIN / business #) — until Sales & tax need them.
- Loyalty / store credit; duplicate-detection & merge (note: phone is the natural key).
- **Formal AR/AP separation** (role-driven vendor mapping) — deferred; the net-balance decision is documented.
- **Middleman commission** posting mechanics — designed with Sales.
- **Enforcement** of "credit sales must be named" + **used-goods / large-cash ID capture** — these live in the Purchase/Sales flows, flagged below.
- Bulk import of entities.

## 📎 References
- **PRD** §9.5 (Profiles/Entities), §6 (HL integration — esp. §6.3 sync reliability), §7.2 (permissions); **FEATURES.md** §5 (Profiles) for the legacy behaviour.
- **HL model:** customers module — balance types `RECEIVABLE | CREDIT | SETTLED`, idempotent `externalId` create; API docs at `http://68.183.86.89/api-server/docs`.
- **`CLAUDE.md`** — "North star" (generic party), money-as-strings, HL dual-write idempotency.
- **Compliance to verify (PO, before launch):** second-hand/used-goods dealer **ID-capture** laws + large-cash reporting (US Form 8300 / Canada FINTRAC) in the target US states / Canadian provinces — affects the *Purchase/Sales* flows, not this model. *(Not legal advice.)*
