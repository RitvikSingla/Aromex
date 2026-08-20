# [Parity] iOS — full app: email-discovery login + HL balances (SwiftUI, per /kmp-arch)

> **Platform: iOS.** One ticket that brings the **entire current app** to iOS — everything Android does
> today (login + HL balances).
> ⚠️ **Follow the `/kmp-arch` skill strictly** — native **SwiftUI** UI + `@MainActor ObservableObject`
> ViewModels + Swift repo impls over the **shared Kotlin logic**. It's the authority; do not deviate.
> Milestone: **iOS & Desktop parity**.

## 📖 Story / Why
The whole app — email-discovery **login** (M1-09) and **HL account balances** (M2-11) — works on Android.
The **shared logic** (models, repository *interfaces*, use cases) is already platform-neutral and merged.
This ticket builds the **iOS platform layer** so the same features run on iPhone/iPad. **It's a port, not
a redesign** — the flow and decisions are proven on Android; iOS mirrors them with native SDKs.

## 🧭 Context — reuse what exists, don't rebuild
`sharedLogic/commonMain` already provides (all consumed as-is from Swift):
- **Models:** `FirebaseClientConfig`, `ResolvedCompany`, `UserSession` (incl. `currency`), `Permissions`,
  `AuthenticatedSession`, `LedgerAccount` (money as `String`), `LoginError`/`LoginException`,
  `HlError`/`HlException`.
- **Repository interfaces:** `CompanyDirectoryRepository`, `AuthRepository` (incl. `idToken`),
  `UserRepository`, `PreferencesRepository`, `LedgerRepository`, `HlTokenProvider`.
- **Use cases:** `LoginUseCase`, `RestoreSessionUseCase`, `LogoutUseCase`, `GetAccountBalancesUseCase`.
- **i18n:** `Strings` + `EnglishStrings`.

iOS implements the **platform side** of these with native SDKs. **Expect zero shared-logic changes** — if a
Swift-interop tweak is truly unavoidable, keep it minimal and call it out. Kotlin `suspend` funcs surface to
Swift as `async` (await them from `Task {}`).

**Mirror the Android reference** (same flow, same screens, same error handling):
1. email → gateway `POST /resolve-company` → `firebaseConfig`
2. **dynamic per-company Firebase init** → Auth sign-in → read `users/{uid}` (+ `companySettings/profile`)
   → build session → Home
3. Home: broker `POST /hl-token` → `GET /api/v1/reports/balance-sheet?date=2999-12-31` → show balances

## 🏛️ Architecture — follow `/kmp-arch` exactly (iOS section)
- **`iosApp/repository`** — Swift impls of the shared interfaces using native SDKs:
  - directory: `URLSession` → `/resolve-company`
  - auth: **Firebase iOS SDK**; **dynamic named app** per company —
    `FirebaseApp.configure(name: projectId, options: FirebaseOptions(...))` from the gateway config; then
    `Auth.auth(app:)`; `idToken` via `user.getIDToken(forcingRefresh:)`
  - user: `Firestore.firestore(app:)` from the named app → read `users/{uid}` + `companySettings/profile`
  - prefs: `UserDefaults` (last email + companyId)
  - HL token: `URLSession` → gateway `/hl-token`, cache + refresh (30s margin) + `invalidate()` on 401
  - HL ledger: `URLSession` → HL balance-sheet, single 401-retry, parse envelope, money kept as `String`
- **`iosApp/viewmodel`** — `@MainActor ObservableObject` + `@Published` + `Task {}`; **manual DI** (each VM
  builds its own dependency chain in `init`), driving the shared use cases.
- **`iosApp/ui`** — SwiftUI screens: Splash, Login, ChooseCompany, Home (balances). `@StateObject` VMs.
- **`iosApp/navigation`** — SwiftUI `NavigationStack`; `Screen` enum for the auth flow (per `/kmp-arch`).

## 🔑 Access & prerequisites
> PM provides secrets via secure channel. Don't commit anything.
- **Mac + Xcode** to build/run `iosApp`.
- **Firebase iOS SDK** dependency (SPM or CocoaPods — the repo already uses the `kotlinCocoapods` plugin,
  so CocoaPods fits; SPM is also fine). Adding this is part of the ticket.
- **Test login:** `ansh.bajaj2611@gmail.com` (company `aromextest` → `aromex-june-2026`) — password via
  one-time-secret. *(Its HL company is empty, so Home shows the empty-balances state — the read succeeding
  is the bar, same as Android M2-11.)*
- **Base URLs (public, in this ticket):** gateway `http://68.183.86.89/gateway`, HL
  `http://68.183.86.89/api-server`. HTTP-only → needs a dev-only **ATS cleartext exception** (below).

## ✅ Scope / What to build
- [ ] Swift impls of **all** shared repo interfaces (directory, auth, user, prefs, HL token, HL ledger)
      using native iOS SDKs.
- [ ] **Dynamic per-company Firebase init** on iOS (named secondary `FirebaseApp` from gateway config) —
      no single-tenant `GoogleService-Info.plist` baked in.
- [ ] SwiftUI screens with feature parity: **Splash** (session restore), **Login** (email + password,
      loading, distinct errors), **ChooseCompany** (multi-company), **Home** (header + balances panel:
      loading / error+retry / empty / grouped Cash·Bank·Credit-Card·Other list + sign out).
- [ ] `@MainActor ObservableObject` ViewModels + manual DI driving the shared use cases.
- [ ] **Session persistence** — `RestoreSessionUseCase` on launch skips login if still signed in; sign out
      returns to login.
- [ ] **HL balances** — brokered token (cache + refresh on expiry/401), balance-sheet read, money as
      strings, grouped display.
- [ ] **Info.plist ATS exception** for the gateway/HL host (dev-only), with a removal `TODO` for HTTPS
      (Phase B).

## 🎯 Acceptance Criteria
- [ ] Strictly follows `/kmp-arch`: SwiftUI UI, `@MainActor ObservableObject` VMs + manual DI, Swift repo
      impls of the shared interfaces; **no shared-logic changes** (or minimal + justified); no
      `expect`/`actual`; **feature parity with Android**.
- [ ] Login works end-to-end on iOS (resolve → sign-in → user doc → Home), verified live with the test
      login; multi-company chooser, inactive-user block, and distinct error messages all present.
- [ ] Already-signed-in user **skips login** on launch; **sign out** returns to login.
- [ ] Home brokers an HL token via the gateway (cached + re-brokered on expiry/401) and shows balances
      (empty state for the empty test company; grouped list otherwise); money rendered from **strings**.
- [ ] Firebase is initialized **dynamically per company** (named app); no single-tenant config committed.
- [ ] No secrets committed; ATS cleartext limited to the gateway/HL host (dev-only) with a removal `TODO`.
- [ ] Builds + runs on an iOS simulator/device.

## 🚫 Out of scope
- **Desktop** — separate ticket, pending the SA-key/rules decision (being sorted now).
- **User management, permission gating, HL writes** — same deferrals as Android.
- Any **redesign** — match Android's behavior/screens; polish is later.

## 🔗 Dependencies
- Builds on the **already-merged shared logic** from M1-09 + M2-11 (Android). **No backend changes** — the
  gateway + HL are live and unchanged.

## 📚 References
- **Android reference to mirror:** `androidApp/` (login M1-09, HL client M2-11) + `handoffs/ticket-9.md`,
  `handoffs/ticket-11.md`.
- **`/kmp-arch` skill** — the iOS-section authority. `CLAUDE.md` — two backends, money-as-strings, auth.
- `docs/PRD.md` §7 (auth), §6 (HL). Humble Ledger `MOBILE_ADMIN_API.md` (reads).

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
