# [Brief] Inventory — generic Product model + scan-to-add (M4)

> **Milestone:** M4 — Inventory + Scanner. **The heart is the data model** — a generic `Product` that phones
> are one *mode* of, deliberately designed against the whole **stock → purchase → sale** loop so Purchase and
> Sales inherit the right shape. **Firebase-only** (operational); permission-gated (`inventory`).

## 🎯 What we're building & why
The ability to **manage the stock a shop holds** — the phones (and, later, any goods) it buys and sells. Staff
can see what's in stock, add new stock with its IMEIs (scanning them in on mobile), edit, and archive. It's the
**foundation the money features stand on**: Purchase brings stock in, Sales takes it out — both reference this
model. Getting the model right *now* is the whole point: it's blocked-upon by Purchase + Sales, and it's where
the North Star is won or lost (build it generic so a general POS retrofits without a rewrite).

## 👤 Who it's for
Shop owner / staff (permission `inventory`), managing their phone stock day to day — the same people who'll
later buy and sell against it.

## ✅ What it must do (capabilities)
- **Browse stock:** searchable/filterable list of products (SKUs) with their **stock count** + status; drill in
  to see the individual units (IMEIs).
- **Add stock:** create a product (SKU) by picking managed attributes (brand → model → capacity → color →
  carrier) and add its **units** (IMEIs) with per-unit cost / location / New-or-Used; **scan** IMEIs on mobile.
- **Edit / archive** products and units; correct details; set a unit's status.
- **Manage the attribute lists** (add a new brand/model/color/carrier/location inline).
- All **Firebase-only** (operational), **permission-gated** (`inventory`).

## 🌟 What "good" looks like
- Add a phone SKU, scan three IMEIs → it shows as "3 in stock," each unit with its own cost/condition; adding
  the same model again **groups under the same SKU** (no duplicates).
- The model reads as **generic** — a general-retail (quantity) product would slot into the *same* shape later,
  no rewrite.
- **Purchase and Sales can reference it cleanly** — a sale can pick a specific unit (IMEI); the model was
  designed against that loop, not just "browse inventory."

## 🚫 Non-negotiables
- **Generic `Product` + `trackingMode`** (`SERIALIZED | QUANTITY | VARIANT | SERVICE`). Implement **`SERIALIZED`**
  (phones) now; a phone is `SERIALIZED` + its attribute set — **never a hardcoded "Phone" entity**. (North Star.)
- **Product = SKU, with individual units (serials) underneath.**
- **Generic naming** (`products`, `serials`/units) — "phone" is a mode, not the domain noun.
- **Designed against the whole stock → purchase → sale loop** — a Sale line must be able to reference a
  **product + a specific unit** (serialized) or product + qty (quantity, later). Don't model only for browsing.
- **Firebase-only; no HL** (inventory is operational). Permission-gated; no secrets committed.

## 🧭 Technical steers (from the PO — the model, decided)
- `[hard]` **Product = SKU + units.** The SKU groups identical phones; each IMEI is a unit.
- `[hard]` **A phone SKU is defined by brand + model + capacity + color + carrier**, attached as a **generic
  attribute set** on `Product` (not top-level phone columns). Fine-grained grouping.
- `[hard]` **Per-unit fields:** IMEI · cost · status (in-stock / sold / reserved) · location · **condition
  (New / Used)**. **Selling price lives on the SKU** (a default, overridable at sale). **Cost is per-unit**
  (drives profit-per-device later).
- `[hard]` **Managed attribute lists** — brand / model / color / carrier / location are reference vocabularies
  with **add-new inline**, model filtered by brand. Consistency → reliable SKU grouping.
- `[hard]` **Basic scan-to-add on mobile** (Android + iOS): camera → fills the IMEI when adding a unit. Desktop
  = manual entry.
- `[preference]` Follow `/kmp-arch`: shared generic model + repo interface + use cases; per-platform impls + UI.
  Firestore layout (units as subcollection vs top-level `serials`) is the cofounder's call.

## 🧊 Happy to defer
- **QUANTITY / VARIANT / SERVICE modes** — *structure* for them, *implement* only SERIALIZED.
- **Stock intake via Purchase** (M5) — v1 adds stock **manually** in Inventory; Purchase becomes the primary
  intake later.
- **Bulk-scan**, **cross-device scan hand-off** (phone → desktop), and **scan-to-add-to-a-sale-cart** (Sales).
- **Detailed grading** (A/B/C, battery %) beyond New/Used; a rich **selling-price-setting UX** (a default field
  is enough here).
- **Inventory valuation / COGS** accounting — that's the HL side, handled with Purchase/Sales.

## 📎 References
- **PRD** §9.6 (Inventory — IMEI-level), §9.7 (Scanner), §7.2 (permissions); **FEATURES.md** §3 (the phone
  line-item attributes) + the legacy **Brand → Model → Phone** hierarchy.
- **`CLAUDE.md`** "North star" (generic `Product` / `trackingMode`), generic naming.
- **M3** is the pattern to mirror (shared model → repo interface → use case → per-platform impls) — but Inventory
  is **Firebase-only** (no HL dual-write).
