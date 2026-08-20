# [UI] Entities — real UI for Android + iOS (Add / List / Detail)

> **Platforms: Android + iOS** (Desktop is the next ticket). **Presentation-only restyle** over the M3
> entities feature — the ViewModels, use cases, repos, and the HL dual-write are **untouched**; you're
> re-rendering the same state with the finished visuals.
> ⚠️ **Follow `/kmp-arch`** — native UI per platform (Compose / SwiftUI), nothing in `sharedUI`, no logic in UI.
> Milestone: **UI & design system**.

## 📖 Story / Why
M3 shipped Profiles/Entities with **bare/test UI**. This applies the **approved Aromex design** to the three
entities screens on **Android + iOS** — all states, **light + dark** — reusing the design system established by
the login work. It's the first "real" feature screen a client uses day-to-day, so it needs to look finished.

## 🎨 The design
> **⚠️ Design assets are provided by the PM.** The **per-screen screenshots (Add / List / Detail, every state,
> light + dark)** and the Figma are the authoritative reference and are **NOT attached to this issue**. **At
> `/start-ticket`, ask the PM for them** and build against those + `docs/brand-kit.md`.

**Screen ① Add / Edit Entity — a FULL SCREEN (deliberately NOT a bottom sheet)**, so back/close can guard
against accidental data loss (a sheet can't be blocked with a warning).
- **Top bar:** ✕ close (left) · "New entity" / "Edit entity" (center) · **Save** (right, disabled until valid).
- **Form (sections):** `IDENTITY` → Full name * (required) · `CONTACT` → Phone (with a dial-code placeholder
  hint) + **"+ Add another number"** (repeatable rows, each removable) · Email · Address · `CLASSIFICATION` →
  **Customer / Supplier / Middleman** multi-select chips + helper *"Just labels — you can buy from and sell to
  anyone."* · `NOTES` (multi-line) · `FINANCIAL` → **Opening balance** (amount with the currency symbol + a
  **"They owe me / I owe them"** toggle) — **Add only, see decisions**.
- **Bottom:** full-width **"Save entity"** (disabled / enabled / **"Saving…"** spinner).
- **States (build all):** empty · filled · **validation error** (e.g. "Name is required", "Enter a valid email
  address" — red label/border/message) · saving · **"Unsaved changes"** dialog (Discard changes / Keep editing).

**Screen ② Entity List** — title "Entities" / subtitle "Customers & Suppliers"; a **search** field; **role
filter chips** (All / Customers / Suppliers / Middlemen); rows = name + role chip(s) + **net balance,
right-aligned and color-coded** (green = they owe you, red = you owe them, grey = settled); a **"+"** button
(header + FAB) → Add. **States:** loaded · **empty** ("No entities yet" + "Add first entity") · loading · error
("Couldn't load entities" + Retry).

**Screen ③ Entity Detail** — name header + role chip; **net balance prominent + color-coded** with a
"You owe them / They owe you" label; contact rows (phone · email · address); notes; a **"Transaction history —
Coming soon"** placeholder; **Edit** (top-right) + **Archive** + **Delete** actions. The reserved **Walk-in**
entity shows with Edit/Archive/Delete **disabled**.

## 🧩 Decisions baked in (read these)
- **Full-screen Add** (not a sheet) + **unsaved-changes guard** on back/close when the form is dirty.
- **Currency symbol is dynamic** — it comes from `session.currency` (the `₱` in the mock is just the sample
  company). Add a **small money formatter** (currency code → symbol + format the decimal string) used
  everywhere money shows. Never hardcode a symbol.
- **Opening balance:** editable on **Add** (create-time; already posts to HL via M3/T2). **On Edit it is
  READ-ONLY / display-only** — editing/adjusting a balance posts an accounting adjustment, which is the
  **deferred "Adjust balance" feature** (Transactions milestone). *(Design shows it editable on Edit; render it
  read-only for now — confirm treatment with the PM.)*
- **Roles are multi-select labels** — they never constrain anything.
- **Phone is a plain text field** (dial-code shown only as a placeholder hint). A real country-code picker +
  company default dial-code is a **separate deferred enhancement** — out of scope here.

## ✅ Scope
- [ ] Extend the design system for these screens (reuse the login components; add what's needed: role/filter
      **chips**, **balance display** (color + sign), **amount field** with currency prefix + segmented toggle,
      section headers, list rows, **FAB**, empty/loading/error states, the full-screen form scaffold, and the
      **unsaved-changes dialog**).
- [ ] Rebuild **Add / List / Detail** on **Android (Compose)** and **iOS (SwiftUI)** to match the design — all
      states, light + dark — **wired to the existing M3 ViewModels / state** (no logic changes).
- [ ] Add the **money formatter** (`session.currency` → symbol + format).
- [ ] New **i18n strings** for the UI labels (no hardcoded user-facing text).

## 🖼️ UI standards (Definition of Done)
Native UI per platform (Compose / SwiftUI); prefer native components — **the full-screen-over-sheet choice is a
deliberate, documented exception** (to guard the back gesture). Also:
- **Light + dark** on every screen/state (theme tokens; nothing hardcoded that breaks in dark).
- **Edge-to-edge + safe areas** — content clears the status bar / notch and the **Android nav / gesture bar**;
  the keyboard never covers the focused field.
- **Keyboard:** **numeric pad** for the opening-balance amount and phone; correct Next/Done IME actions
  (Next advances fields, Done submits); keep the focused field visible; tap-outside dismisses.
- **Correct ellipsis** on long names / emails / notes / balances — never clip or break layout.
- **States:** loading / empty / error / disabled all built (per the design); controls disabled while saving.
- **Accessibility:** labels/content-descriptions; respect dynamic type / font scaling; touch targets ≥ 48dp /
  44pt; WCAG-AA contrast (incl. the green/red balances).
- **Strings** via i18n; **`/kmp-arch`** (native UI, no `sharedUI`, no logic in UI).

## 🎯 Acceptance Criteria
- [ ] Add / List / Detail match the design on **both Android and iOS**, across **all states** and **light + dark**.
- [ ] **Add is a full screen** with a working **unsaved-changes guard**; List has FAB/empty/loading/error;
      Detail shows the read-only balance, contact, "coming soon" history, and Walk-in is non-editable.
- [ ] Money renders via the **`session.currency`** formatter (dynamic symbol, decimal strings — no float);
      balances color-coded green/red/grey.
- [ ] **Opening balance editable on Add, read-only on Edit**; the existing M3 flow/logic is **unchanged** and
      still works (add → PENDING → SYNCED, balance shows, archive, Walk-in protected).
- [ ] Strictly `/kmp-arch`; no secrets; builds + runs on an Android emulator and an iOS simulator.

## 🚫 Out of scope
- **"Adjust balance" / editing a balance** (posts an owner's-drawings adjustment) — **Transactions milestone**.
- **Country-code picker + company default dial-code** — separate enhancement.
- **Transaction history** (placeholder only), **Home/dashboard** (the "Add entity" entry-point lives there,
  designed last), and **Desktop** (the next ticket).

## 🔗 Dependencies
- Builds on merged **M3** (#30–#35). Coordinate with **#34 (iOS SKIE)** — ideally it lands first so the iOS
  list path is unified, but this restyle works on the current structure.

## 📚 References
- `docs/brand-kit.md`; the **PM-provided per-screen designs (ask at `/start-ticket`)**. Existing bare screens:
  Android `androidApp/.../ui/entities`, iOS `iosApp/iosApp/ui/EntitiesView.swift` + `EntityDetailView.swift` +
  `EntityFormView.swift` (+ their ViewModels). `/kmp-arch` (Desktop is the sibling ticket).

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
