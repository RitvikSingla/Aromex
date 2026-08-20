> **Platforms: Android · iOS · Desktop.** **Builds on the merged Add-Inventory flow (#52).** Adds a
> **shared SICKW paste parser** + a paste entry point + **inline editing on the review table**. iPhone-focused.
> ⚠️ **Follow `/kmp-arch`** — the parser is **shared Kotlin** (pure + unit-tested); paste box + table edits are
> native per platform. Milestone: **M4 — Inventory + Scanner**.

## 📖 Story / Why
Phone shops look phones up on **SICKW** (an IMEI service, strongest on iPhones) and get a block of copyable
text with the phone's identity. This lets them **paste that text — one phone or many at once — and have the
app auto-fill the SKU attributes**, dropping the phones onto the Add-Inventory **review table** where the user
only fills the shop-specific fields (cost / condition / location) and confirms. It turns tedious per-phone
dropdown-picking into paste-and-review, especially for bulk intake.

This is the **paste approach** (deliberate — the customer can use SICKW's free web lookups). A direct SICKW
**API** integration is a **separate, later** enhancement that feeds the *same* parser pipeline.

## 🎯 The flow
1. **Entry:** next to "Add product" there is a **"Paste from SICKW"** button → a screen/box with a **large
   multiline paste area** and a **"Parse & add"** action (do **not** auto-jump the instant text is pasted —
   pasting to edit shouldn't navigate). "Paste more" appends to the current batch.
2. **Parse:** on "Parse & add", the **shared parser** splits the text into phones and maps each to our fields,
   then lands on the **review table** (the same screen 2 as manual add) pre-filled — with a summary banner
   (e.g. *"Parsed 6 phones · 1 couldn't be read"*).
3. **Fill + review:** the user sets the shop fields and confirms → the existing **atomic `AddStockUseCase`**
   write (per SKU), with the **batch-size cap** below.

## 🔤 The parser (shared Kotlin — the heart)
A pure `parseSickw(text): SickwParseResult` in `sharedLogic` (with unit tests), returning a list of parsed
phones **plus** any blocks it couldn't read. **Type-based, tolerant parsing — never rely on comma position**
(SICKW's format varies by service).

**Field mapping (from the real sample below):**
| SICKW field | → Aromex | How |
|---|---|---|
| `Model Description` (e.g. `IPHONE 14,ROW,256GB,PURPLE`) | **Model · Capacity · Color** | split on comma, classify each token: `…GB/TB` → capacity; a colour word → color; `IPHONE …` → model; region codes (`ROW`, `LL`, `ZP`…) → ignore |
| *implicit "IPHONE"* | **Brand** | infer **Apple** |
| `Sim-Lock Status` (`Unlocked`) / `Locked Carrier` | **Carrier** | `Unlocked` → "Unlocked"; if locked, use the carrier name |
| `IMEI:` | **IMEI** | primary; **ignore** `IMEI2`, `MEID`, `Serial Number` (no field), warranty, dates, Demo/Loaner/Refurb flags, `ROW` |
| Multiple phones in one paste | split into blocks | by the `IMEI:` boundary / result header; each block = one phone |

**Normalize** to our vocab casing (`PURPLE`→`Purple`, `IPHONE 14`→`iPhone 14`) and **map to existing
attributes case-insensitively; find-or-create if absent.** iPhone-only is expected — a block that isn't an
iPhone or can't yield a model is returned as **"couldn't read."**

**Test fixture (pin the parser to this exact sample; keep it as a `commonTest` resource):**
```
Model Description: IPHONE 14,ROW,256GB,PURPLE
IMEI: 353340195540565
IMEI2: 353340199954622
MEID: 35334019554056
Serial Number: KJPK6N7TLG
Estimated Purchase Date: 2023-04-28
Warranty Status: Out Of Warranty
Demo Unit: No
Locked Carrier: 10 - Unlock
Sim-Lock Status: Unlocked
```
→ **Apple · iPhone 14 · 256GB · Purple · Unlocked · IMEI 353340195540565** (only cost/condition/location left).

## 🖥️ Review-table changes (the "supreme" UX)
The review table (screen 2 from #52) gains **inline editing** and:
- **"Apply to all" bar** at the top for **cost / condition / location** — a paste is usually one lot, so set
  them **once → fills every row**, with per-row override. (The single biggest friendliness win.)
- **Inline-editable cells** — the empty shop fields are tappable/typable **right in the row** (not only a
  dialog). Desktop keeps its table styling; mobile its list rows.
- **Per-row / per-cell status colour** — parsed-from-SICKW (confident) · **must-fill** (cost/condition/location)
  · **problem** (see below). The eye goes straight to what's unfinished.
- **Live IMEI checks in the table** — flag inline if an IMEI is **already in stock** or **duplicated within the
  paste** (reuse `CheckImeiAvailabilityUseCase` + in-batch dedup); the confirm-time transaction remains the
  real guard.
- **Unknown attribute → auto-create (add-new-inline) but tagged "new"** so a typo'd value is visible, not
  silent.
- **Graceful "couldn't read"** — unreadable blocks are shown (with their raw text) to fix or drop; **never
  silently swallowed.**
- **Mixed SKUs already work** — each row carries its own SKU snapshot (from #52), so a paste of different
  models is fine.

## 🧯 Batch-size cap (folded in here, as agreed)
A Firestore **transaction is capped at ~500 writes**; each unit = 2 docs (+1 product) → **~150–200 units is the
safe ceiling**. A big paste can exceed it. **Cap the batch** (warn + ask to split past the ceiling) **or chunk**
the write into ≤500-write transactions with **graceful partial-success handling** ("42 of 60 added — retry
these"). No Cloud Function — the client transaction stays the mechanism.

## ✅ Scope
- [ ] **Shared `parseSickw` parser** in `sharedLogic` (pure, tolerant, type-based) + **`commonTest`** covering
      the fixture above, multi-phone paste, a locked-carrier phone, an unreadable block, and vocab normalization.
- [ ] **Paste entry** per platform (button next to Add product → multiline paste box → "Parse & add" → review),
      with "paste more" append.
- [ ] **Review-table inline editing** per platform: the **Apply-to-all bar**, inline cells, status colours, live
      IMEI checks, auto-create-and-tag unknown vocab, and the "couldn't read" list.
- [ ] **Batch-size cap / chunking** with graceful partial-success.
- [ ] Map parsed attributes to vocab via **find-or-create** (`AddAttributeUseCase`); i18n for all new strings.

## 🖼️ UI standards (Definition of Done)
- Match the **inventory/entities theme**; **light + dark**; native components per platform.
- Keyboard: numeric for cost; the paste box is a proper multiline field; keep the focused row visible.
- **Graceful errors everywhere** (the recurring ask): bad paste, partial parse, unknown vocab, duplicate/in-stock
  IMEIs, the batch cap, and confirm-time failures — all shown clearly, nothing silently dropped, batch preserved.
- Ellipsis on long model/color/location; states (parsing, empty, error); a11y (labels, dynamic type, targets,
  contrast); i18n; **`/kmp-arch`** (shared parser, native UI per platform).

## 🎯 Acceptance Criteria
- [ ] Pasting the **sample above** (and multiples of it) parses to **Apple · iPhone 14 · 256GB · Purple ·
      Unlocked · <IMEI>**, lands on the review table pre-filled, only cost/condition/location empty.
- [ ] **Apply-to-all** sets cost/condition/location for every row; per-row override works; cells are
      **inline-editable**; per-row status is visible.
- [ ] A **duplicate/in-stock IMEI** in the paste is flagged in its row; an **unreadable block** is shown, not
      dropped; **unknown attributes are created and tagged "new"**.
- [ ] Confirm writes via the existing **atomic** transaction; a batch over the safe size is **capped or chunked
      with graceful partial-success**; a race duplicate fails gracefully with nothing partial.
- [ ] The **parser is shared Kotlin** with passing `commonTest`; matches the inventory/entities theme; light +
      dark; i18n; `/kmp-arch`; builds + runs on Android, iOS, Desktop.

## 🚫 Out of scope
- **Direct SICKW API** integration (register/key/credits) — a later enhancement feeding the same parser.
- **Non-iPhone** reliability (Android/others stay manual); editing an existing SKU's attributes.
- Storing SICKW-only fields we have no home for (Serial/IMEI2/warranty/dates) — ignored for now.

## 🔗 Dependencies
- **#52 (merged)** — the Add-Inventory flow + review table this extends. M4 inventory models/use cases.
- **PO to confirm** the sample above is the SICKW service the customer uses (so the parser is pinned to one
  format); if they switch services, the parser format must be revisited.

## 📚 References
- Add-Inventory flow (#52): `*/ui/inventory/*`, `AddStockViewModel`, the review screen; `CheckImeiAvailabilityUseCase`.
- M4 inventory: `AddStockUseCase`, `AttributeType`, `NewUnit`/`NewProduct`. `/kmp-arch`. Design decision: memory `sickw-imei-paste-integration`.

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
