# [Desktop-prep] Gateway: `/firestore-token` — broker a short-lived Firestore access token

> **Component: the `aromex-gateway` service** (Node + TS + Fastify), *not* the app. Code lands in the
> `Humble-Coders/aromex-gateway` repo.
> Milestone: **iOS & Desktop parity**. This unblocks the Desktop app (which has no JVM Firebase client
> SDK and must reach Firestore via `google-cloud-firestore` with a brokered credential).

## 📖 Story / Why
Desktop (Compose-Desktop / JVM) can't use a Firebase client SDK, so — unlike mobile — it can't read
Firestore (or get **real-time listeners**) with just the user's ID token. The only JVM path with
listeners is `google-cloud-firestore`, which needs **Google credentials**. We do **not** put the
service-account (SA) key on the desktop; instead the gateway — which already holds each company's SA key
— mints a **short-lived Firestore access token** and brokers it, exactly like it already brokers the HL
token. This ticket adds that one endpoint.

## 🧭 Context — mirror the existing `/hl-token`
The gateway already does the hard part. Reuse it:
- `src/routes/hlToken.ts` — the **auth-verification skeleton to copy**: read `Authorization: Bearer
  <Firebase ID token>` → `unsafeDecodeProjectId` → `findCompanyByProjectId` → `verifyIdToken(company, …)`
  → `isUserActive(company, uid)` (403 if not). The new route's first ~20 lines are identical.
- `src/services/firebaseVerifier.ts` — already loads the per-company SA key (`company.serviceAccountKeyPath`)
  and caches a `firebase-admin` `App` per company. The new token is minted from that **same SA**.

**What's new:** after the identical auth checks, instead of `mintHlToken`, mint a **Google OAuth2 access
token scoped to Firestore** from the company's SA and return it.

**Security decision baked in (Approach A):** this token authenticates as **admin — it bypasses Firestore
security rules.** That's accepted for Desktop (a trusted environment; permissions enforced in shared app
logic — already the PRD's stance). To limit blast radius, the token **must be scoped to Firestore only**
(`https://www.googleapis.com/auth/datastore`), **never** `cloud-platform`. It's also short-lived (~1h)
and company-scoped (the SA is per-company).

## 🏛️ What to build
- **New route** `src/routes/firestoreToken.ts` → `POST /firestore-token`:
  1. Copy the auth block from `hlToken.ts` (bearer → projectId → company → `verifyIdToken` → `isUserActive`;
     same 401/403 responses).
  2. Mint the token via a new service (below).
  3. Return `{ firestoreToken: string, projectId: string, expiresIn: number }`.
- **New service** `src/services/firestoreTokenService.ts`:
  - `mintFirestoreToken(company): Promise<{ firestoreToken, projectId, expiresIn }>`.
  - Use `google-auth-library`'s `JWT` (transitive dep of `firebase-admin`; add it as a **direct**
    dependency for a clean import) built from the SA key with `scopes:
    ['https://www.googleapis.com/auth/datastore']`; call `getAccessToken()`; compute `expiresIn` from the
    token's expiry. **Cache per company** and refresh ~60s before expiry (mirror `hlClient`'s cache), so we
    don't re-mint on every request.
  - Read the SA key the same way `firebaseVerifier` does (`company.serviceAccountKeyPath`).
- **Register the route** in `src/server.ts` alongside the others.
- **Docs:** add the endpoint to `docs/API.md` (request/response + error codes), mirroring the `/hl-token`
  section.

## 🔑 Access & prerequisites
> Provided per ticket via secure channel. Don't commit anything.
- SSH/deploy access to the gateway droplet (same as prior gateway tickets) to deploy + smoke-test.
- A working test login whose company has an SA key on the gateway (e.g. `aromextest` / `gtr`) — password
  via one-time-secret — to mint a real Firebase ID token for the live check.
- No new secrets: reuses the per-company SA keys already on the gateway.

## ✅ Scope / What to build
- [ ] `POST /firestore-token` route reusing the `/hl-token` auth block (same 401/403 behavior).
- [ ] `mintFirestoreToken` service — SA → **datastore-scoped**, short-lived OAuth access token, per-company
      cached + refreshed.
- [ ] `google-auth-library` added as a direct dependency.
- [ ] Route registered; `docs/API.md` updated.
- [ ] Tests (vitest + Fastify inject): missing/invalid token → 401; inactive user → 403; valid → 200 with
      `{ firestoreToken, projectId, expiresIn }`; **assert the response never leaks the SA key / private key**;
      cache reuse (two calls → one mint).

## 🎯 Acceptance Criteria
- [ ] `POST /firestore-token` returns **401** for missing/invalid Firebase ID token, **403** for an inactive
      user — identical to `/hl-token`.
- [ ] For a valid, active user it returns `{ firestoreToken, projectId, expiresIn }`; the `firestoreToken`
      is a Google access token **scoped to Firestore only** (`datastore`, not `cloud-platform`) and works
      against that company's Firestore (verified: a `google-cloud-firestore` read/listen with it succeeds).
- [ ] The **SA private key never appears** in any response or log (asserted in tests, like `/hl-token`).
- [ ] Token is **cached per company** and refreshed before expiry (two rapid calls mint at most once).
- [ ] No gateway secret committed; deploy steps documented; existing endpoints unchanged.

## 🚫 Out of scope
- The **Desktop app** itself (separate ticket — consumes this endpoint).
- Any change to `/resolve-company`, `/hl-token`, or `/admin/*`.
- Per-user / rules-grade enforcement of the Firestore token (that's Approach B — a gateway *proxy* — which
  we explicitly chose **not** to build now; permissions are enforced in app logic on Desktop).

## 🔗 Dependencies
- Reuses `firebaseVerifier` (per-company SA + `verifyIdToken` + `isUserActive`) and the `/hl-token` route
  shape — both already in `main`. No app changes.

## 📚 References
- `src/routes/hlToken.ts` (auth skeleton to copy), `src/services/firebaseVerifier.ts` (per-company SA/app),
  `src/services/hlClient.ts` (token-cache pattern to mirror).
- `docs/API.md` (endpoint doc format), `docs/DEPLOY.md` (deploy/smoke-test).
- Aromex-KMP `CLAUDE.md` — "Two backends", Desktop-bypasses-rules / enforce-in-app-logic decision.

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
