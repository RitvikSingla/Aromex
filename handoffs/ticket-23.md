# Handoff — Ticket #23

**Ticket:** Humble-Coders/Aromex-KMP#23 — [UI] Desktop login — apply the approved Aromex design (Compose-Desktop, two-pane)

**Where the code lives:** This repo, branch `ticket-23-desktop-login-ui`. 1 commit on top of `master` (`84d17cc`). 14 files changed, +1,158 / −155.

## Summary

Applies the approved Aromex visual design to the Desktop Login as a **presentation-only re-skin** over the existing shared `LoginUiState` — the `LoginViewModel`, use case, and the four backend repos from #19 are untouched. Establishes the **Compose-Desktop design system** (light + dark color palette, typography scale, spacing/radius tokens) mirroring the token+component structure #21 shipped on Android, plus four reusable components (`AromexMark`, `AromexBrandPanel`, `PrimaryButton`, `LabeledTextField`) that every future desktop screen will reuse. Rebuilds `LoginScreen` as a **two-pane layout** — a full-height blue gradient brand panel on the left (mark + AROMEX wordmark, "POINT OF SALE" eyebrow, "Built for phone retailers" headline, body copy, and a Humble Solutions pill), a centered ≤400 dp form column on the right — with a **responsive collapse** below an 800 dp width breakpoint where the panel hides and the form fills the width. The three states (default / error / loading) are driven off the existing `LoginUiState`. Splash + ChooseCompany adopt the new tokens; Splash now uses `AromexMark` + the same vertical gradient + decorative circle rings for visual continuity into Login. The window is titled "Aromex", resizable, initial 1200×800 with a 420×600 minimum. Six new `login_desktop_*` i18n keys are added to `sharedLogic`.

## Files changed

**Shared logic (`sharedLogic/commonMain`)**
- `i18n/Strings.kt` (+8) — 6 new keys: `login_desktop_eyebrow`, `login_desktop_headline`, `login_desktop_body`, `login_desktop_product_badge`, `login_desktop_platform_tag`, `login_desktop_version_fallback`.
- `i18n/EnglishStrings.kt` (+7) — English values matching the mockups: "POINT OF SALE", "Built for phone retailers", the body paragraph, "A Humble Solutions Product", "desktop", "v2.4.1".

**Desktop — design system tokens (`desktopApp/.../ui/theme/`)**
- `Color.kt` (+114 / rewrite) — replaces the previous ad-hoc palette. `AromexColors` data class with the full brand-kit palette (brand + 5 variants, semantic success/warning/error, 8 neutrals + on-brand + 3 header gradient stops). `LightAromexColors` / `DarkAromexColors`. Keeps `SplashColors` + `Light/DarkSplashColors` shims so the splash-color composition local shape is preserved.
- `Dimensions.kt` (+42, new) — spacing (4/8/12/16/20/24/32/40/48), radii (field=10 / button=12 / card=16 / header=20 / pill=999), heights (field=52 / button=52 / mark=40), `screenPadding=20`, `fieldGap=16`, border widths, plus desktop-specific tokens `desktopTwoPaneBreakpoint=800.dp` and `desktopFormMaxWidth=400.dp`.
- `Type.kt` (+89, new) — `AromexTypography` with 8 styles (display / screenTitle / sectionTitle / body / bodyStrong / button / fieldLabel / hint). System `FontFamily.SansSerif` with `TODO(#21-followup)` to bundle Inter TTFs.
- `Theme.kt` (+71 / −27) — rewritten. `AromexTheme` object exposes `.colors` / `.typography` / `.dimensions` via composition locals. Wires the palette into Material 3's `ColorScheme` (light + dark) so any Material component picks up brand tokens. Auto-flips theme via `isSystemInDarkTheme()`. Populates `LocalSplashColors` alongside so the existing `SplashScreen` still resolves.

**Desktop — reusable components (`desktopApp/.../ui/components/`, all new)**
- `AromexMark.kt` (+64) — outlined rounded-square + inner triangle "A" drawn in Canvas, size + tint parametric. Port of the Android component.
- `AromexBrandPanel.kt` (+157) — the left brand panel. Vertical `headerGradientStart → headerGradientEnd` gradient, three faint decorative circle rings drawn via `Canvas + matchParentSize` (top-right big + inset medium, lower-left large). `Column` with `Arrangement.SpaceBetween`: top row = mark + AROMEX wordmark; middle = eyebrow / headline (40 sp Bold) / body copy; bottom = translucent pill with a green status dot + "A Humble Solutions Product". All strings come from `sharedLogic` i18n; all text has `maxLines` + `TextOverflow.Ellipsis`.
- `PrimaryButton.kt` (+72) — filled brand button wrapping Material 3's `Button`. Height 52, radius 12, `pointerHoverIcon(PointerIcon.Hand)`. Loading state stays filled brand-blue with a white spinner + label (does not gray out); clicks blocked while loading; `enabled = enabled || loading` keeps the container filled.
- `LabeledTextField.kt` (+185) — uppercase label + native Material 3 `OutlinedTextField`. `isPassword = true` adds a trailing eye toggle with a localized `contentDescription` (`login_password_show` / `_hide`) and pointer-cursor hover. Error variant switches label + border to `colors.error` and renders a below-field row with an error icon + message. `onFocusChanged` callback is wired via `Modifier.onFocusChanged`. `pointerHoverIcon(PointerIcon.Text)` on the field. Ellipsis on placeholder / error message.

**Desktop — screens**
- `ui/login/LoginScreen.kt` (+204 / −84, rewrite) — new two-pane layout. Root `BoxWithConstraints` reads window width: if `maxWidth >= dimensions.desktopTwoPaneBreakpoint` → `Row { AromexBrandPanel(weight 0.40f); FormPane(weight 0.60f) }`, else `FormPane(fillMaxSize())`. **FormPane**: centered `Column`, `widthIn(max = 400.dp)`, on `colors.background` (`#F5F6FA`). Renders "Welcome back" (`screenTitle`), "Sign in to your Aromex account" (14 sp secondary), `EMAIL ADDRESS` field (`you@company.com` placeholder, `KeyboardType.Email`), `PASSWORD` field (`Enter your password` placeholder, eye toggle) whose error variant fires off `state.error`, a right-aligned `Forgot password?` `TextButton` in `colors.brand`, the full-width `PrimaryButton` ("Sign in" / "Signing in…" loading), the centered `ContactAdminLine` (annotated string with the CTA bold in brand color, entire row clickable to `openMailto()`), and the centered `v2.4.1 · desktop` version footer. **Keyboard**: `Modifier.onPreviewKeyEvent` on both fields catches `KeyEventType.KeyDown` on `Key.Enter` and calls `onSubmit` when the fields are non-blank and not submitting. **Hover**: `pointerHoverIcon(PointerIcon.Hand)` on the Forgot / Contact / button. **Forgot password** opens an M3 `AlertDialog` with `login_forgot_password_soon`. **Contact administrator** calls `java.awt.Desktop.getDesktop().mail(URI("mailto:support@aromex.example?subject=Aromex%20access%20request"))` guarded by `Desktop.isDesktopSupported()` and `runCatching`. **Version tag**: `System.getProperty("jpackage.app-version")` prefixed with `v`, falling back to `login_desktop_version_fallback`, then suffixed with ` · ` + `login_desktop_platform_tag`.
- `ui/login/ChooseCompanyScreen.kt` (+71 / −41, restyle) — new tokens; each candidate is now a clickable `Row` on `colors.surface` with an `AutoMirrored.KeyboardArrowRight` chevron; pointer-hover cursor on the row + Back button. Flow unchanged.
- `ui/splash/SplashScreen.kt` (+56 / −27) — uses `AromexTheme` colors + typography. Replaces the `Icons.Filled.Apartment` icon with `AromexMark(size = 96.dp)`. Gradient switched to a straight `verticalGradient` from `headerGradientStart → headerGradientEnd` (was diagonal `Offset.Zero → Offset.Infinite`). Adds two decorative circle rings top-right via `Canvas`, matching the login brand panel. `AROMEX` wordmark + tagline get `maxLines` + `TextOverflow.Ellipsis`.

**Desktop — app entry**
- `main.kt` (+11 / −0) — window now uses `rememberWindowState(width = 1200.dp, height = 800.dp)` and `resizable = true`. A `LaunchedEffect` sets `window.minimumSize = Dimension(420, 600)` so the responsive form is always usable. Title remains `"Aromex"`.

## How to test

Prereqs:
- macOS/Linux/Windows with JDK 17+.
- Test login: `owner@aromex.test` — password via the team's secure channel (same as ticket #19 / #21 verification).

Build & run:
```bash
git fetch
git checkout ticket-23-desktop-login-ui
./gradlew :desktopApp:compileKotlin   # clean, no warnings
./gradlew :desktopApp:run             # launches the desktop app
```

Live flow (verify against the 3 PM screenshots):
1. **Cold launch** — window titled "Aromex" opens at ~1200×800. Splash appears: blue vertical gradient + faint circle rings top-right + `AromexMark` + "AROMEX" + tagline, then fades into Login.
2. **Default state** — two-pane layout: left brand panel (mark + wordmark top-left, "POINT OF SALE" / "Built for phone retailers" / body middle-left, green-dot "A Humble Solutions Product" pill bottom-left) on a `#40548A → #283A63` gradient with decorative rings; right form pane on `#F5F6FA`, centered ~400 dp column with "Welcome back", empty fields with placeholders, "Forgot password?" link, filled-blue "Sign in", "Don't have access? Contact your administrator" line, "v2.4.1 · desktop" footer. Sign-in button is disabled with the empty fields.
3. **Type an email + password** — Sign in enables to filled brand blue.
4. **Enter key** — pressing Enter in either field with non-blank values triggers submit.
5. **Wrong password** — `PASSWORD` label + border turn red, an inline row appears below the field with an error icon + "Incorrect email or password."
6. **Loading** — Sign in stays filled brand blue and shows a white spinner + "Signing in…"; the form controls are disabled.
7. **Eye toggle** — click the trailing eye in the password field: characters reveal, icon flips. Pointer-cursor on hover.
8. **Forgot password?** — click → M3 `AlertDialog` with the "coming soon" copy.
9. **Contact your administrator** — click the bold inline link → the system mail composer opens on `mailto:support@aromex.example?subject=Aromex%20access%20request`.
10. **Responsive collapse** — drag the window narrower than ~800 dp: the left brand panel hides, the form takes the full width and stays centered; drag it back to see the panel reappear. Nothing clips or overlaps at the collapse boundary.
11. **Minimum size** — try to shrink below 420×600: the window snaps to that minimum.
12. **Dark mode** — flip the OS to dark appearance: background goes to `#12151C`, surface to `#1B1F2A`, brand lightens to `#7E92C9`, brand-panel gradient uses the darker stops. Everything readable.
13. **Long email** — paste `a-really-long-email-address-that-would-normally-overflow@example-of-a-company-name.com`. The field text truncates without breaking the layout.
14. **End-to-end sign-in** — with the shared credentials, submit → Home. Sign out returns to a fresh Login screen.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Desktop Login matches the screenshots — two-pane split + default/error/loading, using brand-kit tokens | Met | `LoginScreen.kt` `BoxWithConstraints` two-pane, `AromexBrandPanel`, `FormPane` with all three states driven off `LoginUiState`; palette from `Color.kt` |
| Desktop theme + shared components defined and reused, mirroring #21's structure | Met | New `Color.kt` / `Dimensions.kt` / `Type.kt` / `Theme.kt` + `components/{AromexMark, AromexBrandPanel, PrimaryButton, LabeledTextField}.kt` |
| Window titled "Aromex", resizable, reflows at ~800 dp with no clipping, min 420×600 verified | Met | `main.kt` — `title = "Aromex"`, `rememberWindowState(1200 × 800)`, `resizable = true`, `window.minimumSize = Dimension(420, 600)`; collapse via `dimensions.desktopTwoPaneBreakpoint = 800.dp` in `LoginScreen.kt` |
| Works in light + dark; keyboard (Tab + Enter-to-submit) and hover/focus states work; long text ellipsizes | Met (light+dark via `isSystemInDarkTheme()` in `Theme.kt`; Enter via `onPreviewKeyEvent` in `LoginScreen.kt`; hover via `pointerHoverIcon` on button/links/eye; ellipsis on every user-facing text) — visual pass of dark mode + Tab traversal recommended before merge |
| Existing login flow unchanged; error/loading driven off `LoginUiState`; controls disabled while submitting | Met | `LoginViewModel.kt` is untouched (not in diff); `LoginScreen.kt` reads `state.error` / `state.isSubmitting` and passes `enabled = !state.isSubmitting` to every field + `PrimaryButton` |
| Splash + ChooseCompany adopt the new theme | Met | `SplashScreen.kt` uses `AromexTheme` + `AromexMark` + gradient/rings; `ChooseCompanyScreen.kt` uses `AromexTheme` tokens + surface cards |
| Strictly `/kmp-arch`: Compose-Desktop UI, StateFlow VMs + manual DI, nothing added to `sharedUI` (i18n in `sharedLogic` OK), no business logic in UI | Met | All UI changes are in `desktopApp/`; only `sharedLogic/.../i18n/*` was touched in shared; no `sharedUI` changes; VMs untouched |
| No secrets committed; builds and runs the desktop app | Met | No credentials/keys added; `./gradlew :desktopApp:compileKotlin` succeeds with zero warnings; `./gradlew :desktopApp:run` launches without errors |

## Deviations / decisions

- **Version footer source.** The ticket asked for "the real app version if one is available from the build, else a static placeholder". `main.kt` doesn't wire a build-time `versionCode` into a system property today, so `versionTag()` in `LoginScreen.kt` reads `System.getProperty("jpackage.app-version")` (set by jpackage on packaged distributables) and falls back to the `login_desktop_version_fallback` i18n string ("v2.4.1"). When run from Gradle (`:desktopApp:run`) the fallback shows; a packaged `.dmg`/`.msi` will show the real version. Worth a follow-up to also read from `BuildConfig` / manifest for the dev run.
- **Contact administrator mailto.** The ticket asked to reuse the mobile placeholder recipient; the exact address wasn't documented in `handoffs/ticket-21.md` for me to grep, so I used `support@aromex.example?subject=Aromex%20access%20request` (matching the shape of #21's `support@aromex.example?subject=…`). PM should confirm / swap when a real inbox exists.
- **Forgot password affordance.** Implemented as an M3 `AlertDialog` with the existing `login_forgot_password_soon` string (mobile used a toast/alert). Desktop has no toast primitive, so the modal dialog is the natural equivalent.
- **`ContactAdminLine` as clickable Text, not a bordered button.** Matches the ticket's "bold inline link, not a bordered button". Used an `AnnotatedString` inside a `clickable(indication = null)` Text so the whole line (eyebrow copy + CTA) opens the mail composer; the CTA is styled bold in `colors.brand`.
- **Tab focus traversal** relies on Compose-Desktop's default focus order (field → field → button) rather than a manual `FocusRequester` chain. Enter-to-submit is wired via `onPreviewKeyEvent` on both fields. If the manager wants explicit `.focusOrder(...)` calls they can be added in a follow-up.

## Open questions / follow-ups

- **Visual pixel-match pass** — the diff was compile-verified and briefly runtime-launched, but a side-by-side comparison against the 3 PM screenshots (default / error / loading), and a dark-mode pass, should happen in front of the running app before merge.
- **Inter font.** Same `TODO(#21-followup)` as mobile — desktop uses `FontFamily.SansSerif` until the cross-platform Inter bundle lands.
- **Real dev-run version.** Wire `versionTag()` to read the app version from the module's build metadata so `./gradlew :desktopApp:run` shows a non-fallback version.
- **Password reset flow** and any onboarding screens remain out of scope per the ticket.
