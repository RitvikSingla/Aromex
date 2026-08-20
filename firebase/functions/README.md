# @aromex/functions — entity → Humble Ledger sync (M3 backend spine)

Per-client Cloud Functions that turn a saved party into a Humble Ledger customer.

## Functions
- **`onEntityWrite`** — Firestore trigger on `entities/{id}`. When a doc is written
  `syncStatus=PENDING`, it brokers an HL token from the gateway, creates the HL customer
  (idempotent on `externalId`), posts the optional opening balance, and patches the doc to
  `SYNCED` (or `FAILED` + rethrow to retry). Profile edits on a synced entity push
  name/email/phone to HL. Loop-safe (its own SYNCED write is a no-op).
- **`reconcileEntities`** — scheduled every 5 min; re-syncs any entity stuck `PENDING`/`FAILED`
  (durability backstop, PRD §6.3). Idempotent.

## Required config (per environment)
Set before deploy:

```bash
# public params
firebase functions:config unavailable in gen-2 — use params/.env or --set-env-vars:
#   GATEWAY_BASE_URL   e.g. https://gateway.example.com
#   HL_BASE_URL        e.g. https://ledger.humblesolutions.in

# secret (Secret Manager)
firebase functions:secrets:set GATEWAY_ADMIN_TOKEN   # the gateway's ADMIN_API_TOKEN
```

The Firebase projectId is read from the runtime (`GCLOUD_PROJECT`) and sent to the gateway's
`POST /internal/hl-token` — no company id needs configuring here.

## Develop
```bash
npm install
npm run build     # tsc → lib/
npm test          # vitest (pure-helper unit tests)
npm run deploy    # firebase deploy --only functions
```

Full behaviour (PENDING→SYNCED, idempotent retries) is verified end-to-end against a deployed
project + a running gateway — see the ticket's "verify by hand" steps.
