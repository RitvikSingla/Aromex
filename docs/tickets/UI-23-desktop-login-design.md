# [UI] Desktop login — apply the approved Aromex design (Compose-Desktop, two-pane)

> **Platform: Desktop only** (JVM / Compose-Desktop). This is the follow-up promised in #21, which shipped
> the login redesign to Android + iOS.
> ⚠️ **Follow `/kmp-arch` (Desktop section)** — native **Compose-Desktop** UI + `StateFlow` VMs + manual DI
> over the shared logic. Nothing in `sharedUI`. This is a **visual re-skin**, not a logic change.
> Milestone: **UI & design system**.

## 📖 Story / Why
Login already works on Desktop (from #19) but is placeholder-styled. This applies the **approved Aromex
desktop design** and — like #21 did for mobile — **establishes the Compose-Desktop design system** (theme
tokens + reusable components derived from `docs/brand-kit.md`) that every future desktop screen reuses. It's
the first thing a prospective client sees on the desktop build, so it needs to look finished.

**Desktop has its own layout.** The mobile design is a stacked gradient-header + form. **Desktop is a
two-pane split**: a tall blue brand/marketing panel on the left, and a centered sign-in form on the right.
Same brand language (blue gradient, AROMEX mark, decorative circles, tokens), different composition.

**This is presentation only.** The existing desktop `LoginViewModel` / `LoginUiState` / flow and the four
backend repos (from #19) all stay exactly as they are — you're re-rendering the same state with new visuals.

## 🎨 The design
> **⚠️ Design assets are provided by the PM.** The **3 desktop Login-state screenshots (default / error /
> loading)** are the authoritative visual reference and are **NOT attached to this issue**. **When you run
> `/start-ticket`, ask the PM for the screenshots before you start building** — build against those +
> `docs/brand-kit.md` + the mobile design system from #21.

- **Design tokens / components:** `docs/brand-kit.md` (brand blue `#40548A`, gradient `#40548A → #283A63`,
  Inter, spacing, field/button specs). Mirror the token + component structure #21 established on Android/iOS
  so all three platforms match — build the desktop theme + components first, then the screen.

**Window & layout**
- A native desktop **window** titled **"Aromex"** (OS title bar / traffic lights). Content fills the window
  edge-to-edge inside the chrome.
- **Two panes, side by side**, full height:
  - **Left brand panel (~38–40% width)** — vertical **`#40548A → #283A63` gradient** with the same faint
    large **decorative circle outlines** as mobile (top-right + lower-left):
    - **top-left:** the **AROMEX mark** (rounded square + "A"/triangle monogram) + **"AROMEX"** wordmark
      (white, uppercase, +2% tracking).
    - **lower block:** small uppercase eyebrow **"POINT OF SALE"** (white ~60%, tracked) → large headline
      **"Built for phone retailers"** (white, ~40 Bold, wraps to 3 lines) → body paragraph *"Process sales,
      manage unit inventory, track IMEI numbers, and close out your cashier — purpose-built for mobile phone
      distribution."* (white ~70%, ~15).
    - **bottom-left:** a translucent **pill** with a green status dot + **"A Humble Solutions Product"**.
  - **Right form pane (~60–62% width, `#F5F6FA`)** — a single **centered column (~360–400 wide)**, vertically
    centered:
    - **"Welcome back"** (28 Bold) + **"Sign in to your Aromex account"** (secondary, 14).
    - `EMAIL ADDRESS` uppercase label → email field (placeholder `you@company.com`).
    - `PASSWORD` label → password field with a trailing **eye toggle** (placeholder `Enter your password`).
    - **"Forgot password?"** link, right-aligned, `#40548A`.
    - full-width primary **"Sign in"** button (`#40548A`).
    - centered helper line: **"Don't have access? Contact your administrator"** (the CTA is a **bold inline
      link**, not a bordered button — this differs from mobile).
    - centered tertiary footer: **"v2.4.1 · desktop"** (app version + platform tag).
- **Responsive (collapsing left panel).** The two-pane split is for normal/large windows. **Below a width
  breakpoint (~800 dp) the left brand panel collapses (hides)** and the **form takes the full width**, staying
  centered — so the app stays usable on a small window. (Details in 🖼️ UI standards.)

**Three states (build all three):**
- **Default** — empty fields with placeholders; Sign in filled blue.
- **Error** — fields filled (`sales@aromex.ph` / dots); the `PASSWORD` **label + field border turn red
  (`#D64545`)**; a red **"Incorrect email or password."** with a small error icon below the field. (Drive off
  the existing `LoginUiState.error`.)
- **Loading** — fields disabled/muted; the button shows a **white spinner + "Signing in…"** (stays filled
  blue). (Drive off `isSubmitting`.)

## 🖼️ UI standards (Definition of Done — Compose-Desktop)
Native Compose-Desktop UI; prefer native components (Material 3 for desktop, native `Window`). Where the
design needs a custom composable (the brand panel, the mark, the eye toggle), that's fine — **flag it to the
PM and proceed**.

- **Match the screenshots exactly** — the two-pane split, spacing, sizing, color, type, hierarchy, and all
  three states, using the `docs/brand-kit.md` tokens. Don't approximate or "improve" the design.
- **Light + dark.** Build the theme with **both** token sets (brand-kit dark: bg `#12151C`, surface
  `#1B1F2A`, brand → `#7E92C9`) and follow the OS appearance; nothing hardcoded that breaks in dark. The
  screenshots are the light spec — for dark, use the brand-kit dark tokens (ask the PM if a specific dark
  design is wanted).
- **Resizable + responsive (with a collapsing left panel).** The window is **resizable**. **Above a width
  breakpoint (~800 dp)** it's the **two-pane split** — the left panel keeps its proportion / a min width, the
  form stays a centered ~360–400 column. **Below the breakpoint the left brand panel collapses (hides) and the
  form takes the full width**, staying centered and readable, so the app is usable on a small window. Minimum
  window size ~**420 × 600** (form-only). Nothing clips or overlaps at any size.
- **Keyboard & pointer (desktop-native).** **Tab** moves email → password → Sign in (logical focus order);
  **Enter** in a field submits when valid; visible **focus rings**; **hover** states on the button + links
  with a **pointer cursor**. The eye toggle is keyboard-reachable.
- **Ellipsis.** A long email / any overflowing label **ellipsizes (`…`)** — never clips or breaks the layout.
- **States.** Default / error / loading as specced; interactive controls **disabled** while `isSubmitting`
  (the Sign-in action is click-guarded even while it stays visually filled).
- **Accessibility.** Field labels + the eye toggle carry descriptions; respect the OS text-scaling / contrast;
  brand-blue-on-white and white-on-blue meet **WCAG AA**.
- **Strings** come from the shared i18n dictionary — **no hardcoded user-facing text** (see New strings).
- **Verify on the range:** minimum window size, **around the collapse breakpoint (both sides)**, and a large
  maximized window; **light + dark**; all three states; keyboard-only navigation; and a very long email.

## ✅ Scope
- [ ] Build the **desktop theme + reusable components** from `docs/brand-kit.md`, mirroring the token +
      component structure from #21 (light **and** dark): `desktopApp/ui/theme` (Color/Type/Dimensions/Theme)
      and `desktopApp/ui/components` — at minimum `AromexMark`, `AromexBrandPanel` (the left gradient panel),
      `PrimaryButton`, `LabeledTextField` (eye toggle + error variant). Reused by every future desktop screen.
- [ ] Rebuild **desktop Login** as the **two-pane layout** above, all three states, wired to the existing
      `LoginUiState` — no changes to the login logic/flow.
- [ ] Set the **window title to "Aromex"**, resizable; implement the **responsive collapse** — two-pane above
      the ~800 dp width breakpoint, **left panel hidden + full-width centered form below it** — with the
      minimum size above.
- [ ] Restyle **Splash** to the same blue theme (mark + gradient + decorative circles) for a continuous brand
      treatment into Login.
- [ ] Restyle **ChooseCompany** with the new tokens (keep it functional; light touch).
- [ ] Add the **new desktop i18n strings** (see below) to `sharedLogic` (i18n keys only — permitted).

## 🆕 New strings & affordances
- **New i18n keys** for the left panel + footer: eyebrow "POINT OF SALE", headline "Built for phone
  retailers", the body paragraph, the "A Humble Solutions Product" badge, the "Don't have access?" helper, and
  the "· desktop" version tag. Follow #21's `login_*` key convention.
- **"Forgot password?"** — style the link, but **password reset is a separate deferred ticket.** Wire it to a
  no-op / "coming soon" (toast/dialog), don't build reset here.
- **"Contact your administrator"** — a `mailto:` composer (reuse the same placeholder recipient as mobile;
  PM swaps when a real support inbox exists).
- **"v2.4.1 · desktop"** — show the real app version if one is available from the build, else a static
  placeholder + the "· desktop" tag.

## 🎯 Acceptance Criteria
- [ ] Desktop Login matches the screenshots — the **two-pane split** (left brand panel + centered form) and
      the **default / error / loading** states, using `docs/brand-kit.md` tokens.
- [ ] The **desktop theme + shared components** are defined and reused (not one-off styling), mirroring #21's
      token/component structure.
- [ ] Window is titled **"Aromex"**, **resizable**; the layout **reflows** — **two-pane above the ~800 dp width
      breakpoint, left panel collapsed + full-width centered form below it** — with no clipping or overlap,
      verified at the min size, at the breakpoint, and maximized.
- [ ] Works in **light and dark**; **keyboard navigation** (Tab order + Enter-to-submit) and **hover/focus**
      states work; long text **ellipsizes** (see 🖼️ UI standards).
- [ ] The **existing login flow is unchanged and still works** end-to-end (resolve → sign-in → Home);
      error/loading are driven off the existing `LoginUiState`; controls disabled while submitting.
- [ ] Splash + ChooseCompany adopt the new theme.
- [ ] Strictly `/kmp-arch`: Compose-Desktop UI, `StateFlow` VMs + manual DI, nothing added to `sharedUI`
      (i18n keys in `sharedLogic` are fine), no business logic in the UI.
- [ ] No secrets committed or in the handoff; builds + runs the desktop app on at least one OS.

## 🚫 Out of scope
- **Android / iOS** — already shipped in #21.
- **Home / dashboard** redesign (the real M7 dashboard).
- **Actual forgot-password functionality** (separate ticket) and any onboarding/other screens.
- **Inter font & full text-scaling** — tracked as the cross-platform `#21-followup`; keep the system
  sans-serif for now to stay consistent with Android/iOS until that lands.

## 🔗 Dependencies
- Builds on the **desktop login (#19)** and `docs/brand-kit.md`. No backend changes.
- **Best started after #21 (PR #22) merges** so the desktop theme mirrors the finalized Android/iOS token +
  component structure. If it lands first, follow `docs/brand-kit.md` directly.

## 📚 References
- **#21** (Android/iOS login refresh) — the design system + components to mirror
  (`handoffs/ticket-21.md`; Android `androidApp/.../ui/theme` + `ui/components` are the closest reference).
- `docs/brand-kit.md` — tokens + component specs. **3 desktop screenshots — provided by the PM (ask for them
  at `/start-ticket`).**
- Desktop code to restyle: `desktopApp/.../ui/login/LoginScreen.kt`, `ui/login/ChooseCompanyScreen.kt`,
  `ui/splash/SplashScreen.kt`, `ui/theme/*`, `navigation/AromexApp.kt`. `/kmp-arch` (Desktop section).

## 🤖 Kickoff prompt
```
/start-ticket <this-issue-number>
```
