> **Platform: Desktop only** (Compose-Desktop). Read-only **browse** view over the M4 inventory the shop
> already has in stock. **Replaces the current minimal/test inventory list** on desktop with a professional
> two-pane browsing screen. Milestone: **M4 — Inventory + Scanner**.
> ⚠️ **Follow `/kmp-arch`** — native Compose-Desktop UI, no business logic in the UI, reuse `sharedLogic`
> reads as-is. Mobile is explicitly out of scope for now.

## 📖 Story / Why
The shop owner needs to **see all their stock, cleanly, at a glance** — not the bare test list we have now.
This is a **two-pane browsing screen**: a **location panel on the left** (every location they have, plus an
**"All"** option) that **filters** a **professional inventory table on the right** (the majority of the screen).
The table **separates brands cleanly, groups models under them, and lists their in-stock phone units** in the
same polished table style we already built for the Add-Inventory **review & confirm** screen. It's a
**read-only view** — browsing, not editing.

## 🎨 The design (desktop, two panes)

### Left — Location panel
- A vertical list of **all locations**, each selectable, with an **"All"** entry at the top (selected by
  default). Selecting a location **filters the table on the right** to units in that location; **"All"** shows
  everything.
- The selected entry is highlighted (reuse the nav-sidebar/selected-item styling from `DesktopNavSidebar`).
- Show a **count** next to each location (number of in-stock units there) — and on "All" (total).
- Locations come from the managed **`LOCATION` attribute vocabulary** (same source the add-flow's location
  dropdown loads) — list **all** managed locations so an empty location still appears; counts derived from the
  in-stock serials. *If that vocab read isn't readily available to this screen, fall back to deriving the
  location list from the in-stock serials' `location` refs.* Fixed panel width, its own scroll if long.

### Right — Inventory table (majority of the screen)
A single scrollable, **professional grouped table**, in this hierarchy:
- **Brand section** header (e.g. *Apple*).
- **Model sub-group** header under it (e.g. *iPhone 14 — 6 units*), with a per-group unit count.
- **Unit rows** beneath each model — one row per in-stock phone. Columns (read-only), reusing the review
  table's cell styling:
  **Capacity · Color · Carrier · IMEI · Condition · Cost · Location · Sell price · Status**
  - These map 1:1 onto the review header's existing columns (which already includes **Sell price**) and its
    column weights — reuse them; **Status** is the one browse-specific addition.
  - The **Location** column is shown under **"All"**; when a specific location is selected it's redundant and
    may be hidden.
  - **Condition** uses the same New/Used cell style (read-only); **Cost** uses the money-cell style with the
    `session.currency` formatter; **Status** is the in-stock indicator.
- **In-stock units only** (sold/archived excluded — matches `observeInStockSerials()`).
- A **top search field** (reuse the VM's existing `query`) filtering the table by IMEI / model — optional but
  low-cost since it's already wired.

## 🔁 Reuse (the core ask — don't rebuild the table)
- The pretty review table already exists but is **`private` and coupled to the editable AddStock flow**:
  `ReviewTableHeader`, `ReviewRow`, `SkuCell`, `MoneyCell`, `TableCell`, `ConditionCell` in
  `desktopApp/.../ui/inventory/InventoryScreen.kt`. **Extract the visual table into a shared, read-only
  table component** (header + row + cells) that **both** the review screen and this browse screen use — one
  source of truth for the "pretty table." **Do not copy-paste** and do not fork the styling.
  - The review flow keeps its **editable** cells; browse consumes a **read-only** variant of the same cells
    (same look, no inputs). Refactor the cells to support a read-only mode rather than duplicating them.
- Everything else follows the **existing desktop theme** (colors, type, spacing, cards/panels, the sidebar
  selected-state) — reuse the entities/inventory design system; invent no new visual language.

## 🔌 Data (all reads already exist — `/kmp-arch`)
- **`ObserveInventoryUseCase`** streams `products` + in-stock `serials`; `InventoryListViewModel` already
  **groups serials by SKU** (`SkuRow`) and holds session/permissions/`query`. Extend the VM (or a browse VM
  in the same style) to also **group by Brand → Model** and **filter by selected location**
  (`Serial.location`).
- **Location** is **per-unit** (`Serial.location: AttributeRef`) — a single SKU can have units in several
  locations, so filtering by location filters **units**, and a model group shows only the units in the
  selected location. **There is no by-location read path** — filter **client-side** in the VM
  (`serials.filter { it.location… == selectedLocationId }`); **no new repository method or backend change**.
- SKU identity (brand/model/capacity/color/carrier) is on **`Product.attributes`**; resolve attribute refs to
  display names the same way the existing screens do.

## ✅ Scope
- [ ] **Desktop two-pane browse screen** replacing the current minimal inventory list.
- [ ] **Left location panel**: "All" + all managed locations, selectable, selected-state highlight, per-location
      in-stock counts, filters the table.
- [ ] **Right grouped table**: Brand → Model → unit rows, in-stock only, columns as above, with group counts.
- [ ] **Extract the review table into a shared read-only table component** and have both screens use it.
- [ ] VM: group by Brand → Model, filter by selected location, reuse existing `query` search; light + dark.
- [ ] **i18n** for all new strings; money via `session.currency`.

## 🖼️ UI standards (Definition of Done)
- Reuse the existing desktop design system; **light + dark** on every state.
- **Professional table**: aligned columns, clear brand/model separators, readable row density; long values
  (model / color / location / IMEI) **truncate with an ellipsis**; the table area scrolls cleanly (sticky
  brand/model headers a plus, not required); the left panel scrolls independently.
- **Resizable window / reflow**; keyboard nav (Tab / arrows through the location list); hover/focus states
  consistent with the app.
- **States:** loading, **empty** (no inventory at all), **empty-for-location** (this location has no stock),
  error, and **no-access** (respect `session.permissions.inventory` — same gate the current VM uses). All
  shown gracefully, never a crash or blank.
- **Accessibility:** labels on the location entries and table, OS text-scaling, WCAG-AA contrast. `/kmp-arch`
  throughout (native Compose-Desktop UI, no logic in the UI, reads via the use case).

## 🎯 Acceptance Criteria
- [ ] Desktop shows a **left location panel** ("All" + every location with counts) that **filters** a
      right-hand table; "All" shows all in-stock units.
- [ ] The table **groups Brand → Model → units** with per-group counts, in-stock only, columns
      Capacity · Color · Carrier · IMEI · Condition · Cost · Location · Status (selling price optional).
- [ ] The table is rendered by a **shared read-only table component extracted from the review screen** — the
      review screen still works and now uses the same component (no duplicated styling).
- [ ] Matches the desktop theme (light + dark), money via `session.currency`, strings i18n; empty / empty-for-
      location / error / no-access states all handled; `/kmp-arch`; builds + runs on the desktop app.

## 🚫 Out of scope
- **Editing / archiving** units or SKUs from this screen (update/archive plumbing exists already — a later
  ticket); this ticket is **read-only browse**.
- **Mobile (Android / iOS)** browse — a separate ticket.
- A per-unit **detail** screen, valuation/HL, and anything on the **add** side (owned by #52 / #53).

## 🔗 Dependencies
- Builds on merged **M4** (models, `ObserveInventoryUseCase`, `InventoryListViewModel`) and the **#52**
  Add-Inventory review table being extracted for reuse.

## 📚 References
- Reuse target (the pretty table): `desktopApp/.../ui/inventory/InventoryScreen.kt` — `ReviewTableHeader`,
  `ReviewRow`, `SkuCell`, `MoneyCell`, `TableCell`, `ConditionCell`.
- Current minimal list being replaced: `desktopApp/.../ui/inventory/InventoryListViewModel.kt` (`SkuRow`,
  `InventoryListUiState`), the list part of `InventoryScreen.kt`.
- Reads: `sharedLogic/.../usecase/ObserveInventoryUseCase.kt`, `repository/InventoryRepository.kt`
  (`observeProducts`, `observeInStockSerials`); models `Product` (`attributes`, `defaultSellingPrice`),
  `Serial` (`location`, `condition`, `cost`, `status`, `imei`); `AttributeType.LOCATION`.
- Theme / sidebar: `desktopApp/.../ui/components/DesktopNavSidebar.kt`, the entities/inventory design system.
- `/kmp-arch`.

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
