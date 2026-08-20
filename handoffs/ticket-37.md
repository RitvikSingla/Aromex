# Handoff — Ticket #37

**Ticket:** #37 — [UI] Entities — real UI for Android + iOS (Add / List / Detail)

## Summary
Applied the approved Aromex design to the three Entities screens (List / Detail / Add-Edit) on **Android (Compose)** and **iOS (SwiftUI)**, in light + dark, across all states. This is a **presentation-only** restyle: the M3 ViewModels, use cases, repositories, and the HL dual-write are untouched — the same `EntitiesUiState` / iOS `@Published` state is re-rendered with finished visuals. Adds a shared money formatter (`session.currency` → symbol + decimal-string formatting, never a float), a shared searchable country-code picker (default Canada), all new user-facing text via the i18n dictionary, NavHost / NavigationStack navigation (List → Detail / Add / Edit as real destinations, config-change safe), pull-to-refresh with a connectivity-gated "You're offline" dialog, themed dialogs (unsaved-changes / archive / offline), fixed brand headers, and correct keyboard handling (pinned Save bar + Next/Done).

> **Note:** this branch supersedes the earlier merged #37 UI (PR #39). Per direction, this PR's files are taken as the source of truth for the Entities UI.

## Files changed

### Shared logic (`sharedLogic/`)
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt` — new keys + English values for every List/Detail/Add-Edit label, the country picker, the offline dialog, the unsaved-changes guard, and the keyboard Next/Done buttons. No hardcoded user-facing text.
- `util/MoneyFormat.kt` — currency **code → symbol** (USD `$`, PHP `₱`, …) and signed/unsigned formatting of decimal **strings** (no Double/Float). Single source for money symbols; the symbol always comes from `session.currency`.
- `util/Country.kt` — `Country` model + `Countries` list (Canada default, `byIso` / `byDialPrefix`), shared so both platforms render the same searchable dial-code list.

### Android (`androidApp/`)
- `ui/entities/EntitiesScreen.kt` — the whole feature: a NavHost graph (`list`, `detail/{id}`, `add`, `edit/{id}`) with List / Detail / Add-Edit screens, fixed brand headers, translucent balance/contact hero on Detail, role cards, searchable country picker, pull-to-refresh + offline gating, `rememberSaveable` form, red-border validation, pinned Save bar, keyboard Next/Done.
- `navigation/AromexApp.kt`, `navigation/Route.kt`, `navigation/AppStateViewModel.kt` — app routing made config-change safe (saveable `Route` enum + retained session), and hosts the entities NavHost feature.
- `ui/components/BrandHeader.kt`, `AromexDialog.kt`, `CountryPicker.kt` — new reusable components (flat/rounded brand header, themed two-action dialog, searchable country-picker dialog + dial-code trigger).
- `util/Connectivity.kt` — `isOnline()` (ConnectivityManager) to gate pull-to-refresh.
- `AndroidManifest.xml` — `ACCESS_NETWORK_STATE` for the connectivity check.
- `build.gradle.kts`, `gradle/libs.versions.toml` — add `org.jetbrains.androidx.navigation:navigation-compose` for the NavHost.

### iOS (`iosApp/`)
- `ui/EntitiesView.swift`, `ui/EntityDetailView.swift`, `ui/EntityFormView.swift` — the three screens rebuilt to match: flat brand headers, NavigationStack with a typed route, fixed Detail hero (only transactions scroll), fixed Add/Edit header (role cards inside), searchable country picker, pull-to-refresh + offline dialog, `@FocusState` keyboard toolbar (Next/Done), pinned Save bar that stays put under the keyboard.
- `ui/HomeView.swift` — presents Entities via `.fullScreenCover` (single NavigationStack for the flow; fixes a nested-stack navigation loop).
- `ui/components/EntityComponents.swift`, `ui/components/EntitySupport.swift` — new SwiftUI helpers (avatar/role glyphs/balance display/section header; connectivity, country flag/picker, themed dialogs).

## How to test
1. **Android:** `./gradlew :androidApp:assembleDebug`, install on an emulator, sign in, open Entities.
   - List: search, color-coded balances (green/red/grey), TO RECEIVE / TO GIVE bar, FAB, empty/loading/error states, pull-to-refresh (turn off wifi → pull → "You're offline" dialog).
   - Add: role cards, country picker (Canada default, searchable), validation red border on bad email, unsaved-changes guard on back, Save disabled until a name is entered; the Save button stays put when the keyboard is up; Next/Done move between fields.
   - Detail: fixed blue hero (balance + contact), AA monogram → Edit/Archive (disabled for Walk-in), "coming soon" transactions.
   - Rotate the device on any screen → you stay on the same screen (no bounce to Home).
2. **iOS:** open `iosApp/iosApp.xcworkspace`, run on a simulator; verify the same flows. `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator build` builds clean.

## Acceptance criteria
- [x] Add / List / Detail match the design on **both Android and iOS**, all states, light + dark.
- [x] **Add is a full screen** with a working **unsaved-changes guard**; List has FAB / empty / loading / error; Detail shows the read-only balance, contact, "coming soon" history; **Walk-in** is non-editable.
- [x] Money renders via the **`session.currency`** formatter (dynamic symbol, decimal strings, no float); balances color-coded green/red/grey.
- [x] **Opening balance editable on Add, read-only on Edit**; existing M3 flow/logic **unchanged**.
- [x] Strictly native UI per platform (Compose / SwiftUI), no `sharedUI`, no logic in UI; no secrets; builds + runs on Android emulator and iOS simulator.

## Deviations / decisions
- **Country-code picker is now in scope** (the ticket had it as a deferred enhancement) — the PM requested a searchable dropdown defaulting to Canada; the dial code is stored as a display prefix on the phone number and re-derived on edit.
- **Roles on the form** show only **Customer / Supplier** cards (per the provided design); Middleman is omitted on the form (still a valid role elsewhere). The list role filter chips are omitted (not in the provided design).
- **Transactions** on Detail render the **coming-soon placeholder only** (no data source yet — out of scope); the sample rows in the mock are illustrative.
- Navigation uses **NavHost (Android) / NavigationStack (iOS)**, and app routing was made config-change safe (saveable route + retained session) so rotation/theme changes don't return to Home.
- This branch **supersedes the previously merged #37 UI (PR #39)** per direction.

## Open questions / follow-ups
- **Visual sign-off** of the iOS screens couldn't be scripted (the simulator needs a gateway login), so the iOS layouts are code-verified/build-verified but need an on-device eyeball.
- Pre-existing, unrelated: `FirebaseAppFactory` triggers Main-Thread-Checker warnings on iOS (Firebase configured on a background queue) — worth a separate ticket.
- "Adjust balance" (editing a balance), the full transaction history, Home dashboard entry point, and Desktop (#38) remain out of scope as per the ticket.
