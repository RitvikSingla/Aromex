# [UI] Login screen — apply the approved Aromex design (Android + iOS)

> **Platforms: Android + iOS** (Desktop is a follow-up — do it after these land).
> ⚠️ **Follow `/kmp-arch`** — native UI per platform (Compose / SwiftUI), nothing in `sharedUI`, no logic
> in the UI. This is a **visual re-skin**, not a logic change.
> Milestone: **UI & design system**.

## 📖 Story / Why
Login works on all platforms but is placeholder-styled. This applies the **approved Aromex visual design**
to the auth entry so it's shippable, and — just as important — **establishes the design system** (theme +
reusable components from `docs/brand-kit.md`) that every future screen reuses. It's the first thing a
prospective client sees, so it needs to look finished.

**This is presentation only.** The existing `LoginViewModel` / `LoginUseCase` / flow / `LoginUiState`
(email, password, isSubmitting, error, candidates…) all stay exactly as they are — you're re-rendering the
same state with the new visuals.

## 🎨 The design
> **⚠️ Design assets are provided by the PM.** The **3 Login-state screenshots (default / error / loading)**
> are the authoritative visual reference and are **NOT attached to this issue**. **When you run
> `/start-ticket`, ask the PM for the screenshots before you start building** — build against those +
> `docs/brand-kit.md` + the Figma link.

- **Approved Figma:** https://www.figma.com/make/NS1KnzqJpEsXVGShudAaJK/Aromex (open in Figma to see it).
- **Design tokens / components:** `docs/brand-kit.md` (brand blue `#40548A`, Inter, spacing, the button /
  field / label specs). Build the theme + shared components from it first, then the screen.

**Login layout (mobile):**
1. **Blue gradient header card** (top ~30%, `#40548A → #283A63`, rounded bottom `16`, a faint large circle
   outline decoration top-right):
   - top-left: the **AROMEX mark** (rounded square + "A"/triangle monogram) + **"AROMEX"** wordmark (white,
     uppercase, +2% tracking).
   - **"Welcome back"** (white, 28 Bold).
   - **"Sign in to your account to continue"** (white ~70%, 14).
2. **Form** (on `#F5F6FA`):
   - `EMAIL ADDRESS` uppercase label → email field (placeholder `you@company.com`).
   - `PASSWORD` label → password field with a trailing **eye toggle** (placeholder `Enter your password`).
   - **"Forgot password?"** link, right-aligned, `#40548A`.
   - full-width primary **"Sign in"** button (`#40548A`).
   - a divider row with **"Need access?"** centered (tertiary).
   - full-width secondary **"Contact your administrator"** button (white / bordered).

**Three states (build all three):**
- **Default** — empty fields with placeholders.
- **Error** — fields filled; the `PASSWORD` **label + field border turn red (`#D64545`)**; a red
  `13px` message **"Incorrect email or password."** with a small error icon below the field. (Drive this
  off the existing `LoginUiState.error`.)
- **Loading** — fields disabled/muted; the button shows a **white spinner + "Signing in…"** (stays filled
  blue). (Drive off `isSubmitting`.)

## 🖼️ UI standards (Definition of Done — applies to this and every UI ticket)
Native UI per platform: **Android = Compose**, **iOS = SwiftUI**. Prefer native components; where the design
can't be done with one (e.g. the gradient header, the eye toggle), a minimal custom composable/view is fine —
**flag it to the PM and proceed**.

- **Match the screenshots exactly** — spacing, sizing, color, type, hierarchy, and all three states, using the
  `docs/brand-kit.md` tokens. Don't approximate or "improve" the design.
- **Light + dark.** Build the theme with **both** token sets (the brand kit defines dark: bg `#12151C`,
  surface `#1B1F2A`, brand → `#7E92C9`) and follow the system setting; nothing hardcoded that breaks in dark.
  The screenshots are the light spec — for the dark rendering use the brand-kit dark tokens (ask the PM if a
  specific dark design is wanted).
- **Edge-to-edge + safe areas.** Draw edge-to-edge; the **blue header gradient bleeds under the status bar /
  notch**, but the AROMEX mark, "Welcome back" and all form content stay inside the safe area. Respect the
  **Android bottom gesture / nav bar** and the iOS home indicator — the "Sign in" and secondary buttons are
  never under system UI.
- **Keyboard.** Email field → **email** keyboard, IME action **Next** (moves focus to password); password →
  **secure** entry, action **Done** (submits). **Keep the focused field + its error visible above the
  keyboard** (scroll / inset); tap-outside dismisses. Correct autofill hints (email / current-password); no
  autocorrect / autocapitalization on email or password.
- **Responsive.** Looks right on small → large phones and doesn't break in landscape; on short screens with the
  keyboard up, the form **scrolls** so nothing is clipped. (Desktop is a separate follow-up.)
- **Truncation.** A long email / any overflowing label **ellipsizes (`…`)** — never clips or breaks the layout.
- **States.** Default / error / loading as specced above; interactive controls are **disabled** while
  `isSubmitting`.
- **Accessibility.** Field labels + the **eye toggle** have content descriptions; respect **dynamic type /
  font scaling** (layout holds at the largest size); touch targets ≥ **48dp / 44pt**; brand-blue-on-white and
  white-on-blue meet **WCAG AA**.
- **Strings** come from string resources — **no hardcoded user-facing text**.
- **Verify on the range:** smallest + largest device, **light + dark**, largest font scale, all three states,
  and with the keyboard up.

## ✅ Scope
- [ ] Build the **theme + reusable components** per platform from `docs/brand-kit.md`: color/type/spacing
      tokens (light **and** dark), `PrimaryButton`, `SecondaryButton`, `LabeledTextField` (with eye toggle +
      error variant). These are reused by every future screen.
- [ ] Rebuild **Login** on **Android (Compose)** and **iOS (SwiftUI)** to match the design above, all three
      states, wired to the existing `LoginUiState` — no changes to the login logic/flow.
- [ ] Restyle **Splash** minimally to the same blue theme (logo + gradient) for consistency.
- [ ] Restyle **ChooseCompany** with the new tokens (keep it functional; light touch).

## 🆕 New affordances — note the deferrals
- **"Forgot password?"** — style the link, but **password reset is a separate deferred ticket.** For now
  wire it to a no-op / "coming soon" (don't build reset here).
- **"Contact your administrator"** — since there's no self-signup, this is a helpful CTA. A static info /
  `mailto:` placeholder is fine for now.

## 🎯 Acceptance Criteria
- [ ] Login matches the Figma design on **both Android and iOS**, including the **default / error / loading**
      states, using the `docs/brand-kit.md` tokens (Inter, `#40548A`, the field/button specs).
- [ ] The theme + shared components are defined per platform and reused (not one-off styling).
- [ ] **Edge-to-edge with correct insets:** the blue header bleeds under the status bar / notch; content and
      both buttons clear the Android nav bar / iOS home indicator; the keyboard never covers the focused field.
- [ ] **Keyboard handled:** email → Next → password → Done submits; focused field stays visible; controls
      disabled while submitting; correct keyboard + autofill types.
- [ ] Works in **light and dark**, across small/large phones and at the largest font scale, with correct
      **ellipsis** on long text (see the 🖼️ UI standards section).
- [ ] The **existing login flow is unchanged and still works** end-to-end (resolve → sign-in → Home);
      error/loading are driven off the existing `LoginUiState`.
- [ ] Splash + ChooseCompany adopt the new theme.
- [ ] Strictly `/kmp-arch`: native UI per platform, nothing added to `sharedUI`, no business logic in the UI.
- [ ] No secrets; builds + runs on an Android emulator and an iOS simulator.

## 🚫 Out of scope
- **Desktop** — a follow-up after these land.
- **Home / dashboard** redesign (that's the real M7 dashboard).
- **Actual forgot-password functionality** (separate ticket) and any onboarding/other screens.

## 🔗 Dependencies
- Builds on the merged login (M1-09) and `docs/brand-kit.md`. No backend changes.

## 📚 References
- `docs/brand-kit.md` (tokens + components), the approved Figma link above, and the **3 login screenshots —
  provided by the PM (ask for them at `/start-ticket`)**. Android: `androidApp/.../ui/login`; iOS:
  `iosApp/iosApp/ui`.

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
