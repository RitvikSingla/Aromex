# [M1] Central Directory & Humble Ledger Gateway service

> Draft — will be created as a GitHub issue under the **M1 — Auth & Onboarding** milestone.

## 📖 Story / Why
Every Aromex client company has its **own Firebase project** and its **own company inside Humble
Ledger (HL)**. The app, running on a user's device, needs two things at login that it cannot safely
work out on its own:
1. **Which Firebase to connect to** — knowing only the user's email.
2. **A safe way to talk to HL** — without ever putting HL's credentials on the device.

This ticket builds the small **vendor-owned server** that provides both. It is the backbone of
multi-company login — without it, no client can log in.

## 🧭 Context
- A **standalone backend service we host**, *not* part of the mobile/desktop app.
- **Repo:** its own — **`Humble-Coders/aromex-gateway`**. **Stack:** Node + TypeScript (matches HL).
- **Hosting:** deploy to the **same server as Humble Ledger**, running as a **separate service**
  (its own process/port behind nginx).
- **Data store:** the Central Directory registry lives in **its own small database** (separate from
  HL's database) — provision a lightweight Postgres (or equivalent) for it.
- It talks to: each client's **Firebase** (verify a user's ID token) and **Humble Ledger** (obtain
  company-scoped access).
- Hard security rule (PRD §7): **HL credentials and service-account keys are NEVER returned before a
  user is authenticated, and never embedded in the app.**

## 🔑 Access & prerequisites
> The **PM provides these per ticket** through a secure channel — **ask the PM directly** before you
> start. Do not commit any of them.
- **SSH / deploy access** to the server that hosts Humble Ledger (to deploy the gateway alongside it).
- **Humble Ledger test access** — base URL + a test company login to develop the `/hl-token`
  brokering against.
- **A test client Firebase project** — its config + a test user, to verify Firebase ID-token validation.
- **The public hostname/path** the gateway will be served on (confirm with the PM — e.g. a path behind
  the existing nginx).

## ✅ Scope / What to build
- [ ] **New repo `aromex-gateway`** (Node + TypeScript) with its **own small database** for the registry.
- [ ] **Directory data model** — per company: `companyId`, display name, status, public Firebase
      config, and server-only secret *references* (service-account key, HL company id + credential),
      plus an **email → companyId** index for login discovery.
- [ ] **`POST /resolve-company`** — email → the matching company's **public Firebase config only**
      (no secrets, no company name); multi-company → minimal "choose your company" data; **rate-limited**.
- [ ] **`POST /hl-token`** — Firebase ID token → verify against the correct company's Firebase, confirm
      the user is active → return a **short-lived, company-scoped HL access token** (broker the HL
      credential server-side; never expose it).
- [ ] **Internal provisioning hooks** to create/update a company's directory entry (used by the
      onboarding runbook, M1-08).
- [ ] **Deploy** to the HL server as a separate service behind nginx; documented and repeatable;
      secrets via env/secure config.

## 🎯 Acceptance Criteria
- [ ] A known email to `/resolve-company` returns that company's Firebase config and **no secrets and
      no company name**.
- [ ] An unknown email returns a generic "not found" that does not reveal whether the email exists.
- [ ] An email mapped to 2+ companies returns the data needed for a company chooser.
- [ ] `/resolve-company` is rate-limited; rapid repeated calls are throttled.
- [ ] `/hl-token` returns 401 for an invalid/expired Firebase ID token, and for a valid token returns
      a short-lived HL token scoped to that user's company.
- [ ] No endpoint ever returns an HL credential or a service-account key to the client.
- [ ] The gateway runs as its **own service on the HL server** (separate process/port) and its
      directory data persists in its **own database**.
- [ ] Deploy steps are documented in the repo; no secret is committed.
- [ ] Tests cover: resolve (found / not-found / multi), token (valid / invalid), and rate-limiting.

## 🚫 Out of scope
- The app-side login UI (M1-05).
- Creating Firebase projects / HL companies (onboarding runbook, M1-08).
- Any inventory/accounting business logic.

## 🔗 Dependencies
- None hard-blocking. Coordinate with **M1-02** (Firebase template — config shape) and **M1-08**
  (runbook — how entries get created).

## 📚 References
- PRD `docs/PRD.md` §5 (architecture), §7 (auth), §8 (onboarding/billing).
- Humble Ledger auth/token endpoints — HL `MOBILE_ADMIN_API.md` (ask the PM for access).

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
