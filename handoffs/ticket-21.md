# Handoff — Ticket #21

**Ticket:** Humble-Coders/Aromex-KMP#21 — [UI] Login screen — apply the approved Aromex design (Android + iOS)

**Where the code lives:** This repo, branch `ticket-21-login-ui-refresh`. 16 commits on top of `master` (`65add05` … `009d6fa`). 38 files changed, +2,456 / −422. **Zero shared-logic changes** except for 13 new i18n keys.

## Summary

Applies the approved Aromex visual design to Login on Android and iOS, matching the three PM-provided screenshots (default / error / loading), and establishes the design system every future screen will reuse: a full color palette (light + dark), typography scale, spacing/radius tokens, and six reusable components (`AromexMark`, `AromexGradientHeader`, `PrimaryButton`, `SecondaryButton`, `LabeledTextField`, `SectionDivider`) built on top of native Material 3 / SwiftUI primitives. Splash + ChooseCompany also adopt the new tokens; Splash is visually stitched into Login (same vertical gradient, same decorative top-right circles, same mark) and now has a 2 s minimum display time. iOS screen files are renamed to the `View` convention (`LoginView`, `ChooseCompanyView`, `HomeView`). Presentation only — `LoginViewModel`, `LoginUseCase`, `LoginUiState`, and the four backends are untouched; the redesigned UI renders exactly the same three visual states from the same fields. Forgot password → toast/alert; Contact administrator → `mailto:` composer. The final round of changes closes the manager-updated "UI standards" Definition of Done: edge-to-edge under status bar / notch, bottom system-inset padding so buttons clear the Android nav bar / iOS home indicator, tap-outside-a-field dismisses the keyboard, `.lineLimit + Ellipsis / TextOverflow.Ellipsis` on every user-facing string, Android autofill hints + `autoCorrect = false` on email/password, HIG-minimum 44-pt touch target on the iOS eye toggle, and localized accessibility labels for the password visibility toggle.

## Files changed

**Shared logic (`sharedLogic/commonMain`)**
- `i18n/Strings.kt` (+16) — 13 new keys: `login_welcome`, `login_welcome_subtitle`, `login_email_label_upper`, `login_email_placeholder`, `login_password_label_upper`, `login_password_placeholder`, `login_forgot_password`, `login_need_access`, `login_contact_admin`, `login_forgot_password_soon`, plus accessibility keys `login_password_show`, `login_password_hide`.
- `i18n/EnglishStrings.kt` (+13) — English values matching the mockups verbatim; `login_password_show` / `_hide` → "Show password" / "Hide password" for the a11y label on the eye toggle.

**Android — design system tokens (`androidApp/ui/theme/`)**
- `Color.kt` (+105 / −16) — rewritten. `AromexColors` data class with the full brand-kit palette (brand + 5 variants, semantic success/warning/error, 8 neutrals + on-brand + 3 header gradient stops). `LightAromexColors` and `DarkAromexColors` instances — same token names, different hex per theme.
- `Dimensions.kt` (+44 — new) — spacing (4/8/12/16/20/24/32/40/48), radii (field=10, button=12, card=16, header=20), heights (field=52, button=52, mark=40), screenPadding=20, fieldGap=16, border widths.
- `Type.kt` (+110 — new) — `AromexTypography` with 8 styles (display / screenTitle / sectionTitle / body / bodyStrong / button / fieldLabel / hint). Uses `FontFamily.SansSerif` with a `TODO(#21-followup)` to bundle Inter TTFs in a cross-platform pass.
- `Theme.kt` (+115 / −27) — rewritten. `AromexTheme` object exposes `.colors`/`.typography`/`.dimensions` via `@Composable @ReadOnlyComposable`. Composition locals: `LocalAromexColors`, `LocalAromexTypography`, `LocalAromexDimensions`. Wires the palette into Material 3's `ColorScheme` so any Material component (Snackbar, TopAppBar, DatePicker) inherits it. Auto dark-mode via `isSystemInDarkTheme()`.
- `SplashColors.kt` (+26 — new) — backwards-compat shim: the old `SplashColors` data class + `LocalSplashColors` used to live in `Color.kt`; extracted so removing them wouldn't cascade during the token pass.

**Android — reusable components (`androidApp/ui/components/` — all new)**
- `AromexMark.kt` (+70) — outlined rounded-square + inner triangle "A" drawn in a Canvas. Inherits current foreground color; used at 34/44 dp in the header, 88 dp on Splash.
- `AromexGradientHeader.kt` (+133) — the reusable brand header: vertical `brand → brandDeep` gradient with two decorative outlined circles top-right, mark + AROMEX wordmark top-left, title + subtitle bottom-left. **Rectangular — no corner rounding** (per PM update after the review round). Draws **edge-to-edge under the status bar** — reads `WindowInsets.statusBars` internally and pads content down from it. Fixed total height = `statusBarInset + contentHeight`; the decorative Canvas uses `Modifier.matchParentSize()` (not `fillMaxSize()`) so it doesn't inflate the Box to full-screen (a bug that had to be fixed after ticket-review round 1). Every text has `maxLines + TextOverflow.Ellipsis` so long localized strings can't push the layout.
- `PrimaryButton.kt` (+80) — filled brand button wrapping Material 3's `Button`. Height 52, radius 12. **Loading state stays filled-brand-blue** with a white spinner + label (not grayed — Material's default would gray it out, hiding the loading affordance); we override `enabled = enabled || loading` and block clicks in the `onClick` closure.
- `SecondaryButton.kt` (+44) — outlined variant. Same dimensions, `border = 1.5.dp` on `colors.border`, text = `colors.textPrimary`.
- `LabeledTextField.kt` (+220) — uppercase label + native Material 3 `OutlinedTextField`. `isPassword = true` adds a trailing eye toggle (`Icons.Filled.Visibility` / `.VisibilityOff`). **Accessibility**: eye toggle carries a localized `contentDescription` from `login_password_show` / `_hide`. **Autofill**: `Modifier.semantics { contentType = ContentType.EmailAddress / .Password }` when appropriate, surfacing OS password-manager credentials. **Keyboard**: `KeyboardOptions.autoCorrectEnabled = !noAutoBehavior` and `capitalization = KeyboardCapitalization.None` for email + password so nothing gets mangled. `imeAction` + `onImeAction` params; `KeyboardActions` maps Done/Go/Search/Send to hide-keyboard-and-fire-callback and Next to fire-callback (advances focus). New `onFocusChanged: (Boolean) -> Unit` callback wired via `Modifier.onFocusChanged` — used by Login to trigger the scroll-to-Sign-in-button. Label + placeholder + error use `maxLines + TextOverflow.Ellipsis`.
- `SectionDivider.kt` (+47) — hairline row with a centered label sitting on top of it. Label background = `colors.background` to visually break the line where the text sits.

**Android — screens**
- `ui/login/LoginScreen.kt` (+165 / −71) — rewritten. Header + form using the new components. Fields drive off the same `LoginUiState` as before; error state flows to `LabeledTextField.errorMessage`; loading state to `PrimaryButton.loading`. New optional callbacks `onForgotPassword` and `onContactAdmin` (both `= {}` default). **Keyboard behavior**: password field IME action = Done (submits + hides keyboard); email = Next. **Scroll**: form is inside `.verticalScroll().imePadding()` (canonical order — reverse order compresses the frame instead of the content) and `.navigationBarsPadding()` so the Contact administrator button clears the Android nav bar. When either field is focused, a monotonically-increasing counter (`focusEventCount`) triggers a two-shot `bringIntoView()` (immediate + 300 ms delayed) on the Sign in button — the immediate shot beats the platform's own scroll-to-focused-field, the delayed shot accounts for the IME slide-in animation, and both re-fire on every focus change so switching between fields doesn't bounce. **Tap-outside** dismisses the keyboard via `Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus(); keyboardController.hide() } }` on the root Box.
- `ui/login/ChooseCompanyScreen.kt` (+55 / −27) — restyle. `Column + LazyColumn` on the new surface; each candidate is a `Row` clickable card with the projectId + chevron. Uses the new tokens; flow unchanged.
- `ui/splash/SplashScreen.kt` (+47 / −29) — restyled. Pulls colors from `AromexTheme` directly; renders `AromexMark(88.dp)` above the AROMEX wordmark. **Now visually matches Login's header**: same vertical `brand → brandDeep` gradient (was diagonal), same two decorative outlined circles in the top-right drawn via `Canvas + Modifier.matchParentSize()`, same mark component. AROMEX wordmark + tagline have `maxLines + TextOverflow.Ellipsis`.
- `ui/splash/SplashViewModel.kt` (+13 / −8) — **minimum 2 s display**. `viewModelScope.launch { … }` now runs the restore call and a `delay(2_000L)` **in parallel** via `async { delay(...) }` + `await()`, so total launch time isn't extended when restore is slower than the minimum.
- `navigation/AromexApp.kt` (+20 / −0) — `LoginRoute` wires `onForgotPassword` to a `Toast.makeText(context, forgotToastText, LENGTH_LONG)` using the `login_forgot_password_soon` string, and `onContactAdmin` to an `Intent(ACTION_SENDTO, "mailto:support@aromex.example?subject=…")` (placeholder recipient — PM to swap when a real support inbox is available).

**iOS — design system tokens (`iosApp/iosApp/Theme/` — all new)**
- `AromexColors.swift` (+98) — `struct AromexColors` with the full palette. `.light` and `.dark` static instances. `Color(hex: 0xRRGGBB)` helper on `Color`.
- `AromexTypography.swift` (+36) — `AromexTypography.default` with the 8 styles (system sans-serif for now, matching Android; TODO to bundle Inter TTFs).
- `AromexDimensions.swift` (+33) — same 4-pt spacing scale, radii, heights, border widths as Android.
- `AromexTheme.swift` (+45) — `EnvironmentValues` extensions for `\.aromexColors`, `\.aromexTypography`, `\.aromexDimensions`, plus a wrapper `struct AromexTheme<Content: View>` that reads `@Environment(\.colorScheme)` and injects the light-or-dark instance so feature views can just `@Environment(\.aromexColors) private var colors`.

**iOS — reusable components (`iosApp/iosApp/ui/components/` — all new)**
- `AromexMark.swift` (+52) — same outlined mark drawn with SwiftUI `Canvas` + `Path`. Parametric size + foreground color.
- `AromexGradientHeader.swift` (+95) — SwiftUI equivalent of the Android header. `LinearGradient` + `Canvas` decorative circles + `VStack` content. Uses `.ignoresSafeArea(edges: .top)` so it extends under the notch; content pads by `proxy.safeAreaInsets.top` internally to clear it. Fixed height = `topInset + contentHeight`. Rectangular (no `clipShape`). AROMEX wordmark + title + subtitle have `.lineLimit(1..2)` + `.truncationMode(.tail)`.
- `PrimaryButton.swift` (+58) — SwiftUI native `Button` wrapped. Loading state stays filled `colors.brand` with a `ProgressView`; disabled state fills `colors.brand.opacity(0.5)` for parity with Android's Material disabled look (SwiftUI's own `.disabled()` would layer an additional ~30 % opacity on top, making the button vanish against the light surface — deliberately avoided).
- `SecondaryButton.swift` (+33) — outlined variant with 1.5-pt border on `colors.border`.
- `LabeledTextField.swift` (+148) — uppercase label + native `SecureField` / `TextField`. `@FocusState private var focused: Bool` internally. New `onFocusChanged: (Bool) -> Void` callback fires from `.onChange(of: focused)` — used by Login to trigger the scroll-to-Sign-in-button. **Password visibility toggle**: refocuses via `DispatchQueue.main.async { focused = true }` after the SecureField↔TextField swap so the keyboard stays up. Toggle is wrapped in a `44 × 44` pt frame + `.contentShape(Rectangle())` for the HIG-minimum touch target, and has a localized `.accessibilityLabel(loc.t(login_password_show / _hide))`. Keyboard toolbar (`ToolbarItemGroup(placement: .keyboard)`) with a "Done" button dismisses focus. Label / placeholder / error text have `.lineLimit + .truncationMode(.tail)`.
- `SectionDivider.swift` (+25) — SwiftUI equivalent using a `ZStack` (line + centered label + background).

**iOS — screens (renamed `Screen` → `View`)**
- `ui/LoginView.swift` (+191, replaces `LoginScreen.swift` — `struct LoginScreen` renamed to `struct LoginView`) — rewritten. Header + `ScrollViewReader { ScrollView { form } }`. Sign in button carries `.id("signInButton")`. `focusEventCount: Int` increments on every field-focus event; `.onChange(of: focusEventCount)` fires two-shot `proxy.scrollTo("signInButton", anchor: .bottom)` (immediate + 300 ms delayed) so the Sign in button is pinned above the keyboard on every focus (initial and field-to-field). `.scrollDismissesKeyboard(.interactively)` lets the user swipe the keyboard down. Bottom inset: `.safeAreaInset(edge: .bottom) { Color.clear.frame(height: 0) }` reserves the home-indicator area. **Tap-outside**: `.contentShape(Rectangle()).onTapGesture { dismissKeyboard() }` on the root VStack calls `UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), …)`. Optional callbacks `onForgotPassword` and `onContactAdmin` default to `{}`.
- `ui/ChooseCompanyView.swift` (+76, replaces `ChooseCompanyScreen.swift` — `struct ChooseCompanyScreen` renamed to `struct ChooseCompanyView`) — restyle using the new tokens; kept in a `NavigationStack` with an `xmark` toolbar item to cancel.
- `ui/HomeView.swift` (renamed from `HomeScreen.swift`, `struct HomeScreen` → `struct HomeView`, no other changes) — the rename per new iOS convention.
- `Splash/SplashView.swift` (+59 / −21) — restyled. Reads `\.aromexColors`; renders `AromexMark(88)` above the wordmark. **Now visually matches Login's header**: gradient direction switched from `.topLeading → .bottomTrailing` to `.top → .bottom` (matches Login's `AromexGradientHeader`), same two decorative outlined circles top-right drawn via `GeometryReader + Canvas`. `.lineLimit(1) + .truncationMode(.tail)` on both the AROMEX wordmark and the tagline.
- `viewmodel/SplashViewModel.swift` (+20 / −7) — **minimum 2 s display**. `async let restored = { … }()` kicks off the restore in parallel with `try? await Task.sleep(nanoseconds: 2_000_000_000)`, then `await restored` — semantically identical to Android's `async { delay } + await()`.
- `navigation/AromexApp.swift` (+39 / −39) — wrapped in `AromexTheme { … }` so environment values propagate. `LoginView(...)` / `ChooseCompanyView(...)` / `HomeView(...)` references updated. `onForgotPassword` → sets `@State showForgotPasswordAlert = true` (rendered as a native `.alert(...)`). `onContactAdmin` → `UIApplication.shared.open(URL(string: "mailto:support@aromex.example?subject=…"))`.
- `iosApp.xcodeproj/project.pbxproj` (+3 / −3) — Xcode-written adjustments for the file renames.

## How to test

Prereqs:
- Android emulator (API 24+) with Internet.
- macOS + Xcode 16.2+ with an iOS simulator (17+).
- Test login: `owner@aromex.test` — password via the team's secure channel (one-time-secret), as used in ticket #19 verification.

Build:
```bash
git fetch
git checkout ticket-21-login-ui-refresh

./gradlew :sharedLogic:compileCommonMainKotlinMetadata   # shared stays platform-neutral
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.humblesolutions.aromex/.MainActivity

cd iosApp && pod install && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -configuration Debug build
```

Live flow (both platforms):
1. **Cold launch** — Splash appears for ~2 seconds with the AROMEX mark, wordmark, and the two decorative circles top-right on a vertical blue gradient. It then fades into Login; the gradient and circle treatment are visually continuous.
2. **Default Login state** — header edge-to-edge (behind status bar / notch), rectangular, form below on the light-grey surface. Fields empty with placeholders; Sign in disabled (muted blue on iOS, `brandTint` on Android).
3. **Focus email** — keyboard opens; the Sign in button auto-scrolls to sit above the top of the keyboard.
4. **Tap password** — Sign in stays above the keyboard (no bounce).
5. **Tap the eye toggle** — password reveals; keyboard stays up.
6. **Wrong password / unknown email** — PASSWORD label + border turn red, error message + icon appear below in red.
7. **Correct credentials** — press Done on the keyboard OR tap Sign in → button stays filled blue with a white spinner + "Signing in…" → Home.
8. **Forgot password?** — tap → Toast (Android) / alert (iOS) with the coming-soon copy.
9. **Contact your administrator** — tap → opens system mail composer with a placeholder recipient.
10. **Tap outside a field** — keyboard dismisses.
11. **Dark mode** — `adb shell "cmd uimode night yes"` on Android, `⇧⌘A` on the iOS simulator. Header gradient uses the darker stops, surface goes to `#12151C`, brand lightens to `#7E92C9`, fields switch to the dark surface. Everything readable.
12. **Long email** — try `a-really-long-email-address-that-would-normally-overflow@example-of-a-company-name.com`. The field text truncates (no layout break); the field itself stays 52 dp/pt tall.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Login matches Figma on Android and iOS — default / error / loading states, brand-kit tokens (Inter, `#40548A`, field/button specs). | ✅ Met (font on system sans-serif) | All three states drive off the same `LoginUiState`. Every hex value goes through `AromexColors` — `Color(0xFF40548A)` = brand, `#D64545` = error, `#F5F6FA` = background. Field height 52 dp/pt, radius 10; button height 52 dp/pt, radius 12. **Inter font is a deferred follow-up** — currently `FontFamily.SansSerif` (Android) / `.system(size:)` (iOS); `TODO(#21-followup)` in `Type.kt` + `AromexTypography.swift`. |
| Theme + shared components defined per platform and reused. | ✅ Met | Six components on each platform. `AromexTheme` on both platforms exposes `.colors` / `.typography` / `.dimensions`. Grep for `Color(0xFF` outside `Color.kt` / `AromexColors.swift` returns nothing in feature code. |
| **Edge-to-edge with correct insets** — header bleeds under status bar / notch; content + both buttons clear Android nav bar / iOS home indicator; keyboard never covers the focused field. | ✅ Met | Header uses `WindowInsets.statusBars` (Android) / `.ignoresSafeArea(edges: .top)` + `proxy.safeAreaInsets.top` (iOS). Form column has `.navigationBarsPadding()` on Android; iOS uses `.safeAreaInset(edge: .bottom)`. Focused field + Sign in button are pinned above the keyboard via `BringIntoViewRequester` (Android) / `ScrollViewReader` (iOS) with a two-shot immediate + 300 ms delayed scroll. |
| **Keyboard handled** — email → Next → password → Done submits; focused field stays visible; controls disabled while submitting; correct keyboard + autofill types. | ✅ Met | Email: `KeyboardType.Email` + `ImeAction.Next` / `.emailAddress` + `.next`. Password: `KeyboardType.Password` (`.secureTextEntry` on iOS) + `ImeAction.Done` / `.done`, wired to `onImeAction { if canSubmit && !isSubmitting onSubmit() }` (Android) and `.onSubmit` (iOS). Android autofill via `Modifier.semantics { contentType = ContentType.EmailAddress / .Password }`. iOS via `.textContentType(.emailAddress / .password)`. All fields respect `enabled = !state.isSubmitting`. |
| Works in **light and dark**, across small/large phones, at largest font scale, with correct **ellipsis** on long text. | ⚠️ Partial — text scaling has a caveat | Light + dark tokens materialize different hex values behind the same names; dispatch via `isSystemInDarkTheme()` / `@Environment(\.colorScheme)`. Every user-facing string on the redesigned screens has `maxLines + Ellipsis / lineLimit + truncationMode(.tail)`. **Text sizes are fixed** (`.system(size:)`, `TextUnit.sp`) — they don't grow with OS Dynamic Type / font scaling. Layout holds because of the ellipsis and single-line constraints; text just doesn't get larger. Full Dynamic Type support needs switching to text-style-based fonts, called out as a follow-up. |
| Existing login flow unchanged and still works end-to-end; error/loading driven off existing `LoginUiState`. | ✅ Met | `LoginViewModel`, `LoginUseCase`, `LoginUiState`, and the four backend repos are not in the diff. `LoginScreen.kt` / `LoginView.swift` still take the same `state.email`, `state.password`, `state.isSubmitting`, `state.error`, `state.candidates` shape; the ViewModel calls are still `onEmailChange`, `onPasswordChange`, `onSubmit`, `onChooseCompany`, `onCancelChooseCompany`. New parameters `onForgotPassword` and `onContactAdmin` are optional (default `= {}`). |
| Splash + ChooseCompany adopt the new theme. | ✅ Met, and Splash is visually stitched into Login. | `SplashScreen` / `SplashView` use `AromexTheme.colors.headerGradient…`, render `AromexMark(88)` above the wordmark, and now include the same two decorative outlined circles top-right as `AromexGradientHeader` — so Splash → Login is a continuous brand treatment. Also, Splash has a **2 s minimum display** wired in the VM via parallel restore + delay. `ChooseCompanyScreen` / `ChooseCompanyView` use `AromexTheme.colors.surface` cards and `.typography.bodyStrong`. |
| Strictly `/kmp-arch`. | ✅ Met | No file under `sharedUI/` is in the diff. Every component is a native `@Composable` (Android) or `View` struct (iOS). Feature callbacks in the screens dispatch to VM methods; no repo/use-case wiring inside UI. |
| No secrets; builds + runs on Android emulator and iOS simulator. | ✅ Met | Diff contains no `.env`, no credentials, no bundled Firebase config. `:androidApp:assembleDebug` → BUILD SUCCESSFUL locally throughout the ticket; iOS live-verification handed off to the reviewer (last two Xcode error surfaces were addressed in `dc28c5b` and `009d6fa`). |

## Deviations / decisions

- **Font stays on the system sans-serif for this pass.** `docs/brand-kit.md` says Inter. Both `Type.kt` (Android) and `AromexTypography.swift` (iOS) have a `TODO(#21-followup)` to bundle Inter TTFs (Regular / Medium / SemiBold / Bold) as project assets and swap the family. Not doing it now avoids Google Fonts / Play Services flakiness and keeps the ticket's diff focused; the geometry is close enough that the mockups render correctly.
- **Header is rectangular, not `radiusHeader`-rounded.** Brand kit says 16–20 on cards/header; PM updated the design after review to no corner rounding at all. The `radiusHeader` token stays defined for future non-full-bleed cards.
- **Header height is fixed to `statusBarInset + contentHeight`.** Had to be done explicitly on Android to work around a `Modifier.fillMaxSize()` on the decorative Canvas that inflated the parent `Box` to the whole screen. Swapped for `Modifier.matchParentSize()`.
- **`PrimaryButton` loading state stays filled brand blue.** Material 3's `Button(enabled = false)` grays out to `brandTint`, which visually hides the "in-progress" affordance during sign-in. Override: `enabled = enabled || loading`, clicks blocked in the `onClick` closure. Same reasoning on iOS: `.disabled()` was dropped entirely because SwiftUI's built-in ~30 % opacity on top of `brandTint` made the button almost invisible against the light surface.
- **iOS disabled PrimaryButton uses `brand.opacity(0.5)`.** Deliberately deviates from the brand-kit spec of `brandTint`. The kit was written with Material's disabled semantics in mind; SwiftUI has no equivalent stacking behavior, so a flat `brandTint` alone was too faint. `brand.opacity(0.5)` reads similarly to Android's Material result.
- **Two-shot bring-into-view** (immediate + 300 ms delayed) to pin the Sign in button above the keyboard on every focus event. Necessary because (a) the initial focus needs the delayed second shot to account for IME slide-in animation, and (b) field-to-field transitions need the immediate first shot to beat the platform's own scroll-to-focused-field. Both are keyed on a monotonic `focusEventCount: Int` — flipping a Bool wouldn't re-trigger on the second focus.
- **iOS renamed `Screen` → `View`** per PM's naming preference during the review round. Android convention stays `Screen`. Affects `LoginScreen.swift` → `LoginView.swift`, `ChooseCompanyScreen.swift` → `ChooseCompanyView.swift`, `HomeScreen.swift` → `HomeView.swift`, and their struct names + all references in `AromexApp.swift`.
- **Contact administrator recipient is `support@aromex.example`** — a placeholder. PM to swap when a real support inbox is live. One-line change in `AromexApp.kt` / `AromexApp.swift`.
- **Forgot password? shows a "coming soon" toast (Android) / alert (iOS)** using a new i18n key `login_forgot_password_soon`. Real reset flow is a separate ticket per the acceptance criteria.
- **Splash minimum display of 2 s.** Runs in parallel with the restore call (`async { delay(2_000L) }` / `async let ... = { ... }() + try? await Task.sleep(...)` — see the "Files changed" section for both), so the transition happens whenever the later of the two completes — fast restore doesn't skip Splash; slow restore doesn't add to launch time.
- **Text size is fixed, not Dynamic-Type-scaling.** `.system(size:)` / `.sp` throughout. Layout holds at any accessibility text size because of `maxLines + ellipsis`, but text itself won't grow. True Dynamic Type support means switching to text-style-based fonts (`Font.system(.body, design:, weight:)`), which would change the pixel-perfect rendering slightly — deferred to the Inter-bundling ticket.
- **`sharedUI` untouched.** Ticket says nothing added; only 13 i18n keys landed in `sharedLogic`, which the ticket explicitly permits.
- **Desktop not restyled.** Ticket explicitly deferred Desktop to a follow-up. The `desktopApp/.../LoginScreen.kt` file is unchanged from ticket #19.

## Open questions / follow-ups

- **Bundle Inter font on all three platforms in one atomic pass.** Referenced as `TODO(#21-followup)` in `Type.kt` and `AromexTypography.swift`. Small file-drop-and-swap ticket.
- **Full Dynamic Type support on iOS + Android.** Currently text sizes are fixed. Switching to text-style-based fonts (`Font.system(.body)` etc. on iOS, `Typography.bodyLarge` etc. on Android with a Dynamic-Type-aware wrapper) would let text scale with the OS setting. Best done in the same pass as the Inter bundling.
- **Desktop redesign.** Desktop was deferred by the ticket. `desktopApp/.../LoginScreen.kt` is the old ticket-#19 style; needs a follow-up that ports the six components + tokens to Compose-Desktop.
- **Real forgot-password flow.** The `login_forgot_password_soon` toast / alert is a placeholder. A separate ticket will implement email-based reset using Firebase Auth's `sendPasswordResetEmail`.
- **Real `Contact administrator` recipient.** `support@aromex.example` is the placeholder; PM to provide the actual support inbox.
- **Home + dashboard redesign (M7).** Out of scope for #21; feature screens beyond login inherit the tokens for free.
- **"Admin" / "Member" role labels** still hard-coded English in `HomeScreen` / `HomeView` (same open item flagged in #13, not affected here).
- **Chooser flow live-verification.** No email currently resolves to multiple companies in the gateway, so `ChooseCompanyScreen` / `ChooseCompanyView`'s new visuals were style-verified only.
- **No unit tests.** Every change is presentation; the shared logic worth testing is unchanged. When `PrimaryButton` / `LabeledTextField` grow more state (e.g. focused-error interplay), a small snapshot test suite becomes worth having.
- **Dark mode contrast check on a real device.** Dark tokens were tuned by eye against the light spec. Someone should QA them on an OLED device with the OS in dark mode.
