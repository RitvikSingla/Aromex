# firebase/

Per-client Firebase project: the data shapes every client gets, the security
rules guarding them, and the script that stands up a fresh project end-to-end.

PRD references: §5 (architecture), §7 (auth + permissions), §8 (onboarding).

## What's here

| File | Why |
|---|---|
| [`SCHEMA.md`](./SCHEMA.md) | Canonical Firestore document shapes (`users/{uid}`, `companySettings/profile`, `invites/{inviteId}`). Source of truth. |
| [`PERMISSIONS.md`](./PERMISSIONS.md) | The 11 feature scopes + `userMgmt` boolean. What `manage`/`view`/`none` mean. |
| `firestore.rules` | Security rules. Helpers for `isAdmin()`, `isSelf()`, `belongsToThisCompany()`, `hasPermission(feature, level)`. Inventory (M4): `products`/`serials`/`attributes`/`imeiIndex` gated on `inventory`. |
| `firestore.indexes.json` | Composite indexes for the inventory queries (serials by SKU/status/location, attribute vocab lists). |
| `tests/inventory.rules.test.ts` | Emulator rules tests (`@firebase/rules-unit-testing`). Run with `npm run test:rules`. |
| `firebase.json`, `.firebaserc` | Firebase CLI config. Default project is `aromex-june-2026`. |
| `scripts/setup-project.ts` | Admin SDK script that deploys rules + creates the first admin user. |
| `scripts/types.ts` | TS types matching `SCHEMA.md`, shared by the setup script and (later) any tooling. |
| `secrets/` | (gitignored) service-account JSON keys per project. |

## Quickstart

```bash
cd firebase
npm install

# 1. Place the service-account key for the target project at secrets/<projectId>-sa.json.
# 2. Log in to the Firebase CLI as a user with access to the project, OR set
#    GOOGLE_APPLICATION_CREDENTIALS to the SA key.
firebase login                       # (one-time)
firebase use aromex-june-2026        # or pass --project on each command

# 3. Dry-run the rules (does not change anything in the project):
npm run deploy:rules:dry

# 4. Bootstrap the project:
npm run setup -- \
  --projectId aromex-june-2026 \
  --serviceAccountKey ./secrets/aromex-june-2026-sa.json \
  --ownerEmail owner@aromex.test \
  --ownerPassword '<a strong password>' \
  --ownerDisplayName 'Aromex Owner' \
  --companyName 'Aromex Test Workspace' \
  --hlCompanyId c6dd3a85-62ae-4490-bb9f-e039d874cf74 \
  --country CA --currency CAD \
  --timezone America/Vancouver \
  --gstRate 0.05 --pstRate 0.07
```

The `setup` script does, in order:

1. Deploys `firestore.rules` to the project.
2. Creates the Auth user (or looks them up + updates if the email already exists).
3. Sets the custom claims `{ admin: true, hlCompanyId }` on the user.
4. Writes `users/{uid}` with `role: "admin"`, full permissions, `isActive: true`.
5. Writes `companySettings/profile` with the supplied settings.
6. Prints a summary and the credentials to relay to the client.

## Security model in one paragraph

Each client gets their own Firebase project (PRD §8). Within a project, the
gateway (`aromex-gateway`) is the only thing that talks to HL — and only after
verifying a Firebase ID token. Inside Firestore, **mobile clients (Android/iOS)
go through Security Rules**; **Desktop uses the Admin SDK and bypasses them**,
which is why the PRD designates **shared app logic** as the real enforcement
and these rules as the mobile backstop. The rules check three things on every
operation: the caller is signed in, the caller's `hlCompanyId` claim matches
this project's `companySettings/profile.hlCompanyId` (cross-project safety),
and either `role: "admin"` OR a per-feature `permissions` level grants access.

## Adding rules for a new feature collection

Every future ticket that adds a new top-level collection (e.g. `/sales/{id}`)
must add a `match` block to `firestore.rules`, **above** the catch-all deny.
Use the existing helpers:

```
match /sales/{saleId} {
  allow read:   if hasPermission('sales', 'view')   && belongsToThisCompany();
  allow write:  if hasPermission('sales', 'manage') && belongsToThisCompany();
}
```

The catch-all `match /{document=**}` is fail-closed — if you forget, the
feature breaks visibly rather than leaking data.

## Inventory (M4 · ticket #44)

Four collections — `products`, `serials`, `attributes`, `imeiIndex` — gated on
the `inventory` permission (view→read, manage→write). Add-stock is a **client**
Firestore transaction (no Cloud Function in M4); the exact steps are the contract
in [`docs/SCHEMA.md`](../docs/SCHEMA.md) Part 2, referenced from the rules header.
The rules only guarantee that atomic multi-doc write is permitted for `manage`
and denied for view/none. `imeiIndex` is the one collection where **delete is
allowed** (the in-stock IMEI guard is released on sold/archive).

**Composite indexes:** `firestore.indexes.json` declares the multi-field indexes
(serials by `productId`/`status`/`isActive`, by `status`/`isActive`, by
`location.attributeId`; attributes by `type`/`isActive` and `type`/`parentId`).
Single-field queries (`products.isActive`, `serials.imei`, and `imeiIndex` by doc
id) need **no** entry — Firestore auto-creates single-field indexes.

**No seed data:** attribute vocabularies (brands/carriers/…) start **empty** and
grow via add-new-inline (T1 `AddAttributeUseCase`). Do not pre-seed them.

**Verify the rules locally** (needs Java for the emulator):

```bash
npm run test:rules   # boots the Firestore emulator and runs tests/inventory.rules.test.ts
```

## Rotating the service-account key

Generate a new key in the Firebase console (Project Settings → Service
Accounts → Generate new private key), drop it into `secrets/`, delete the old
one. **Never commit `secrets/`.**
