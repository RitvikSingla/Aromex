# firebase/scripts

Operator scripts. Run from `firebase/` (one level up) via the `npm run`
entrypoints — that's how `firebase-tools` and `tsx` get resolved from
`firebase/node_modules/`.

## `setup-project.ts` — bootstrap a fresh client Firebase

Deploys `firestore.rules`, creates the first admin user, sets custom claims,
and writes `users/{uid}` + `companySettings/profile`. See
[../README.md](../README.md) for the quickstart.

### Flags

| flag | required | example |
|---|---|---|
| `--projectId` | yes | `aromex-june-2026` |
| `--serviceAccountKey` | yes | `./secrets/aromex-june-2026-sa.json` |
| `--ownerEmail` | yes | `owner@aromex.test` |
| `--ownerPassword` | yes | `<a strong password>` |
| `--ownerDisplayName` | yes | `'Aromex Owner'` |
| `--companyName` | yes | `'Aromex Test Workspace'` |
| `--hlCompanyId` | yes | the HL company UUID — must match what HL's JWTs carry |
| `--country` | yes | `CA` |
| `--currency` | yes | `CAD` |
| `--timezone` | yes | IANA zone the shop trades in, e.g. `America/Vancouver`. Validated here; invoice dates are formatted in it so an evening sale keeps its own calendar day (ticket #80). |
| `--legalName` | no | defaults to `--companyName` |
| `--gstRate` | no | e.g. `0.05` (5%). Omit / 0 = GST disabled. |
| `--pstRate` | no | e.g. `0.07`. Omit / 0 = PST disabled. |
| `--isHST` | no | flag — if present, the GST line is treated as HST |
| `--contactEmail`, `--contactPhone`, `--businessAddress` | no | optional |
| `--taxNumber` | no | GST/HST registration no. shown on invoice letterheads (ticket #76) |
| `--skipRulesDeploy` | no | skip the `firebase deploy --only firestore:rules` step (useful if you've just deployed rules manually) |

### Idempotency

Re-running with the same `--ownerEmail` is safe:

- If the Auth user already exists, the script updates their password and
  displayName instead of failing.
- Firestore writes use `merge: true`, so `createdAt` is preserved across
  re-runs (the first run sets it; subsequent runs leave it).
- Rules deploy is unconditional — re-deploying the same rules is harmless.

### Tax presets — copy-paste

| jurisdiction | flags |
|---|---|
| Canada — BC/SK/MB | `--gstRate 0.05 --pstRate 0.07` |
| Canada — ON/NS/NB/NL/PE (HST) | `--gstRate 0.13 --isHST` |
| Canada — AB (no PST) | `--gstRate 0.05` |
| India — GST | `--gstRate 0.18` |
| None | (omit both rates) |
