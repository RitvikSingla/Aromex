# Aromex — Firestore Data Schema

> The operational data model held in each client's **per-client Firebase project** (Firestore).
> Money lives in Humble Ledger and is **not** duplicated here. This doc covers **two things**:
>
> 1. **What already ships** in the app (Auth / Users / Company / Entities — M1–M3).
> 2. **The Inventory model** designed for **M4** (Brief #41) — generic `Product` + `serials` + managed
>    `attributes`, built against the whole **stock → purchase → sale** loop.
>
> Sources of truth: `docs/PRD.md` §7.3 / §9.6, `CLAUDE.md` (North star + money-as-strings), the shipped
> `sharedLogic/model/*` classes, and the M4 schema discussion.

---

## Conventions (apply to every collection)

- **One Firebase project per client** — collections below live inside each client's own project; there is no
  cross-tenant collection.
- **Money is a decimal `String`, never a number** (`"540.00"`, `"699.00"`). Per `CLAUDE.md`.
- **Enums are stored as their UPPERCASE name verbatim** (`"SERIALIZED"`, `"IN_STOCK"`, `"CUSTOMER"`), so the
  spelling is identical on every platform — no mapping table.
- **Timestamps** are Firestore `Timestamp` (`serverTimestamp()` on write); the shared model carries them as
  epoch-millis `Long`.
- **Soft-delete, never hard-delete** anything with history: `isActive = false` (mirrors Entities).
- **Audit fields** on mutable docs: `createdAt`, `updatedAt`, `createdBy` (the author's Firebase `uid`).

### Document-id convention  ⚠️ two styles in the codebase

| Area | Own id | Notes |
|---|---|---|
| **Existing (M1–M3)** — `users`, `entities`, … | id is the **Firestore document key**; it is **not** stored as a field inside the doc | e.g. `Entity.id` is mapped from `doc.id` at read time. |
| **Inventory (M4, new)** — `products`, `serials`, `attributes` | id is **stored inside the doc** under a **collection-named key**: `productId`, `serialId`, `attributeId` | Equals the document key, but present in the body so the model round-trips without reaching for the doc key, and so foreign keys read self-descriptively (a serial's `productId` obviously points at a `products` doc). |

All **foreign keys** use the target's id name: a serial → product via `productId`; any attribute reference via
`attributeId`; a model → its brand via `parentId` (which holds an `attributeId`).

**Auto-id vs. chosen id** — rule of thumb: *editable + FK-referenced → auto-id* (keep the natural key in a
field); *externally-owned/immutable key, singleton, or a lookup index → use that key as the id*.

| Doc | Id | Why |
|---|---|---|
| `products/{productId}` | **chosen** — the deterministic `skuKey` | Atomic find-or-create (no duplicate SKUs under concurrency). Trade: SKU-defining attributes aren't edited in place. |
| `serials/{serialId}` | **auto** | Editable unit (correctable IMEI/details); identity decoupled from content. |
| `attributes/{attributeId}` | **auto** | Renamable vocabulary — never tie the id to the mutable `name`. |
| `imeiIndex/{imei}` | **chosen** — the IMEI | A lookup/guard keyed by the value you hold → O(1) existence check. |
| `users/{firebaseUid}` | **chosen** — Auth `uid` | Externally-owned, immutable; read directly by uid. |
| `companySettings/profile` | **chosen** — `"profile"` | Singleton. |
| `entities/{entityId}` | **auto** | Editable party; id pinned as HL `externalId`, must stay stable. |

---

## The business date  *(M11, ticket #107)*

`sales` and `purchases` carry **`createdAt` = the day the thing happened**, not the day someone
typed it in; the keystroke time lives in **`enteredAt`**. A shop bringing its old books into the app
picks the real date at checkout or at Add Inventory, and the record lands there everywhere at once:

| Where | How it follows |
|---|---|
| The lists | Sales History and Stock History already order, filter and page on `createdAt` |
| Humble Ledger | `syncSale` / `syncPurchase` / `syncCommission` send it as HL's `date`, formatted in the shop's timezone |
| The invoice PDF | the issue stamp is already built from `createdAt` |

Backdating one field rather than adding a second date field is deliberate: every consumer already
read `createdAt` as "when this happened", so there is no second ordering to keep in step, no index
to rebuild, and no existing record to migrate — for a same-day sale the two fields are one instant.

**Future dates are refused** (`BusinessDate.isValid`, re-checked in the use cases): a forward-dated
record would report revenue that hasn't happened, in a period that isn't closed. A few minutes of
slack is allowed for a clock that runs fast.

---

# Part 1 — What already ships (M1–M3)

### `users/{firebaseUid}`
The signed-in staff/admin and their capability scopes — the **single source of truth for permissions**,
enforced in shared app logic.

```
users/{firebaseUid}
  email:        "maya@shop.com"
  displayName:  "Maya"
  role:         "admin" | "member"
  permissions: {
    sales | purchases | inventory | transactions | profiles |
    balances | reports | statistics | histories | ledgers |
    settings:  "manage" | "view" | "none"
    userMgmt:  true            // admin-only boolean
  }
  isActive:     true
  createdBy, createdAt, updatedAt, lastLoginAt
```
Model: `Permissions` (`PermissionLevel = MANAGE|VIEW|NONE`), `UserSession` (`UserRole = ADMIN|MEMBER`).

### `companySettings/profile`  *(singleton doc)*
```
companySettings/profile
  companyName, legalName, logoUrl, country, currency
  tax: { gstEnabled, gstRate, pstEnabled, pstRate, isHST }
  hlCompanyId
  businessAddress, contactEmail, contactPhone
  taxNumber        // GST/HST registration no. (ticket #76); null when not tax-registered.
                   // Legally required on a Canadian tax invoice; omitted from the PDF if null.
  timezone         // IANA zone, e.g. "America/Vancouver" (ticket #80). The invoice date is
                   // formatted in this zone so an evening sale keeps its own calendar day / tax
                   // period. Required by --timezone at provisioning (validated there); the CF
                   // still falls back to UTC if a hand-edited value is missing or invalid.
```

**Editable in-app since M10 (ticket #98).** An admin changes the `tax` map and the invoice identity
fields (`companyName`, `legalName`, `taxNumber`, `businessAddress`, `contactEmail`, `contactPhone`)
from Settings. `hlCompanyId`, `currency` and `timezone` are **not** editable: the first addresses
every HL posting, and changing the second would re-label — not convert — every amount already stored.
Rules pin `hlCompanyId` and `currency` on update; the Settings screen never offers them.

Tax rates are read as **either a number or a decimal string**. Provisioning writes numbers
(`0.05`); the Settings screen writes strings (`"0.05"`), because a rate like Quebec's 9.975% has to
survive a round-trip intact and a string cannot pick up a float's rounding.

**Changing the rate never touches a past sale.** Each sale snapshots its own `taxLines`, HL holds the
posted amounts, and a void reverses the original transaction by id — so a new rate reaches new sales
and nothing else. That is what makes a plain overwrite safe here, instead of effective-dated rate
history. A running till follows the change within seconds (the Sales screen observes this doc); if a
cart already has lines when the rate moves, it is recalculated and a banner says so.

### `companySettingsChanges/{changeId}` — settings audit log  *(M10, ticket #98)*
One doc **per changed field**, written in the same transaction as the profile write. A save that
changed nothing writes nothing.

```
companySettingsChanges/{changeId}           // id = doc key, also stored as changeId
  field:         "GST rate"                 // as displayed, not the storage path
  oldValue:      "5%" | null                // null when the field did not previously apply
  newValue:      "6%"                       // "" when the field stopped applying (e.g. GST off)
  changedBy:     "<firebaseUid>"
  changedByName: "Maya" | null
  changedAt:     <server timestamp>
```

Rates are recorded as **percentages** (`"9.975%"`), not the stored fraction — nobody reads `0.09975`
under pressure and thinks *nine point nine seven five percent*.

**Append-only.** Rules deny update and delete to every client including the author, and pin
`changedBy` to the caller's own uid. Admin-only to read: it names who did what.

### `invites/{inviteId}`  *(optional)*
```
invites/{inviteId}
  email, permissions, invitedBy, status, expiresAt
```

### `entities/{entityId}` — unified party (customer / supplier / middleman)  *(M3)*
One party backed by exactly one HL customer; **roles are non-binding labels**. **No balance is stored** — the
net balance is read live from HL. `id` is the Firestore doc key (not a stored field).

```
entities/{entityId}                         // id = doc key
  name:        "Rajesh Traders"
  phones:      ["+1..."]                     // array
  email:       "..."  | null
  address:     "..."  | null
  roles:       ["CUSTOMER", "SUPPLIER"]      // subset of CUSTOMER|SUPPLIER|MIDDLEMAN
  notes:       "..."  | null
  taxNumber:   "..."  | null                 // optional GST/HST number (ticket #106); snapshotted onto a sale + printed on that party's invoice
  isWalkIn:    false                         // the reserved "Walk-in Customer" cannot be archived/renamed
  isActive:    true
  opening:     { amount: "500.00", direction: "RECEIVABLE" | "CREDIT" }   // create-only, optional
  // HL dual-write (Cloud-Function owned; client writes syncStatus=PENDING on save):
  hlCustomerId: "..." | null
  hlAccountId:  "..." | null
  syncStatus:   "PENDING" | "SYNCED" | "FAILED"
  createdBy, createdAt, updatedAt
```
Models: `Entity`, `EntityInput`, `EntityRole`, `OpeningBalance`, `BalanceDirection` (RECEIVABLE|CREDIT|SETTLED),
`HlSyncStatus`. Balance read live via `EntityBalance` (never persisted here).

---

# Part 2 — Inventory (M4 · Brief #41)  *(proposed)*

Three top-level collections. **No subcollections.** A phone is **not** a top-level entity — it is a
`Product` with `trackingMode = SERIALIZED` carrying a phone **attribute set**, with individual **units
(serials)** underneath. Only `SERIALIZED` is implemented now; the enum carries the other modes for retrofit.

```
products/{productId}      ← the SKU (groups identical phones)   productId == the deterministic skuKey
serials/{serialId}        ← one physical unit / one IMEI        (FK: productId)
attributes/{attributeId}  ← managed vocabularies (brand/model/capacity/color/carrier/location)
imeiIndex/{imei}          ← in-stock IMEI uniqueness guard      (written in the SAME txn as a serial; released on sold/archive)
```

> **Concurrency (PO decision #1 — locked):** the shop is **multi-cashier**. Uniqueness must be **race-safe**,
> not query-then-create (two simultaneous adds would both pass a pre-check). Two mechanisms enforce it:
> **(a)** `productId` **is** the `skuKey` → find-or-create is an atomic doc read/write, so no two SKUs can ever
> share a `skuKey`; **(b)** `imeiIndex/{imei}` is written **in the same transaction** as its serial and is the
> real in-stock-IMEI uniqueness guarantee.

## `products/{productId}` — the SKU
A SKU groups phones identical across **brand + model + capacity + color + carrier**. Selling price lives here
(a default, overridable at sale). Cost does **not** — cost is per-unit.

```
products/{productId}
  productId:     "aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked"
                                         // own id, stored, == doc key == the deterministic skuKey:
                                         // the 5 SKU-defining attribute IDs in fixed order
                                         // (brand→model→capacity→color→carrier), joined by "_"
                                         // (id-safe chars only). Using it AS the doc id makes find-or-create
                                         // ATOMIC → concurrent cashiers can never create duplicate SKUs (#1).
  trackingMode:  "SERIALIZED"            // SERIALIZED|QUANTITY|VARIANT|SERVICE — only SERIALIZED now
  attributes: {                          // GENERIC attribute set — NOT top-level phone columns.
    brand:    { attributeId: "aBrandApple",    name: "Apple" },
    model:    { attributeId: "aModeliPhone15", name: "iPhone 15" },
    capacity: { attributeId: "aCap128",        name: "128 GB" },
    color:    { attributeId: "aColorPink",     name: "Pink" },
    carrier:  { attributeId: "aCarrUnlocked",  name: "Unlocked" }
  }
  defaultSellingPrice: "699.00"          // decimal STRING; overridable per sale
  isActive:      true                    // soft-archive
  createdBy, createdAt, updatedAt
```
- Each attribute is `{ attributeId, name }` — `attributeId` points into `attributes/`; `name` is a denormalized
  label for fast list render.
- **Stock count is not stored** — it is computed by grouping in-stock serials on `productId` (see below).
- **Because `productId` is the deterministic `skuKey`, the doc id is immutable-by-content:** correcting a
  SKU-defining attribute (wrong color/capacity) is conceptually a **different SKU** — handle it as move the
  units + archive the old product, not an in-place attribute edit. (Accepted trade for atomic dedupe, #1.)

## `serials/{serialId}` — one physical unit (one IMEI)
The individual phone. Carries per-unit cost/condition/status/location and the FK to its SKU. This doc is the
whole bridge to Sales: a sale line references **`productId` + `serialId`**.

```
serials/{serialId}
  serialId:   "sPINK_A"                  // own id, stored, == doc key
  productId:  "pPINK01"                  // FK → products/{productId}  (the SKU)
  imei:       "356938035699001"          // unique across all serials (dup-checked on add)
  cost:       "560.00"                   // decimal STRING — PER UNIT (drives profit-per-device)
  condition:  "NEW"                      // NEW | USED  (fixed enum; grading deferred)
  status:     "IN_STOCK"                 // IN_STOCK | RESERVED | SOLD  (stock lifecycle)
  location:   { attributeId: "aLocWH_A", name: "Warehouse A" }   // managed vocab, PER-UNIT (Sales filters by it)
  isActive:   true                       // soft-archive (a correction) — kept SEPARATE from status:SOLD
  saleId:     null                       // set to the sale record id when SOLD (Sales-loop hook)
  purchaseId: "aB3xK…"                   // FK → purchases/{id}: the Add-Inventory batch that brought this
                                         // unit in (M11, ticket #106). Written at intake, IMMUTABLE.
                                         // A batch reversal finds its stock by this field and nothing
                                         // else — there is no way to reconstruct it afterwards, so units
                                         // added before #106 carry null and their batch can't be reversed.
                                         // Null too on the two direct add paths, which book no purchase.
  createdBy, createdAt, updatedAt
```
- **`status` (lifecycle) vs `isActive` (soft-delete) are deliberately separate** so "sold" (a real outcome) is
  never confused with "removed by mistake."
- `location` and `condition` are **per-unit** — the same SKU can sit in different locations and be New/Used.
- **Write path (transactional, #1):** adding a unit runs a transaction that (a) checks `imeiIndex/{imei}` is
  absent → else reject as a duplicate in-stock IMEI, (b) creates this `serials` doc, (c) creates
  `imeiIndex/{imei}`. All-or-nothing. **On sold/archive** (`status → SOLD` or `isActive → false`) the same
  transaction **deletes `imeiIndex/{imei}`**, releasing the IMEI so the phone can be re-added later (#2).
- **`serialId`, `productId`, and `imei` are immutable once created** (the security rules pin them on
  update; #44). Lifecycle/`condition`/`cost`/`location` are editable in place, but a **mistyped IMEI is
  corrected by voiding the unit** (archive + release its `imeiIndex`) **and re-adding** a fresh unit with
  the right IMEI — never by editing `imei` in place. This mirrors the SKU-attribute rule above (a
  different IMEI/attribute is handled as a new record, not an in-place edit) and guarantees the
  `serial ↔ imeiIndex` pair can never desync.

## `imeiIndex/{imei}` — in-stock IMEI uniqueness guard
The **race-safe, in-stock-only** uniqueness mechanism for IMEIs (PO decisions #1 + #2). Doc id **is** the IMEI
(a known, immutable key → O(1) existence check + scan lookup). An entry **exists only while its phone is in
stock**: created in the same transaction as the serial, deleted when that serial is sold or archived.

```
imeiIndex/{imei}
  imei:      "356938035699001"           // == doc key
  serialId:  "sPINK_A"                    // the in-stock serial currently holding this IMEI
  productId: "aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked"
```
- **Uniqueness is scoped to in-stock**, not all-time: a `SOLD`/archived serial keeps its `imei` field for
  history, but has **no** `imeiIndex` entry — so a returned/traded-in phone with the same IMEI can be re-added
  as a fresh in-stock unit (#2). A new serial doc is created; the old sold one is untouched.

## `attributes/{attributeId}` — managed vocabularies
One collection for all reference lists, discriminated by `type`, with **add-new inline**. `parentId` wires
**model → brand** (model list is filtered by the picked brand). A unified collection (vs. five separate ones)
is the retrofit-friendly choice: a future VARIANT product's attribute types drop in as new `type` values.

```
attributes/{attributeId}
  attributeId: "aColorPink"              // own id, stored, == doc key
  type:        "brand" | "model" | "capacity" | "color" | "carrier" | "location"
  name:        "Pink"                    // display form (trim + collapse whitespace, case kept)
  nameKey:     "pink"                    // case-folded dedupe key = AttributeName.matchKey(name)
  parentId:    null                      // for type=model → the brand's attributeId; null otherwise
  isActive:    true
  createdBy, createdAt
```
**Case-insensitive dedupe:** add-new-inline dedupes on `(type, parentId, nameKey)`, so `"Apple"` / `"apple"` /
`" APPLE "` collapse to one row (the display `name` keeps the first-seen case). The picker is the first guard
(the user selects the existing value); `nameKey` is the guarantee underneath it, defined once in shared code
(`util/AttributeName`) so every platform folds identically. Keeps SKU grouping reliable (#41 `[hard]` steer).

---

## Derived / computed (not stored)

- **Stock count per SKU** = `serials where productId == P AND status == "IN_STOCK" AND isActive == true`, counted
  in memory (the ViewModel loads products + in-stock serials once and groups). No counter to drift. A
  denormalized `inStockCount` on the product is the scale path if unit volumes grow.
- **IMEI uniqueness (in-stock, race-safe)** — enforced **transactionally** against `imeiIndex/{imei}`, created
  with the serial and released on sold/archive (see `imeiIndex` above). **Not** a query-then-create pre-check,
  and **not** all-time — scoped to phones currently in stock (#1, #2).

---

## Worked example — "iPhone 15 · 128 GB · Pink · Unlocked", 2 units

**1) Vocabulary** (Pink created inline; the rest already exist):
```
attributes/aBrandApple    { attributeId:"aBrandApple",    type:"brand",    name:"Apple",     parentId:null }
attributes/aModeliPhone15 { attributeId:"aModeliPhone15", type:"model",    name:"iPhone 15", parentId:"aBrandApple" }
attributes/aCap128        { attributeId:"aCap128",        type:"capacity", name:"128 GB",    parentId:null }
attributes/aColorPink     { attributeId:"aColorPink",     type:"color",    name:"Pink",      parentId:null }   ◄ new
attributes/aCarrUnlocked  { attributeId:"aCarrUnlocked",  type:"carrier",  name:"Unlocked",  parentId:null }
attributes/aLocWH_A       { attributeId:"aLocWH_A",       type:"location", name:"Warehouse A", parentId:null }
```

Let `SKU = "aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked"` (the deterministic `productId`).

**2) SKU** (transaction: `products/{SKU}` absent → create it):
```
products/aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked
  { productId:"…(SKU)…", trackingMode:"SERIALIZED",
    attributes:{ brand:{attributeId:"aBrandApple",name:"Apple"}, model:{attributeId:"aModeliPhone15",name:"iPhone 15"},
                 capacity:{attributeId:"aCap128",name:"128 GB"}, color:{attributeId:"aColorPink",name:"Pink"},
                 carrier:{attributeId:"aCarrUnlocked",name:"Unlocked"} },
    defaultSellingPrice:"699.00", isActive:true, createdBy:"uid_rishi", createdAt:…, updatedAt:… }
```

**3) Units** — 2 scanned IMEIs. Each is one transaction: `imeiIndex/{imei}` absent → write serial **and** index:
```
serials/sPINK_A                                    imeiIndex/356938035699001
  { serialId:"sPINK_A", productId:"…(SKU)…",         { imei:"356938035699001",
    imei:"356938035699001", cost:"560.00",             serialId:"sPINK_A",
    condition:"NEW", status:"IN_STOCK",                productId:"…(SKU)…" }
    location:{attributeId:"aLocWH_A",name:"Warehouse A"},
    isActive:true, saleId:null, createdBy:"uid_rishi", … }

serials/sPINK_B                                    imeiIndex/356938035699019
  { serialId:"sPINK_B", productId:"…(SKU)…",         { imei:"356938035699019",
    imei:"356938035699019", cost:"558.00",             serialId:"sPINK_B",
    condition:"NEW", status:"IN_STOCK",                productId:"…(SKU)…" }
    location:{attributeId:"aLocWH_A",name:"Warehouse A"},
    isActive:true, saleId:null, createdBy:"uid_rishi", … }
```

**How it connects**
```
  attributes/aBrandApple ◄──parentId── attributes/aModeliPhone15
        ▲                                     ▲
        │ attributes.brand.attributeId        │ attributes.model.attributeId
        └──────────── products/{SKU} ◄─────────┘
              ▲        ▲   ▲        ▲
   productId  │        │   │        │  productId
      serials/sPINK_A  │   │   serials/sPINK_B
              │        │   │        │
     location │  imeiIndex/…001  imeiIndex/…019 (serialId back-refs)
              ▼
     attributes/aLocWH_A
```
Live view → "Apple · iPhone 15 · 128 GB · Pink · Unlocked — **2 in stock**".

- **Re-adding the same variant** recomputes the same `productId` (SKU), the transaction finds the existing
  product, and appends `serials/sPINK_C` + `imeiIndex/…` → **3 in stock**. No duplicate SKU (atomic).
- **Selling `sPINK_A`** (later, via Sales): one transaction sets `sPINK_A.status:"SOLD"` + `saleId`, and
  **deletes `imeiIndex/356938035699001`**. Count falls to 2, and IMEI `…001` is now free to re-enter stock.
- **The phone comes back** (trade-in/return): adding IMEI `…001` again passes the `imeiIndex` check (the old
  entry was released), creating a **fresh** `serials/sPINK_D` + a new `imeiIndex/…001`. The old `SOLD`
  `sPINK_A` stays untouched for history (#2).

---

## Out of scope for this schema (deferred)

- **No scanner-channel collection.** M4's in-scope scan is *local* (camera → IMEI → form field, same device).
  The Firebase scanner **hand-off** channel (PRD §9.7 — phone→desktop, scan-to-sale-cart) is a separate,
  deferred ticket.
- *(Note: `imeiIndex` is **not** deferred — the PO promoted it from an optional speed-up to the required
  correctness mechanism for concurrent, in-stock-scoped IMEI uniqueness. See `imeiIndex` above.)*
- **No HL fields.** Inventory is Firebase-only/operational. Inventory valuation / COGS is the open PRD §6.4
  question, handled with Purchase/Sales.
- **QUANTITY / VARIANT / SERVICE** — the `trackingMode` enum carries them; none are implemented. A QUANTITY
  product would later add an `onHandQty` and skip `serials` — the shape already allows it.

---

## Open decisions (locked recommendations — confirm before build)

| # | Decision | Recommendation |
|---|---|---|
| 1 | Units: top-level `serials` vs subcollection | **Top-level `serials`** (Sales/scan queries cut across SKUs) |
| 2 | Vocabularies: one collection vs five | **Unified `attributes` + `type`** (retrofittable) |
| 3 | SKU dedupe | 🔒 **`productId` == the deterministic `skuKey`** → atomic find-or-create (PO #1) |
| 4 | Capacity representation | **A managed vocabulary (a 6th `type`)** so grouping stays consistent |
| 5 | Attribute rename propagation | **Denormalize `name`; backfill later** (fast render) — PO accepted |
| 6 | Stock count | **Compute in memory**; denormalized counter is the scale path — PO accepted |
| 7 | IMEI uniqueness | 🔒 **Transactional `imeiIndex/{imei}`, in-stock scoped** (created w/ serial, released on sold/archive) — PO #1, #2 |
| 8 | Unit archive vs sold | **Separate `status` + `isActive`** |

---

# Part 3 — Sales (M5 · Brief #60)

The **sell-side** operational record. One top-level collection. Selling a phone flips its
`serials/{serialId}` unit → `SOLD` (stamped with `saleId`) and **releases its `imeiIndex/{imei}`**,
in the **same transaction** that writes the `sales/{saleId}` doc — so a unit is never sold without
its sale record, or vice-versa (the #58 atomicity lesson). The sale is written `PENDING`; the
`onSaleWrite` Cloud Function posts it to Humble Ledger and owns `syncStatus`/`hl*`.

```
sales/{saleId}            ← one completed sale         saleId == the doc key (auto-id, stored)
```

> **Concurrency (no cart lock):** two cashiers may hold the same unit in their carts. The
> mark-sold transaction **re-reads each serial at commit** and aborts (nothing written) if it is no
> longer `IN_STOCK`/active — the race loser gets a graceful "already sold" message. The guarantee is
> the transaction, not a lock.

## `sales/{saleId}` — one completed sale
Money is a decimal **string** everywhere. Prices are **tax-exclusive**; tax is added at checkout
from the company `companySettings/profile.tax` config and snapshotted here. The original price **and**
the discount are both kept (at line and sale level) so discounting is reportable.

```
sales/{saleId}
  saleId:            "s_ab12..."               // own id, stored, == doc key
  customerEntityId:  "walk-in-customer" | "<entityId>"   // FK → entities/{entityId}
  isWalkIn:          true|false                // snapshot; a walk-in must pay in full

  lines: [                                     // 1..N (≤ 100), at least one
    { kind: "INVENTORY",                       // a phone leaving stock
      productId, serialId, imei,               // FK → products / serials; imei snapshot
      label:      "Apple iPhone 15 · 128 GB · Pink · Unlocked",   // denormalized display
      listPrice:  "699.00",                    // product.defaultSellingPrice at sale time (reference)
      unitPrice:  "699.00",                    // price the cashier set (editable)
      lineDiscount: "20.00",                    // per-item discount (kept separately)
      netPrice:   "679.00",                    // = unitPrice - lineDiscount (derived)
      cost:       "560.00" },                   // serial.cost SNAPSHOT → drives COGS
    { kind: "CUSTOM",                          // revenue-only line (no stock, no COGS)
      name: "Phone case", unitPrice: "25.00", lineDiscount: "0.00", netPrice: "25.00" }
  ]

  subtotal:        "704.00"                    // Σ line.netPrice (pre-tax)
  saleDiscount:    "0.00"                       // whole-sale discount (kept)
  taxInclusive:    false                        // (#106) how the sale was priced. false/absent = tax-EXCLUSIVE (typed
                                                //   prices are pre-tax, tax added on top). true = tax-INCLUSIVE (typed
                                                //   prices already contain tax; taxableAmount is backed out of grandTotal).
                                                //   Per-sale; the till resets it to false on every new sale.
  taxableAmount:   "704.00"                     // = subtotal - saleDiscount (exclusive) OR grandTotal ÷ (1 + Σ rates) (inclusive)
  taxLines: [ { name: "GST", rate: "0.05", amount: "35.20" }, ... ]   // 0..2 snapshot
  taxTotal:        "35.20"
  grandTotal:      "739.20"                     // = taxableAmount + taxTotal
  cogsTotal:       "560.00"                     // Σ inventory-line cost (0 → HL cogs omitted)

  payments: { cash: "600.00", card: "139.20", bank: "0.00" }   // card posts to HL Bank; split kept here
  amountPaid:      "739.20"                     // = cash + card + bank
  balanceRemaining:"0.00"                       // = grandTotal - amountPaid (named customer's new AR if > 0)
  hasOutstandingBalance: false                  // (#83) denormalized `balanceRemaining != 0`, evaluated with Money at
                                                //   create. Client-owned. Sales History filters "with a balance" on this
                                                //   boolean — money is a decimal STRING and Firestore orders strings
                                                //   lexicographically ("100.00" < "90.00"), so an inequality query on
                                                //   balanceRemaining is silently wrong.

  note:            "..." | null
  status:          "COMPLETED" | "VOIDED"       // operational lifecycle. VOIDED (#85) = fully reversed.

  buyerName:       "..." | null                // walk-in buyer capture (client-set on create; UI in T2)
  buyerPhone:      "..." | null                // walk-in buyer capture (client-set on create; UI in T2)
  buyerTaxNumber:  "..." | null                // (#106) buyer's GST/HST number for the invoice "Bill To" line. Entered at
                                                //   checkout (prefilled from entities/{id}.taxNumber, editable per sale) and
                                                //   SNAPSHOTTED here so a later contact edit can't change an issued invoice.
                                                //   Set for a walk-in too (typed for the bill; no contact to save to). The
                                                //   optional "Save to contact" action writes the value back to the entity.

  // HL dual-write (CF-owned; client writes syncStatus=PENDING on save):
  syncStatus:      "PENDING" | "SYNCED" | "FAILED"
  hlSaleId:        "..." | null                 // HL's SALE transaction id (persisted by syncSale, #85) — reversed if no invoice can be cancelled
  hlInvoiceId:     "..." | null                 // HL's invoice id (distinct from invoiceNumber, #85) — what a void cancels + refunds against
  hlSyncedAt:      <Timestamp> | null
  hlSyncError:     "..." | null

  // Void (ticket #85) — a full reversal, never a delete. The client (an admin) writes ONLY the
  // request fields (voidReason, voidRequestedBy, voidRequestedAt, voidStatus=PENDING); the
  // onSaleWrite CF owns everything after — HL cancel/reverse + split refunds + atomic stock restore.
  // Mirrors the syncStatus spine. Firestore rules let an admin set exactly the request fields.
  voidStatus:      "PENDING" | "DONE" | "FAILED" | null   // dual-write spine (null = no void requested)
  voidReason:      "..." | null                 // REQUIRED to void; passed through to HL's cancelReason
  voidRequestedBy: "<uid>" | null               // the requesting admin; the CF re-verifies this uid is an admin
  voidRequestedAt: <Timestamp> | null           // Desktop's edge-trigger key (Admin SDK); mobile uses the voidSale callable
  voidedAt:        <Timestamp> | null            // CF-owned; set when the void settles DONE
  voidError:       "..." | null                 // CF-owned; last failure reason (e.g. a re-used IMEI index)
  hlVoidTxnId:     "..." | null                 // CF-owned; the REVERSAL transaction HL posted
  hlRefundIds:     ["...", ...] | null           // CF-owned; one refund id per payment method returned

  // Invoice (ticket #76) — CF-owned; a client may NEVER set these. Issued AFTER syncStatus=SYNCED
  // by the onSaleWrite CF, which POSTs the payload to the Humble Bill Engine.
  invoiceNumber:   "INV-000042" | null         // the number Humble Ledger minted for this sale
  invoiceUrl:      "https://…/aromex-INV-000042.pdf" | null   // public, permanent S3 URL, stored as-is
  invoiceStatus:   "PENDING" | "ISSUED" | "FAILED"
  invoiceIssuedAt: <Timestamp> | null
  invoiceError:    "..." | null                // last render failure reason (cleared on success)
  invoiceAttempts: <number>                     // AUTOMATIC failed-render count; reconcile gives up past a cap (manual Retry never increments it — ticket #77)
  invoiceRetryRequestedAt: <Timestamp> | null   // (ticket #77) Desktop sets this to ask the CF for an immediate re-issue; edge-triggered, mobile uses the `retryInvoice` callable instead
  createdBy, createdAt, updatedAt
```
- **Id:** auto-id (an immutable record; identity decoupled from content), stored in-doc under `saleId`.
- **Write path (transactional):** read each inventory line's serial → abort if not `IN_STOCK`/active
  (release the race), else set `status=SOLD` + `saleId`, delete `imeiIndex/{imei}`, and create this
  doc `PENDING` — all-or-nothing.
- **Client may only START it `PENDING`** and may never delete it (Firestore rules); the CF owns
  `syncStatus` + `hl*` after creation. The **one** client update allowed is an **admin** setting the
  void request fields (`voidReason`, `voidRequestedBy`, `voidRequestedAt`, `voidStatus=PENDING`) to
  ask the CF to reverse the sale (ticket #85) — never the CF-owned void/HL fields or `status`.
- **Void = reverse, never delete (#85):** an admin voids a sale to undo a mistake. The CF cancels the
  HL invoice (which reverses revenue + AR + tax **and** COGS/Inventory in one call), refunds any
  amount paid split across the same accounts the payment used, and restores every unit to `IN_STOCK`
  with its `imeiIndex/{imei}` re-created — in one all-or-nothing transaction — then flips `status` to
  `VOIDED`. The sale, invoice and ledger entries all survive (a cancelled invoice is a normal audit
  artifact; a missing one is not). Idempotent: HL calls are keyed/guarded so a retry never
  double-reverses or double-refunds. Guarded against a **re-used IMEI** — if `imeiIndex/{imei}` now
  points at a different serial, the void fails rather than clobber it.
- **The reserved [Walk-in Customer](Part 1 · `entities`)** (`customerEntityId = "walk-in-customer"`,
  `isWalkIn`) is the sell-side mirror of the Unspecified Supplier: fixed id, lazily bootstrapped in the
  CF, visible in Entities. A walk-in sale must be paid in full.

## `purchases/{purchaseId}` — one Add-Inventory batch  *(M6 · reversible since M11, ticket #106)*
One doc per Add-Inventory **submission**, not per unit — written in the same transaction as the
stock it pays for. Full field list in `firebase/SCHEMA.md`; what matters here is the reversal.

```
purchases/{purchaseId}                       // id = doc key
  partyEntityId, totalCost, cashPaid, bankPaid
  unitCount:        2                        // phones this batch brought in, recorded at intake (#106)
  syncStatus:       "PENDING" | "SYNCED" | "FAILED"    // CF-owned from here down
  hlPurchaseTxnId, hlPayoutCashTxnId, hlPayoutBankTxnId // the legs a reversal reverses
  status:           "ACTIVE" | "REVERSED"
  reversalRequestedAt, reversalRequestedBy, reversalReason   // the request (client/Desktop)
  reversalStatus:   "PENDING" | "DONE" | "FAILED"
  hlReversalTxnIds: { purchase: "…", payout_cash: "…", "commission_<id>:accrue": "…" }
  reversedAt, reversalError
```

**Reversing a batch** (`reversePurchaseCore`, the mirror of a sale void). A batch is the unit of
reversal because it is the unit of posting: one HL transaction covers the whole batch, and HL
reverses a transaction whole — undoing one phone out of eight would leave a posted purchase
describing stock that no longer exists.

| Step | Effect |
|---|---|
| Admin gate | re-checked **server-side** (`users/{uid}.role`), plus a written reason |
| Wholeness check | every unit still in stock, still active, all accounted for — **before** any HL call, so a blocked batch fails while the ledger is untouched |
| Reverse each leg | purchase, each payout, and every commission with `sourceBatchId == purchaseId`, by transaction id; each reversal id persisted **as it lands** so a retry re-reverses nothing |
| Pull the stock | every unit archived + its `imeiIndex` released, commissions marked REVERSED, batch flipped REVERSED — one transaction |

- **Blocked, not partially done**, when a unit has been sold, was removed individually, or the batch
  predates `serials.purchaseId`. The reason names the IMEIs.
- An `imeiIndex` entry pointing at a *different* serial is left alone — the handset was re-added and
  is genuinely in stock.
- Deleting a single phone from the Inventory screen is a **different act**: it archives the unit and
  frees its IMEI but deliberately does **not** touch the ledger. The confirmation says so.

## `moneyEntries/{entryId}` — one movement of money (M8, ticket #90)

The rebuilt transactions screen: money from one account to another, where an account is a **party**,
the shop's **Cash**, or the shop's **Bank**. It covers a customer paying their balance down, the shop
paying someone back or lending, one party settling another's account, and a cash→bank deposit.

**This document deliberately stores no balances.** Not on the parties, not as a "balance after"
snapshot. The legacy app kept a running balance on each party and mutated it with a read taken
outside any transaction, so two concurrent entries could silently lose one side — and because the
stored number *was* the truth, the loss was undetectable. Balances here are always read from Humble
Ledger, derived from its journal entries.

```
moneyEntries/{entryId}                       // id = doc key, also stored as entryId
  entryId:      "m_ab12..."
  from: { kind: "PARTY"|"CASH"|"BANK", entityId: "<entityId>" | null }   // entityId iff kind=PARTY
  to:   { kind: "PARTY"|"CASH"|"BANK", entityId: "<entityId>" | null }

  amount:       "500.00"                     // decimal STRING, always POSITIVE — direction is
                                             // carried by from/to, never by a sign
  note:         "..." | null
  entryDate:    <Timestamp>                  // the ACCOUNTING date; may be backdated
  createdBy, createdAt, updatedAt            // createdAt is when it was typed, ≠ entryDate

  // Reversal links (a mistake is corrected by reversing; entries are never edited or deleted)
  reversesEntryId:   "<entryId>" | null      // set on the reversing entry
  reversedByEntryId: "<entryId>" | null      // set on the original, atomically with the reversal

  // HL dual-write (CF-owned; client writes syncStatus=PENDING on create):
  syncStatus:      "PENDING" | "SYNCED" | "FAILED"
  hlTransactionId: "..." | null              // set for the raw-journal routes
  hlSyncedAt:      <Timestamp> | null
  hlSyncError:     "..." | null
```

**Routing** (`onMoneyEntryWrite` → `syncMoneyEntry`) — a high-level HL endpoint wherever one fits,
because HL treats those as first-class; a raw journal only for the two cases none covers:

| from → to | HL call | posting type |
|---|---|---|
| party → cash/bank | `POST /api/v1/payments` | PAYMENT |
| cash/bank → party | `POST /api/v1/customer-payouts` | PAYOUT |
| party → party | `POST /api/v1/transactions` | JOURNAL |
| cash ↔ bank | `POST /api/v1/transactions` | JOURNAL |

- **Idempotency:** `sourceId = "money_<entryId>"`, derived from the doc id, so a redelivered trigger
  re-posts nothing.
- **Payments here are NOT applied to an invoice** — they settle the party's overall balance
  (brief #89). A *sale's own* payments do carry `invoiceId` (ticket #88); these don't.
- **Party not yet synced to HL** → the entry is left `PENDING` **without rewriting the doc** (a
  rewrite re-fires the trigger in a hot loop); the reconcile sweep retries it.
- **Client may only START it `PENDING`** and may never update or delete it. Reversal marks the
  original via the Admin SDK in the same transaction that creates the reversing entry.

## `commissionRules/{ruleId}` — a standing "pay X per phone" arrangement (M9, ticket #97)

**Admin-managed.** *Whenever phones land at a location, a party earns per phone.* Several rules may
target one location; each fires independently at intake. Touches Humble Ledger not at all — it only
decides what a later intake will owe.

```
commissionRules/{ruleId}                     // id = doc key, also stored as ruleId
  locationAttributeId: "<attributeId>"       // an `attributes` value of type LOCATION
  payeeEntityId:       "<entityId>"          // the party who earns it
  rateKind:            "PER_UNIT" | "PERCENT_OF_COST"
  rate:                "5.00" | "0.02"       // decimal STRING; percent as a FRACTION
  isActive:            true                  // switched off ≠ deleted
  createdBy, createdAt, updatedAt
```

- **Write is admin-only.** A rule silently creates money owed on every future intake, which is a
  settings-grade act rather than a daily one.
- **Read is `inventory` view** — the cashier adding stock must see and confirm what a rule proposes,
  even though only an admin may edit it.
- **Switching a rule off never touches commission already earned.** What's owed is owed.
- No hard delete.

## `commissions/{commissionId}` — one payee's commission from one batch (M9, ticket #97)

Written `PENDING` **in the same Firestore transaction as the stock and its purchase**. That extends
#58's invariant — never in-stock inventory without its purchase record — to *never stock without its
obligation*. A separate write that could fail on its own would eventually leave phones in the system
and a forgotten debt, which is the exact failure this feature exists to prevent.

```
commissions/{commissionId}                   // id = doc key, also stored as commissionId
  payeeEntityId, locationAttributeId
  ruleId:        "<ruleId>" | null           // null when the amount was hand-edited at intake
  unitCount:     12                          // units at this location
  basisAmount:   "14400.00"                  // cost a percent rule applied to; "0" for per-unit
  amount:        "60.00"                     // decimal STRING — always accrued
  paidCash:      "0"                         // given now, decimal STRING
  paidBank:      "0"
  sourceBatchId: "<purchases doc id>"
  createdBy, createdAt, updatedAt
  syncStatus:      "PENDING" | "SYNCED" | "FAILED"    // CF-owned from here down
  hlTransactionId, hlSyncedAt, hlSyncError
```

**How it posts** (`onCommissionWrite` → `syncCommission`) — the same netting shape as buying stock on
credit, so nothing new was invented in the books:

| Step | HL call | Effect |
|---|---|---|
| Accrue | `POST /customer-purchases` against a `Commission` EXPENSE account | DR Commission · CR payee → their balance moves in their favour |
| Give now (optional) | `POST /customer-payouts` | DR payee · CR Cash/Bank → nets it back down |

- Idempotent on `commission_<commissionId>[:payout_cash|:payout_bank]`.
- **Payee not yet synced to HL** → left `PENDING` without rewriting the doc (a rewrite re-fires the
  trigger in a hot loop); the reconcile sweep retries it.
- **Earned on arrival only** — moving a unit between locations later earns nothing, or stock could be
  shuffled back and forth to mint commission.
- **Clawback on a later void or return is out of scope in v1** (ticket #97): deciding what happens
  once the payee has been paid deserves its own conversation.
- Client may only START it `PENDING` and may never update or delete it.

---

## Party statement PDF (`renderStatement` + `aromex-statement`) — ticket #109

A party's **statement** is a printable PDF of everything they owed at the start of a period, every
movement since, what they owe now, and how old the debt is. It is rendered by the Humble Bill Engine
exactly like an invoice, against the **`billApps/aromex-statement`** template (owned by the
manager — the app never touches the engine or its templates).

**Nothing is stored** — a statement is assembled on demand and rendered; there is no
`statements/{id}` collection.

### Who does what

| Layer | Responsibility |
|---|---|
| Shared `BuildPartyStatementUseCase` (Kotlin) | Pages every ledger row (cap 2000), makes the opening-balance call, computes the FIFO aging → a `StatementDocument`. Gated on `profiles: view`. Tested in `:sharedLogic:jvmTest`. |
| Device (Android/iOS ViewModel) | Calls the use case, then the `renderStatement` callable with the assembled document. |
| CF `renderStatement` (callable) | **Re-checks `profiles: view` server-side**, reads the seller letterhead (`companySettings/profile`) and buyer (`entities/{id}`), stamps the issue instant in the shop's timezone, wraps the engine envelope, POSTs it, returns `{ url }`. The device never reaches the (unauthenticated) engine directly. |

- **Opening balance** is *not* in the range response — the use case makes one extra call
  (`getStatement(from = null, to = <day before the period>, limit = 1)`) and reads HL's own closing
  balance as at that day. An all-time statement (`from = null`) opens at `0.00`.
- **Aging is of the balance, from the ledger movements** — never HL `/receivables` (which ages unpaid
  *invoices*, a different quantity that ignores unapplied credits, so its buckets would not reconcile
  with the closing balance). Direction comes from HL's own running balance (`delta = balance −
  previous`); a FIFO queue seeded with the opening balance settles oldest debt first. **The four
  buckets sum to the closing balance exactly** (asserted in a test). A party in credit shows no aging
  block, not four zeros.

### Callable request (device → CF)

```jsonc
{
  "entityId": "<entities doc id / HL externalId>",
  "statement": {
    "periodFrom": "1 Jan 2026",           // omitted for an all-time statement
    "periodTo": "31 Mar 2026",
    "openingBalance": "1200.00",
    "totalDebits": "5400.00",
    "totalCredits": "4100.00",
    "closingBalance": "2500.00",
    "agingBuckets": [                       // empty ⇒ CF omits the whole block
      { "label": "0–30 days", "amount": "500.00" },
      { "label": "31–60 days", "amount": "1000.00" },
      { "label": "61–90 days", "amount": "0.00" },
      { "label": "90+ days",  "amount": "1000.00" }
    ],
    "statementRows": [                      // oldest first; `note` only when the toggle is on
      { "date": "12 Feb 2026", "description": "Payment for INV-000042",
        "note": "paid by cheque", "debit": "", "credit": "700.00", "balance": "1800.00" }
    ]
  }
}
```

Money is **plain decimal strings** — no symbol, no thousands separators (the engine adds `$` and
formats). An empty `debit`/`credit` renders an empty cell, not `$0.00`. Dates are date-only display
strings (formatted client-side; a calendar date needs no timezone). The **issue** instant is stamped
by the CF in the shop's timezone.

### Engine envelope (CF → Bill Engine)

The CF sends `{ "appId": "aromex-statement", "data": { … } }`. Inside `data` it adds the seller block
(`sellerName`/`sellerAddress`/`sellerContact`/`sellerPhone`/`sellerTaxLine`/`logoUrl`), the
`customer` block + `customerTaxLine`, and `issueDate`, then passes the summary, `agingBuckets` and
`statementRows` through. An absent value is **omitted** (never sent as `""`), so no stray label
renders. `statementRows` and `agingBuckets` are arrays the template renders through helpers;
everything else is a flat string.

### Deploy

`firebase deploy --only functions:renderStatement` (by name — never a bare `--only functions`, never
`--force`).

### Notes index (follow-up)

The use case takes `noteByTransactionId` (row `transactionId` → note). Money-entry and sale notes are
keyed by HL transaction id. The mobile ViewModels currently pass an empty map (a row with no matching
record simply shows no note — HL's own `description` still labels it); populating it from the party's
money entries / sales is a follow-up.
