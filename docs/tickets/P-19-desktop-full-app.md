# [Parity] Desktop — full app: email-discovery login + HL balances (Compose-Desktop, per /kmp-arch)

> **Platform: Desktop (JVM / Compose-Desktop).** The final platform for parity — brings the entire app
> (login + session + HL balances) to Windows/macOS/Linux.
> ⚠️ **Follow the `/kmp-arch` skill strictly** — native **Compose-Desktop** UI + `StateFlow` ViewModels +
> JVM repo impls over the **shared Kotlin logic**. Do not deviate.
> Milestone: **iOS & Desktop parity**.
>
> **Heads-up: this is the biggest of the three platform tickets.** Unlike Android/iOS, the JVM has **no
> Firebase client SDK**, so Auth and Firestore are reimplemented over REST / `google-cloud-firestore`.

## 📖 Story / Why
The app ships on Android + iOS. This ports it to Desktop, reusing the shared logic. Desktop is special:
- **No Firebase client SDK on the JVM.** So **Auth** goes through the **REST API**, and **Firestore** is
  reached via **`google-cloud-firestore`** — authenticated with the short-lived token the gateway now
  brokers at **`POST /firestore-token`** (from #15). The service-account key **never touches the desktop**,
  real-time **listeners work**, and — because that token bypasses Firestore rules — **permissions are
  enforced in shared app logic** (the PRD's Desktop stance).

## 🧭 Context — reuse shared logic; the Desktop-specific plumbing
Shared `model` / `repository` interfaces / `usecase` are consumed **as-is** (JVM target). **Expect zero
shared-logic changes** (the layer is already JVM-clean; #13's `@Throws` is a no-op on JVM). Mirror the
Android (M1-09, M2-11) + iOS (#13) flow: resolve → sign-in → load `users/{uid}` + `companySettings` →
Home; then `/hl-token` → balance-sheet → grouped balances.

The four backends, Desktop-style:
1. **Directory** — gateway `POST /resolve-company` over a JVM HTTP client.
2. **Firebase Auth (REST, the biggest new piece)** — `identitytoolkit` `signInWithPassword` for sign-in;
   `securetoken.googleapis.com` to refresh the ID token. Desktop **manages its own session**: persist the
   refresh token locally, refresh on demand. This backs the shared `AuthRepository`
   (`signIn`/`signOut`/`currentUid`/`idToken`).
3. **Firestore** — `google-cloud-firestore` authenticated with a **gateway-brokered `/firestore-token`**
   (datastore-scoped OAuth). Backs the shared `UserRepository` (reads `users/{uid}` +
   `companySettings/profile`). A small Desktop-internal broker fetches + caches that token (Firebase ID
   token → `/firestore-token` → cache + refresh), analogous to `HlTokenRepository`.
4. **HL** — same gateway `/hl-token` + `GET /api/v1/reports/balance-sheet?date=2999-12-31`, over JVM HTTP.

## 🏛️ Architecture — follow `/kmp-arch` exactly (Desktop section)
- **`desktopApp/repository`** — JVM impls of the shared interfaces:
  - directory: HTTP → `/resolve-company`
  - `AuthRepository` (REST): `signInWithPassword` + `securetoken` refresh; persists the refresh token;
    `currentUid` = "restore a valid session from the stored refresh token"; `idToken(forceRefresh)`.
  - Firestore-token broker (Desktop-internal): Firebase ID token → `/firestore-token` → cached
    datastore-scoped token + refresh.
  - `UserRepository` (`google-cloud-firestore` with the brokered token): read `users/{uid}` +
    `companySettings/profile`.
  - `HlTokenProvider` (`/hl-token`, cache + refresh) + `LedgerRepository` (balance-sheet, 401-retry,
    money as `String`).
  - `PreferencesRepository` (`java.util.prefs` or a small local file).
- **`desktopApp/viewmodel`** — `StateFlow` ViewModels + **manual DI** (each builds its own chain), driving
  the shared use cases.
- **`desktopApp/ui`** — Compose-Desktop screens: Splash, Login, ChooseCompany, Home (balances panel).
- **`desktopApp/navigation`** — Compose-Desktop navigation.

## 🔑 Access & prerequisites
> PM provides secrets via secure channel. Don't commit anything.
- A JDK + the `desktopApp` module (Compose-Desktop).
- **Test login:** `ansh.bajaj2611@gmail.com` (company `aromextest` → `aromex-june-2026`) — password via
  one-time-secret. *(Empty HL company → Home shows the empty-balances state, same bar as Android/iOS.)*
- **The gateway must be deployed with `/firestore-token` live (#15)** — confirm before building the
  Firestore part. Base URLs (public): gateway `http://68.183.86.89/gateway`, HL
  `http://68.183.86.89/api-server`; Firebase Auth REST at `identitytoolkit.googleapis.com` /
  `securetoken.googleapis.com` using the company's public `apiKey` from `/resolve-company`.
- JVM deps: `google-cloud-firestore`, `google-auth-library` (build `OAuth2Credentials` from the brokered
  token), and a JVM HTTP client (OkHttp / `java.net.http`).
- HTTP-only for the gateway/HL host (Phase B pending) — JVM clients allow `http` by default; no ATS/network
  config needed, but keep the same `Phase B` TODO to switch to HTTPS.

## ✅ Scope / What to build
- [ ] **Firebase Auth REST** impl of `AuthRepository` — sign-in, ID-token refresh, refresh-token persistence.
- [ ] **Firestore via `google-cloud-firestore` + the `/firestore-token` broker** — impl of `UserRepository`;
      SA key never on device; broker caches + refreshes the datastore token.
- [ ] Directory + HL repos over JVM HTTP (mirror Android/iOS).
- [ ] Compose-Desktop UI: Splash / Login / ChooseCompany / Home (balances: loading / error+retry / empty /
      grouped Cash·Bank·Credit-Card·Other) — feature parity.
- [ ] `StateFlow` ViewModels + manual DI driving the shared use cases.
- [ ] **Session persistence** — restore on launch from the stored refresh token (`RestoreSessionUseCase`);
      sign out clears it.
- [ ] HL balances (brokered token, balance-sheet, money as strings, grouped display).
- [ ] **Demonstrate a real-time Firestore listener works** on Desktop (the whole reason for the
      `google-cloud-firestore` path) — even a minimal proof.

## 🎯 Acceptance Criteria
- [ ] Strictly follows `/kmp-arch`: Compose-Desktop UI, `StateFlow` VMs + manual DI, JVM repo impls of the
      shared interfaces; **no shared-logic changes** (or minimal + justified); no `expect`/`actual`;
      feature parity with Android/iOS.
- [ ] Login works end-to-end on Desktop (resolve → REST sign-in → Firestore `users/{uid}` via the brokered
      token → Home), **verified live** with the test login.
- [ ] Already-signed-in user **restores on relaunch** (via the stored refresh token); **sign out** → Login.
- [ ] Home reads HL balances via the brokered HL token; money rendered from **strings**.
- [ ] Firestore is reached via `google-cloud-firestore` + the gateway `/firestore-token`
      (**datastore-scoped**); **the SA key is never on the desktop**; a **real-time listener** is shown to work.
- [ ] Permissions captured into the session (rules are bypassed on Desktop → enforcement is in app logic;
      no feature-gating required yet, but the session carries them).
- [ ] No secrets committed; refresh token + brokered tokens stored **locally only**, never logged in full.
- [ ] Builds + runs on the JVM (Compose-Desktop) on at least one OS.

## 🚫 Out of scope
- Feature screens beyond login + balances (same deferrals as Android/iOS).
- **HL writes, user management, permission-gating UI.**
- OS-native secure storage for the refresh token — use a reasonable local store; hardening is a follow-up.

## 🔗 Dependencies
- Builds on the merged shared logic (M1-09, M2-11, #13) and the **deployed** gateway **`/firestore-token`
  (#15)** + `/hl-token`. **Confirm `/firestore-token` is live on the droplet before the Firestore part.**

## 📚 References
- **iOS (#13)** + **Android (M1-09, M2-11)** — the flow + screens to mirror (`handoffs/ticket-9,11,13.md`).
- **`/kmp-arch`** (Desktop section — the authority). `CLAUDE.md` — two backends, **Desktop-bypasses-rules /
  enforce-in-app-logic**, money-as-strings.
- `aromex-gateway/docs/API.md` — `/resolve-company`, `/hl-token`, **`/firestore-token`**.
- Firebase Auth REST — `identitytoolkit` `signInWithPassword`, `securetoken` refresh.

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
