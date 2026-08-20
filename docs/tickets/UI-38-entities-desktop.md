# [UI] Entities — real UI for Desktop (list + Add/Edit modal + detail)

> **Platform: Desktop only** (Compose-Desktop / JVM). The sibling of the mobile entities UI ticket — do it
> after or alongside that one. **Presentation-only restyle** over the M3 desktop entities feature — ViewModels,
> use cases, repos, and the HL dual-write are **untouched**.
> ⚠️ **Follow `/kmp-arch` (Desktop section)** — native Compose-Desktop UI, nothing in `sharedUI`, no logic in UI.
> Milestone: **UI & design system**.

## 📖 Story / Why
M3 shipped the desktop entities feature with **bare/test UI**. This applies the **approved Aromex desktop
design** to the entities screens — **light + dark** — matching the mobile ticket's look, adapted to desktop
conventions (a **modal dialog** for Add/Edit instead of a full screen; a **table** for the list).

## 🎨 The design
> **⚠️ Design assets are provided by the PM.** The desktop screenshots (Add/Edit **modal**, Entity **List**,
> Entity **Detail**) and Figma are the authoritative reference and are **NOT attached to this issue** — **ask
> the PM at `/start-ticket`.** *(Note: the Entity-Detail desktop screen wasn't attached to the chat due to an
> image limit — the PM will provide it at start.)* Build against those + `docs/brand-kit.md` + the mobile
> ticket's decisions.

**Screen ① Add / Edit Entity — a CENTERED MODAL DIALOG** over the dimmed list (not a full window). ~520 px
wide, scrolls if tall.
- Title bar: "New entity" / "Edit entity" + an **✕**.
- Same form + sections as mobile (Identity · Contact · Classification · Notes · Financial), laid out for
  desktop (e.g. Email + Address side-by-side); **"+ Add"** for extra phone numbers; role chips; **"+ Add
  opening balance"** expander.
- Footer: **Cancel** + **Save entity** (bottom-right; Save disabled until valid, "Saving…" while posting).
- **States (build all):** empty · filled · saving · **"Unsaved changes"** dialog on close/Esc (Keep editing /
  Discard) — **light + dark**.

**Screen ② Entity List** — inside the app shell (top bar: AROMEX + nav tabs + a **"＋ New entity"** button).
Title "Entities" + count subtitle; **search** field + **role filter chips** (All / Customers / Suppliers /
Middlemen); a **table** with columns **Name · Balance (color-coded) · Phone · [View]**. **States:** loaded ·
empty · loading · error — **light + dark**.

**Screen ③ Entity Detail** — the desktop equivalent of the mobile detail (name + role chip, prominent
color-coded net balance, contact, notes, "Transaction history — Coming soon", Edit / Archive / Delete;
Walk-in non-editable). Follow the PM's desktop design.

## 🧩 Decisions baked in (same as mobile)
- **Add/Edit is a modal dialog** (not a full window) + **unsaved-changes guard** on ✕ / Esc / Cancel when dirty.
- **Currency symbol is dynamic** from `session.currency` (reuse the money formatter from the mobile ticket / add
  it if landing first). Never hardcode a symbol.
- **Opening balance:** editable on **Add**; **read-only on Edit** — the editable "Adjust balance" (owner's-
  drawings adjustment posting) is the **deferred Transactions feature**. *(Confirm treatment with the PM.)*
- **Roles = multi-select labels**; **phone = plain field** (dial-code as placeholder hint; picker deferred).
- **App shell / other nav tabs** (Dashboard / Sales / Inventory / Reports) are **context only** — build the
  **Entities** area; the other tabs stay placeholder/non-functional (Home/dashboard is designed last).

## ✅ Scope
- [ ] Extend the desktop design system for these screens (reuse the desktop login tokens/components; add:
      role/filter chips, table rows with color-coded balance, the amount field + toggle, the **modal-dialog**
      scaffold + unsaved-changes dialog, empty/loading/error states).
- [ ] Rebuild **List · Add/Edit (modal) · Detail** in **Compose-Desktop** to match the design — all states,
      light + dark — **wired to the existing M3 desktop ViewModels / state** (no logic changes).
- [ ] Use the shared **money formatter** (`session.currency`); add **i18n strings** for UI labels.

## 🖼️ UI standards (Definition of Done — Compose-Desktop)
- **Light + dark** on every screen/state.
- **Resizable + responsive:** the window resizes; the list/table and the centered form **reflow** with no
  clipping; sensible minimum size (reuse the login window conventions).
- **Keyboard + pointer (desktop-native):** **Tab** moves through fields in order, **Enter** submits when valid,
  **Esc** closes the dialog (via the unsaved-changes guard); visible **focus rings**; **hover** states on rows,
  buttons, chips, links with a **pointer cursor**.
- **Correct ellipsis** on long names/emails/notes in table cells and the form.
- **States** loading / empty / error / disabled built; controls disabled while saving.
- **Accessibility:** labels; respect OS text scaling; WCAG-AA contrast (incl. green/red balances).
- **Strings** via i18n; **`/kmp-arch`** (native Compose-Desktop, no `sharedUI`, no logic in UI).

## 🎯 Acceptance Criteria
- [ ] List (table) · Add/Edit (**modal dialog**) · Detail match the design, across **all states** and
      **light + dark**.
- [ ] The Add/Edit **modal** + **unsaved-changes guard** (✕ / Esc / Cancel) work; the list table shows
      color-coded balances + View; Detail shows the read-only balance + "coming soon" history; Walk-in non-editable.
- [ ] Money via the **`session.currency`** formatter (dynamic symbol, decimal strings — no float).
- [ ] **Opening balance editable on Add, read-only on Edit**; existing M3 desktop flow/logic **unchanged** and
      still works (add → PENDING → SYNCED, balance, archive, Walk-in protected).
- [ ] Resizable window reflows cleanly; keyboard nav (Tab/Enter/Esc) + hover/focus work.
- [ ] Strictly `/kmp-arch`; no secrets; builds + runs the desktop app.

## 🚫 Out of scope
- **"Adjust balance" / balance editing** (Transactions milestone); **country-code picker + company dial-code**;
  **transaction history** (placeholder); **Home/dashboard** + the other nav tabs' content; **Android / iOS**
  (the sibling ticket).

## 🔗 Dependencies
- Builds on merged **M3** (#30–#35). Pairs with the **mobile entities UI ticket** (shares the money formatter +
  i18n strings — coordinate so they aren't duplicated).

## 📚 References
- `docs/brand-kit.md`; **PM-provided desktop designs (ask at `/start-ticket`)**. Existing bare screens:
  `desktopApp/.../ui/entities` (+ ViewModels). The desktop **login** work is the closest reference for the
  window/dialog + tokens. `/kmp-arch` (Desktop section).

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
