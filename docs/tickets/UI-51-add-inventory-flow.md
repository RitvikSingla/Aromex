# [UI] Add Inventory flow — batch entry + review-before-write (Android · iOS · Desktop)

> **Platforms: Android · iOS · Desktop.** **Presentation + flow** over the **M4** inventory logic — the
> models, `AddStockUseCase`, the race-safe transaction, and `DuplicateImeiException` are **reused as-is**
> (one thin shared addition, below). This **replaces the bare M4 add-stock test UI** with the real,
> polished Add-Inventory experience.
> ⚠️ **Follow `/kmp-arch`** — native UI per platform (Compose / SwiftUI / Compose-Desktop), nothing in
> `sharedUI`, no business logic in the UI. Milestone: **UI & design system**.

## 📖 Story / Why
Shops add phones **in batches** — a stack of units that share the same **cost, condition, and location**, differing only by IMEI. This builds the add-inventory flow around that reality: pick the phone once, enter the shared batch details once, **scan/type IMEIs quickly one after another**, then **review the whole batch before it's written**. The review step is a deliberate safety net (nothing hits the database until the user confirms), and it's where any per-unit exceptions get corrected.

## 🎨 The design
> **⚠️ Two things are provided by the PM at `/start-ticket` — ask for them BEFORE planning:**
> 1. **The searchable-dropdown reference code** — build the brand/model/capacity/color/carrier/location dropdowns from it. **Do not design a dropdown from scratch; do not plan until you have it.**
> 2. Match the **existing entities screens'** look, components, and behaviour (the styled design system now on `master`) — this flow must feel like the same app. No new Figma; reuse the entities theme.

**Screen 1 — Add inventory (a FULL SCREEN, not a bottom sheet)** — full-screen so back/close can guard against accidental data loss.
- **Top bar:** ✕ close (left) · "Add inventory" (title) · a **Review** action (right, disabled until valid).
- **Phone (the SKU):** **searchable dropdowns** — Brand → Model (filtered by the chosen brand) → Capacity → Color → Carrier — each with **add-new-inline** (type a value that doesn't exist → it's created and selected). Plus a **Selling price** field (per SKU; the batch's default sell price).
- **Batch details (entered ONCE, apply to every unit):** **Cost** (per unit), **Condition** (New / Used toggle), **Location** (searchable dropdown). *Deliberately not per-IMEI here* — that's the whole point of batch entry.
- **Add IMEIs:** an **IMEI field with a green ✓ button**. Tapping ✓ (or Done): validates the IMEI, then checks it is **not already in this batch's list** *and* **not already in stock**; on success it **adds the unit to the list and clears the field** (ready for the next); on failure it shows a **graceful inline error** ("already in this batch" / "already in stock" / "not a valid IMEI"). Numeric keypad; a live count ("**3 phones**").
- **The list:** each added IMEI as a row, individually **removable**.
- **Bottom / Review:** proceeds to Screen 2. Disabled until the SKU + all batch details are valid **and** at least one IMEI is added.

**Screen 2 — Review & confirm (before anything is written)** — the **complete list of phones about to be added**, each showing IMEI · cost · condition · location (pre-filled from the batch). On this screen the user can:
- **Edit any unit** — override *any* detail for a single phone (IMEI, cost, condition, location), for the odd unit that differs.
- **Delete** a unit, and **Add** more (returning to entry / an inline add row).
- **Confirm** → the **actual DB write** (`AddStockUseCase` → the race-safe transaction). Shows a saving state; on success returns to the inventory list; on failure (a duplicate slipped in via a race, or a network error) shows a **graceful error, writes nothing (atomic), and stays on the screen** so the user can fix and retry.
- **Desktop:** render this review as a **clean, readable table** (IMEI · Cost · Condition · Location · row actions), matching the entities desktop table styling.

## 🧩 Decisions baked in
- **Full-screen entry (not a sheet)** on mobile + **unsaved-changes guard** on back/close/Esc when the batch is non-empty. On desktop, entry can be a full screen or a large modal (dev's call — mirror the entities desktop pattern); the **review is a table**.
- **Batch-shared fields:** cost / condition / location captured once and applied to all units; **per-unit overrides happen only on the review screen.** (Maps straight onto M4's `AddStockUseCase(NewProduct, List<NewUnit>)` — no data-model change; the UI just builds the `NewUnit` list.)
- **The ✓ availability check is a fast, advisory pre-check** (reads the IMEI index) for instant feedback. **The confirm-time transaction is the real guarantee** — it's race-safe and throws `DuplicateImeiException`, so even if a duplicate slips between the pre-check and the write, the write catches it and the UI reports it cleanly. The pre-check must never be treated as the authority.
- **Currency** for cost/price uses the **`session.currency`** formatter (reuse the entities money formatter).
- **Theme + components:** reuse the entities design system (fields, buttons, chips, dialogs, table) — **do not invent new visual language.**

## ✅ Scope
- [ ] Build the **Add-inventory entry screen** and the **Review & confirm screen** on **Android (Compose)**, **iOS (SwiftUI)**, and **Desktop (Compose-Desktop)** — all states, **light + dark**, styled to match the entities screens.
- [ ] The **searchable dropdown** component per platform, built from the **PM-provided reference code**, with add-new-inline and model-filtered-by-brand.
- [ ] The **green-✓ IMEI add** with in-batch dedup (client-side) + in-stock availability pre-check + graceful inline errors + numeric input.
- [ ] The **review screen** with per-unit edit / delete / add; **desktop = a clean table**.
- [ ] Wire to **M4**: `AddStockUseCase`, `AddAttributeUseCase` (add-new-inline), the money formatter; surface `DuplicateImeiException` and save failures gracefully (atomic — nothing partial).
- [ ] **Thin shared addition:** an **IMEI availability pre-check** — a repo method (reads `imeiIndex/{imei}`) + a small use case — for the ✓ feedback. Add to `sharedLogic` per `/kmp-arch` (interface + impls per platform); it does **not** replace the transactional guard.
- [ ] **i18n** for all new strings.

## 🖼️ UI standards (Definition of Done)
Native UI per platform; reuse the entities components (the full-screen-over-sheet choice is deliberate — to guard the back gesture). Also:
- **Light + dark** on every screen/state.
- **Edge-to-edge + safe areas** (mobile) — content clears status bar / notch / Android nav bar; the keyboard never covers the focused IMEI/cost/price field. **Desktop:** resizable, reflowing, keyboard nav (Tab / Enter / Esc), hover/focus states, the review table scrolls cleanly.
- **Keyboard:** numeric pad for IMEI / cost / price; the ✓/Done action adds the IMEI and keeps focus for the next; correct Next/Done elsewhere.
- **Graceful errors everywhere** (the explicit ask): invalid IMEI, in-batch duplicate, in-stock duplicate, attribute-add failure, network/save failure, and the race-time `DuplicateImeiException` — all shown inline/clearly, never a crash or a silent no-op; a failed save leaves the batch intact to retry.
- **Correct ellipsis** on long model names / locations / IMEIs (list rows + table cells).
- **States:** empty (no IMEIs yet), the ✓ checking / valid / error states, saving, and the review edit dialogs — all built.
- **Accessibility:** labels/content-descriptions (incl. the ✓ and remove buttons); dynamic type / OS text-scaling; touch targets ≥ 48dp / 44pt; WCAG-AA contrast. **Strings** via i18n. **`/kmp-arch`** throughout.

## 🎯 Acceptance Criteria
- [ ] Add-inventory is a **full screen** (mobile) with a working **unsaved-changes guard**; entry uses **searchable dropdowns** (brand/model/capacity/color/carrier/location) with add-new-inline and model-by-brand.
- [ ] **Cost / condition / location are entered once** and apply to every IMEI; the **✓** adds a unit only after passing **in-batch** and **in-stock** checks, with graceful inline errors; the list shows a live count and per-row remove.
- [ ] **Review screen** shows every phone, allows **per-unit edit / delete / add**, and **desktop renders it as a clean table**; nothing is written until **Confirm**.
- [ ] **Confirm** writes via M4's transaction; a duplicate/race or network failure is shown **gracefully with nothing written (atomic)** and the batch preserved for retry.
- [ ] Matches the **entities theme** (components, light + dark); money via `session.currency`; all strings i18n; `/kmp-arch` (native UI per platform, no `sharedUI`, no logic in UI).
- [ ] No secrets; builds + runs on an Android emulator, an iOS simulator, and the desktop app.

## 🚫 Out of scope
- The **inventory list + detail real UI** (browse side) — a separate ticket; this ticket owns the **add** flow only (reachable from the existing inventory section).
- **Home/dashboard**, **Purchase/Sales**, the cross-device scan hand-off, and any HL/valuation.
- Editing an existing SKU's attributes (immutable by design) — only add-new-inline of vocabulary here.

## 🔗 Dependencies
- Builds on merged **M4** (#47–#50: models, `AddStockUseCase`, transaction, rules). No backend changes beyond the thin IMEI-availability pre-check.
- **PM provides** the searchable-dropdown reference code + confirms the entities theme is the reference — **at `/start-ticket`, before planning.**

## 📚 References
- **Entities screens** (the theme + component reference): `androidApp/.../ui/entities`, `iosApp/.../ui` (Entities/Detail/Form views), `desktopApp/.../ui/entities`. `docs/brand-kit.md`.
- **M4 inventory:** `sharedLogic/.../usecase/AddStockUseCase.kt`, `AttributeType`, `NewUnit`/`NewProduct`, `InventoryRepository`; the bare screens being replaced: `*/ui/inventory/*`.
- `/kmp-arch`. *(Optionally split mobile/desktop into two tickets during `/start-ticket` planning if cleaner.)*

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
