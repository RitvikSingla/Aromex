---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M4] Inventory — T2: Firebase spine (security rules + composite indexes + race-safe transaction contract)"
labels: []
assignees: []
---

**Brief:** #41

> **Milestone:** M4 — Inventory + Scanner.
> **Ticket 2 of 4** (T1 shared logic → **T2 Firebase spine** → T3 mobile → T4 desktop).
> 🪶 **Much lighter than M3's T2 — there is NO Humble Ledger, NO gateway, NO Cloud Function here.** M4 is
> **Firebase-only**. The race-safe write is a **client-side Firestore transaction** (T3/T4); this ticket
> supplies the **rules + indexes + the exact transaction contract** all three platforms implement identically.

## 📖 Story / Why
Adding stock in a **multi-cashier** shop must be **race-safe** (PO decision #1 on #41): two cashiers adding
the same phone at the same instant must never create a duplicate SKU or two in-stock units with the same IMEI.
Because M4 touches no secret backend, the atomic write runs **on the client** as a Firestore transaction — so
this ticket doesn't build a server. It builds the **guardrails and the contract**: the Firestore **security
rules** that gate `inventory` on mobile, the **composite indexes** the queries need, and a precise,
platform-agnostic **transaction spec** so Android, iOS, and Desktop all implement the same atomic steps.

## 🧭 Context
**Why no Cloud Function (the key difference from M3 T2):** M3 needed a server because HL credentials can't sit
on a device. M4 writes **only Firestore**, and Firestore transactions are atomic whether they run on a client
SDK or a server — so the client transaction (T3/T4) *is* the correctness mechanism. This ticket makes it safe
+ queryable.

**Enforcement model (mirror M3):** mobile (native SDK) is subject to **security rules**; Desktop (Admin SDK)
**bypasses rules**, so on Desktop the **shared `AddStockUseCase`/permission gates (T1) are the enforcement**.
Rules here are therefore the **mobile backstop**; they must not be the *only* line of defense.

**The transaction contract (this is the deliverable other tickets build to — also in `docs/SCHEMA.md`):**
```
ADD STOCK (one transaction):
  1. read products/{skuKey}
       └─ absent → create it { productId:skuKey, trackingMode:"SERIALIZED", attributes{…}, defaultSellingPrice, isActive:true, audit }
  2. for each unit:
       read imeiIndex/{imei}
         ├─ present → ABORT whole txn → DuplicateImeiException([imei,…])   (in-stock IMEI already taken)
         └─ absent  → create serials/{autoId} { serialId, productId:skuKey, imei, cost, condition, status:"IN_STOCK", location, isActive:true, saleId:null, audit }
                    + create imeiIndex/{imei} { imei, serialId, productId:skuKey }
  → all-or-nothing

RELEASE IMEI (same-transaction, whenever a unit leaves stock):
  setStatus→SOLD  |  archiveSerial (isActive=false)  →  delete imeiIndex/{imei}
CORRECT IMEI (updateSerial with new imei): delete imeiIndex/{oldImei} + create imeiIndex/{newImei} + patch serial, in one txn
RE-ADD a returned phone: its old SOLD serial has no imeiIndex entry → the IMEI is free → a fresh serial+index is created
```
`imeiIndex` scope is **in-stock only** (PO #2): SOLD/archived serials keep their `imei` field for history but
hold **no** index entry.

**Where the artifacts live:** extend the per-client Firebase project structure + rules established in **#7**
(`[M1] Per-client Firebase project: structure, security rules & first-admin setup`) and the `entities` rules
from **#27** (M3 T2). Keep `firestore.rules` + `firestore.indexes.json` in the same repo location those tickets
used (confirm the path from #7's handoff, `handoffs/ticket-7.md`).

## 🔑 Access & prerequisites
> Via the manager / secure channel. Never commit secrets.
- Access to the **aromex-test** client Firebase project (console + `firebase deploy --only firestore:rules,firestore:indexes`), or the manager deploys from your PR. Reuse the same access #7/#27 used.
- The Firebase CLI logged into the test project. **No** HL / gateway access is needed for this ticket.

## ✅ Scope / What to build
**Firestore security rules (`firestore.rules`, mobile backstop) — extend the existing file:**
- [ ] Helpers: read the caller's `users/{uid}.permissions.inventory` (reuse the pattern the `entities` rules use for `profiles`), plus the existing `isSignedIn()` / admin helpers.
- [ ] `products/{productId}`: **read** if `inventory in {view, manage}`; **create/update** if `inventory == manage`. Validate on write: `trackingMode` is a known value; `productId` shape is the skuKey; `defaultSellingPrice` is a string; no hard-delete (archive via `isActive`).
- [ ] `serials/{serialId}`: **read** if view/manage; **create/update** if manage. Validate: `productId` non-empty; `cost` is a string; `status` ∈ {IN_STOCK,RESERVED,SOLD}; `condition` ∈ {NEW,USED}; `imei` non-empty; no hard-delete.
- [ ] `attributes/{attributeId}`: **read** if view/manage; **create/update** if manage; `type` ∈ the 6 known types.
- [ ] `imeiIndex/{imei}`: **read** if view/manage; **create/delete** if manage (delete is allowed here — the index entry is released on sold/archive, unlike the history-bearing docs). Validate the doc id equals the `imei` field.
- [ ] Confirm rules **allow the multi-document transaction** the client runs (create product + serial + index atomically) for a `manage` user, and **deny** a `view`/`none` user.

**Composite indexes (`firestore.indexes.json`):**
- [ ] `serials`: `(productId ASC, status ASC, isActive ASC)` — drill-in + in-stock count per SKU.
- [ ] `serials`: `(status ASC, isActive ASC)` — the live in-stock-serials stream.
- [ ] `serials`: `(location.attributeId ASC, status ASC, isActive ASC)` — Sales-by-location later (add now to avoid a churn).
- [ ] `products`: `(isActive ASC)` — active-SKU list.
- [ ] `attributes`: `(type ASC, isActive ASC)` and `(type ASC, parentId ASC)` — vocab lists + model-by-brand.
- [ ] (Single-field indexes like `serials.imei` are auto-created by Firestore; note that `imeiIndex` needs none — it's keyed by doc id.)

**Contract doc + seed decision:**
- [ ] Add/point to the **transaction contract** above in `docs/SCHEMA.md` (already present) and reference it from the rules file header comment, so T3/T4 implement identical steps.
- [ ] **No seed data.** Attribute vocabularies start **empty** and grow via add-new-inline (T1's `AddAttributeUseCase`). Do **not** pre-seed brands/carriers. (Documented decision — call it out.)

**Verify (no app needed):**
- [ ] Use the **Firestore Rules Playground / emulator** to assert: a `manage` user can run the add-stock writes; a `view` user is denied create; a duplicate `imeiIndex/{imei}` create is rejected by the client transaction (demonstrate the abort path against the emulator).

## 🎯 Acceptance Criteria
- [ ] `firestore.rules` + `firestore.indexes.json` deploy cleanly to the aromex-test project (`firebase deploy --only firestore:rules,firestore:indexes` succeeds).
- [ ] Rules gate **all four** inventory collections on `users/{uid}.permissions.inventory` (view→read, manage→write) and **permit the atomic add-stock transaction** for a manage user while **denying** view/none — verified in the emulator/playground.
- [ ] `imeiIndex` rules allow **create + delete** (release) for manage, and enforce `doc id == imei`.
- [ ] The composite indexes above exist so `observeInStockSerials`, per-SKU drill-in, and the vocab/model-by-brand queries run without a "needs index" error.
- [ ] **No Cloud Function, no gateway change, no HL** is introduced. The transaction contract is documented and referenced from the rules header.
- [ ] Vocabularies are **not** seeded; the empty-start decision is recorded in the ticket/handoff.

## 🚫 Out of scope
- The **transaction implementation** (client-side Firestore transaction) — that's T3 (mobile) / T4 (desktop); this ticket only guarantees rules+indexes permit it and specifies the steps.
- Any **Cloud Function / server / gateway** work — deliberately none in M4.
- ViewModels, UI, scanner.
- HL, valuation, tax.

## 🔗 Dependencies
- **T1 (#43)** (the model + `skuKey` + interface contract this enforces).
- Builds on **#7** (Firebase project + rules structure) and **#27** (existing `entities` rules to extend).

## 📚 References
- **Brief:** #41 · **Schema + transaction contract:** `docs/SCHEMA.md` Part 2
- **PRD:** `docs/PRD.md` §9.6, §7.2 (permissions), §7.3 (Firebase schema)
- **Prior art:** `docs/tickets/M3-25-T2-backend-spine.md` (structure to mirror — minus HL/CF), `handoffs/ticket-7.md`, `handoffs/ticket-27.md`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
