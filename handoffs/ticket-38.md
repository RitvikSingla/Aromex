# Ticket #38 — [UI] Entities — real UI for Desktop (list + Add/Edit modal + detail)

## Summary

Replaces the M3 bare-test Entities screen on Desktop with the PM-approved three-panel Compose-Desktop shell: a hover-driven collapsible nav sidebar (72dp collapsed ↔ 280dp expanded), a fixed 320dp contact-list panel with search + role filter chips + receivable/payable stat cards, and a right-hand detail panel with a blue gradient header showing name/avatar/balance. Add/Edit is implemented as a centered modal dialog (max 660dp wide) overlaid on a scrim, matching the PM's designs. All input rules are enforced: name is auto title-cased with no leading spaces, phone is digits-only capped at 10, email is auto-lowercased with no spaces, address and notes auto-capitalise their first character; field-level error display only. An unsaved-changes guard fires on ✕ / Esc / Cancel when the form is dirty. The `EntitiesScreen` is now the authenticated app shell — `AromexApp.kt` routes directly to it after login. ViewModels, use cases, and repositories are untouched.

---

## Files changed

### Shared logic
| File | Why |
|---|---|
| `sharedLogic/.../i18n/Strings.kt` | 26 new string keys for the desktop shell: top-bar button, stat card labels, sidebar nav labels, detail-panel field labels, sign-out, `entity_form_save_contact` |
| `sharedLogic/.../i18n/EnglishStrings.kt` | Translations for all 26 new keys; email placeholder updated to `"user@example.com"` |

### Desktop
| File | Why |
|---|---|
| `desktopApp/.../ui/components/Avatar.kt` *(new)* | Initials circle — hashes name into one of 6 palette colours; handles 2-word, 1-word, and empty names |
| `desktopApp/.../ui/components/AromexDialog.kt` *(new)* | Themed `AlertDialog` wrapper for confirmations; `destructive` flag switches confirm colour to `colors.error` |
| `desktopApp/.../ui/components/CountryPickerDialog.kt` *(new)* | Searchable country-picker (`Dialog` + `LazyColumn`); flag emoji replaced with bold ISO code (`CA`, `US`) since JVM cannot render Unicode regional indicator characters |
| `desktopApp/.../ui/entities/EntitiesScreen.kt` *(full rewrite + follow-up fixes)* | Complete three-panel shell + `EntityFormDialog` modal; all edge cases, input rules, and UX polish below |
| `desktopApp/.../navigation/AromexApp.kt` | Removes `showEntities` bool and `HomeScreen` from auth route; routes directly to `EntitiesScreen`; passes `onSignOut = home::signOut` |

---

## Detail of follow-up fixes (post-initial commit)

| Fix | What changed |
|---|---|
| Top-bar search removed | The global search `BasicTextField` in `EntitiesTopBar` was removed; breadcrumb takes `weight(1f)` to fill space. Contact filtering lives only in the list-panel "Filter…" field. |
| Role chips horizontally scrollable | Wrapped chips `Row` in `horizontalScroll(rememberScrollState())` — labels no longer wrap or clip at narrow list-panel widths. |
| Middlemen chip removed | `EntitiesFilter.MIDDLEMAN` entry removed from the chips list (role being retired from the backend). |
| Flag emoji → ISO code | JVM cannot render Unicode flag emoji; both `PhoneFormField` prefix and `CountryPickerDialog` rows now show bold ISO codes (`CA`, `US`). `isoToFlag` / `isoToFlagEmoji` helpers removed. |
| Duplicate gray "NOTES" label removed | The `Text`+`Spacer` inside the notes `Column` duplicated the blue `FormSectionHeader`; removed. |
| Address + Notes auto-cap first char | `autoCapFirstChar()` extension added; applied in `onValueChange` for both fields. |
| Opening balance direction colours | "To Receive" chip → green (`colors.success`); "To Give" chip → red (`colors.error`). Active background is `chipColor.copy(alpha = 0.12f)`. |
| Email placeholder | Changed from `"Email address"` to `"user@example.com"` for clarity. |

---

## How to test

1. `./gradlew :desktopApp:run`
2. Sign in with a valid account → lands directly on Entities shell (no HomeScreen step).
3. **Sidebar:** hover → expands to 280dp with labels; move to content → collapses to 72dp. Hamburger (☰) in top bar forces expand.
4. **List panel:** contacts load; "Filter…" search filters by name; **All / Customers / Suppliers** chips are horizontally scrollable; RECEIVABLE (green) and PAYABLE (red) totals sum correctly.
5. **Top bar:** breadcrumb shows `POS › Contacts`; "+ New Contact" button is right-aligned next to the bell and avatar.
6. **Detail panel:** select a contact → blue gradient header with name, avatar, balance pill, contact fields; walk-in shows no Edit/Archive.
7. **Add dialog:** "+ New Contact" → modal appears, scrim dims background.
   - Name: leading space blocked; words auto title-case.
   - Phone: digits only, max 10; country prefix shows bold ISO code (e.g. `CA +1`) — tap to open picker.
   - Country picker: bold ISO codes visible in every row; search by name/code works.
   - Email: auto-lowercased, no spaces; placeholder shows `user@example.com`.
   - Address: first character auto-capitalised.
   - Notes: first character auto-capitalised; no duplicate gray "NOTES" label above the field.
   - Opening balance: **To Receive** lights up green when selected; **To Give** lights up red.
8. **Unsaved-changes guard:** modify any field → ✕ / Cancel / Esc → "Discard changes?" dialog.
9. **Save:** fill name → Save Contact enabled; success → dialog closes, contact appears.
10. **Archive:** Archive button in detail header → confirmation dialog → contact removed.
11. **Sign out:** sidebar bottom avatar or top-bar avatar → confirmation → returns to Login.
12. **Dark mode:** toggle system dark mode — all surfaces adapt; green/red balances remain legible.

---

## Acceptance criteria

| Criterion | Status |
|---|---|
| List · Add/Edit modal · Detail match design across all states and light + dark | ✅ Met |
| Add/Edit modal + unsaved-changes guard (✕ / Esc / Cancel) work | ✅ Met |
| Walk-in non-editable; read-only balance; "coming soon" transaction placeholder | ✅ Met |
| Money via `session.currency` formatter; decimal strings, no float | ✅ Met |
| Opening balance editable on Add, read-only on Edit; M3 flow unchanged | ✅ Met |
| Resizable window; keyboard nav (Tab / Enter / Esc); hover/focus states | ✅ Met |
| Strictly `/kmp-arch`; no secrets; builds + runs | ✅ Met |

---

## Deviations / decisions

| Item | Decision |
|---|---|
| **Contact list layout** | Ticket describes a "table". PM's design screenshots show contact cards (avatar + name + balance). Cards implemented to match actual design assets. |
| **Country-code picker** | Listed as out of scope in ticket. PM's design and user requirement at `/start-ticket` included it. Implemented as `CountryPickerDialog`. |
| **Flag emoji → ISO code** | JVM (Desktop) cannot render Unicode regional indicator flag characters. Bold ISO code (`CA`, `US`) used as a legible, reliable fallback. |
| **Hover-driven sidebar** | Not in original ticket scope. PM provided the exact spec at `/start-ticket`; implemented precisely. |
| **`HomeScreen` removed from auth route** | `EntitiesScreen` is now the app shell. `HomeViewModel` retained for its sign-out observer. |
| **`Button` instead of `PrimaryButton` for Save/New Contact** | `PrimaryButton` hardcodes `fillMaxWidth()`; using `Button` directly allows natural width in the footer row and top bar. |
| **Notes uses `OutlinedTextField` directly** | `LabeledTextField` hardcodes `singleLine = true`; notes needs `minLines = 3`. |
| **Sidebar background `#1B2B48`** | Fixed for both themes — `headerGradientEnd` in dark (`#12151C`) would blend into the page background with no visible separation. |
| **Middlemen chip removed** | Role being retired from the backend; chip removed from the filter carousel. |

---

## Open questions / follow-ups

- **Transactions tab:** placeholder only — implementing it is a future ticket.
- **`HomeScreen` / `HomeViewModel` cleanup:** files still exist but are no longer rendered. Delete when team confirms no other path uses them.
- **Global search in top bar:** removed per user request; all filtering goes through the list-panel "Filter…" field. If a broader cross-feature search is needed later, add it as a separate ticket.
- **Sidebar active state:** "Contacts" nav item is hardcoded `active = true`. Should derive from route when other tabs get real destinations.
