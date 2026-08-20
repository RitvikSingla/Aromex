# Firestore Schema — per-client Firebase project

Canonical document shapes for every client's Firebase project. Firestore is
schemaless; this file is the **source of truth** for what the app, the
Cloud Functions (future), the rules, and the setup script agree on.

If you change a shape here, you must also update:
- `firestore.rules` (any field-level checks)
- `scripts/types.ts` (the TS types used by the setup script)
- All callers in the app once it starts reading these (later tickets).

PRD reference: §7.3.

---

## `users/{uid}`

One doc per signed-in user. `uid` is the Firebase Auth UID.

| field | type | notes |
|---|---|---|
| `email` | string | matches the Auth user's email; immutable after creation |
| `displayName` | string | shown in the UI |
| `role` | `"admin"` \| `"member"` | admin has full access regardless of `permissions`; member is gated by `permissions` |
| `permissions` | map | see [PERMISSIONS.md](./PERMISSIONS.md) |
| `isActive` | boolean | gate read by gateway's `/hl-token` (returns 403 if false) and by `hasPermission()` in rules |
| `createdBy` | string | uid of the admin who created the user, or `"setup-script"` for the very first admin |
| `createdAt` | timestamp | server timestamp at create |
| `updatedAt` | timestamp | server timestamp at every write |
| `lastLoginAt` | timestamp \| null | updated by the app on successful sign-in; null until first login |

**Custom claims (set on the Auth user, NOT in this doc):**
- `admin: bool` — must match `role === "admin"`
- `hlCompanyId: string` — must match `companySettings/profile.hlCompanyId`

Rules enforce: read is `self OR admin`; write is `admin only`; delete is never allowed
(deactivate via `isActive: false`).

### Example

```json
{
  "email": "owner@aromex.test",
  "displayName": "Aromex Owner",
  "role": "admin",
  "permissions": {
    "sales": "manage",
    "purchases": "manage",
    "inventory": "manage",
    "transactions": "manage",
    "profiles": "manage",
    "balances": "manage",
    "reports": "manage",
    "statistics": "manage",
    "histories": "manage",
    "ledgers": "manage",
    "settings": "manage",
    "userMgmt": true
  },
  "isActive": true,
  "createdBy": "setup-script",
  "createdAt": "<server timestamp>",
  "updatedAt": "<server timestamp>",
  "lastLoginAt": null
}
```

---

## `companySettings/profile` (singleton)

Exactly one doc, always at this path. Holds company-wide config.

| field | type | notes |
|---|---|---|
| `companyName` | string | display name, e.g. "Acme Mobile" |
| `legalName` | string | for invoices |
| `logoUrl` | string \| null | optional |
| `country` | string | ISO-3166 alpha-2, e.g. `"CA"` |
| `currency` | string | ISO-4217, e.g. `"CAD"` — fixed at setup (PRD: one currency per company) |
| `tax` | map | see below |
| `hlCompanyId` | string | the HL company UUID; matched against custom claim `hlCompanyId` |
| `businessAddress` | string \| null | |
| `contactEmail` | string \| null | |
| `contactPhone` | string \| null | |
| `taxNumber` | string \| null | GST/HST registration no. for invoice letterheads (ticket #76); `null` when the shop isn't registered — the PDF then omits the line |
| `timezone` | string | IANA zone the shop trades in, e.g. `"America/Vancouver"` (ticket #80). Invoice dates are formatted in it so an evening sale keeps its own calendar day / tax period. Required by `--timezone` at setup; a missing or invalid value makes the CF fall back to UTC |
| `createdAt` | timestamp | |
| `updatedAt` | timestamp | |

### `tax` map

| field | type | notes |
|---|---|---|
| `gstEnabled` | boolean | |
| `gstRate` | number \| string | decimal fraction, e.g. `0.05` for 5%. Provisioning writes a number; the in-app Settings screen writes a **string** (`"0.05"`) so a rate like Quebec's 9.975% round-trips exactly. Readers accept both |
| `pstEnabled` | boolean | |
| `pstRate` | number \| string | decimal fraction; same both-types rule as `gstRate` |
| `isHST` | boolean | if `true`, treat the GST line as HST (single combined tax) |

Three typical configurations:
- **Canada (PST provinces, BC/SK/MB/QC):** `gstEnabled=true, gstRate=0.05, pstEnabled=true, pstRate=0.07, isHST=false`
- **Canada (HST provinces):** `gstEnabled=true, gstRate=0.13, pstEnabled=false, pstRate=0, isHST=true`
- **India / GST-only:** `gstEnabled=true, gstRate=0.18, pstEnabled=false, pstRate=0, isHST=false`

Rules enforce: read by any signed-in user (the app needs it to render currency/tax); write by admin
only, with `hlCompanyId` and `currency` **immutable** — an admin editing tax or the invoice
letterhead must not be able to re-point the company at another HL ledger or re-label every stored
amount as a different currency. Only a Cloud Function (Admin SDK) may move those. Delete is denied
outright: every other rule resolves the tenant through this document.

---

## `companySettingsChanges/{changeId}` (append-only audit)

One doc per **changed field**, written by the Settings screen in the same transaction as the profile
write. A save that changed nothing writes nothing.

| field | type | notes |
|---|---|---|
| `changeId` | string | mirrors the doc key |
| `field` | string | as displayed, e.g. `"GST rate"`, `"Business address"` |
| `oldValue` | string \| null | `null` when the field did not previously apply |
| `newValue` | string | `""` when the field stopped applying (e.g. GST turned off) |
| `changedBy` | string | Firebase uid; rules pin it to the caller's own uid |
| `changedByName` | string \| null | display name at the time of the change |
| `changedAt` | timestamp | server timestamp |

Rates are recorded as **percentages** (`"9.975%"`) rather than the stored fraction.

Rules enforce: admin-only read; create only with `changedBy == request.auth.uid`; **update and delete
denied to everyone**, including the author — an editable audit log is not one.

---

## `invites/{inviteId}` (optional)

For the later staff-invite flow. Not used yet.

| field | type | notes |
|---|---|---|
| `email` | string | invitee's email |
| `permissions` | map | same shape as `users/{uid}.permissions` |
| `invitedBy` | string | admin's uid |
| `status` | `"pending"` \| `"accepted"` \| `"revoked"` \| `"expired"` | |
| `expiresAt` | timestamp | |
| `createdAt` | timestamp | |

Rules enforce: read+write by admin only. (Invitees won't see their own invite —
the link they receive contains the inviteId out-of-band.)

---

## `entities/{entityId}` (Profiles / parties)

One unified party Aromex buys from and/or sells to. `customer` / `supplier` /
`middleman` are **non-binding roles** (labels), never gates. Each entity is backed by
exactly one Humble Ledger customer account, so buying and selling net into a single
balance. `entityId` doubles as the HL `externalId`.

| field | type | notes |
|---|---|---|
| `name` | string | party name |
| `phones` | string[] | one or more; `phones[0]` is sent to HL |
| `email` | string \| null | |
| `address` | string \| null | |
| `roles` | string[] | subset of `["CUSTOMER","SUPPLIER","MIDDLEMAN"]` — **UPPERCASE** |
| `notes` | string \| null | |
| `taxNumber` | string \| null | optional GST/HST number (ticket #106); snapshotted onto a sale + printed on that party's invoice |
| `isWalkIn` | bool | reserved `walk-in` doc only; cannot be archived/renamed |
| `isActive` | bool | soft-archive flag (never hard-deleted) |
| `hlCustomerId` | string \| null | **Cloud-Function-owned** — HL customer id once synced |
| `hlAccountId` | string \| null | **Cloud-Function-owned** — HL AR sub-account id |
| `syncStatus` | `"PENDING"` \| `"SYNCED"` \| `"FAILED"` | client writes PENDING; the CF sets SYNCED/FAILED |
| `hlSyncedAt` | timestamp \| absent | CF-owned; set on successful sync |
| `hlSyncError` | string \| absent | CF-owned; last failure reason |
| `opening` | map \| absent | optional `{ amount, direction, sourceId, posted }` — only on create |
| `createdBy` | string | uid (or `setup-script` for Walk-in) |
| `createdAt` / `updatedAt` | timestamp | |

**Balance is NOT stored here** — it is read live from HL. Rules enforce: read with
`profiles` view; create/update with `profiles` manage; **no hard delete** (soft-archive
via `isActive=false`); clients may only start `syncStatus=PENDING` and may not forge the
HL fields or mint the reserved Walk-in (the `onEntityWrite` Cloud Function, via Admin SDK,
owns those and bypasses rules).

The reserved **`entities/walk-in`** (`isWalkIn=true`) is created by the setup script for
anonymous paid-in-full sales.

The reserved **`entities/unspecified-supplier`** (ticket #58) is created lazily by the
`onPurchaseWrite` Cloud Function on first use — the fallback party a purchase is booked
against when the cashier doesn't name who inventory was bought from. Fixed id → no
duplicates under concurrent first-use; an ordinary visible party otherwise.

---

## `purchases/{purchaseId}` (Inventory purchase → HL · ticket #58)

One lightweight purchase captured at Add-Inventory time — **one doc per batch/submission**
(not per unit). Written PENDING by the client alongside (never merged into) the inventory
transaction; the `onPurchaseWrite` Cloud Function posts it to Humble Ledger as an **asset**
increase (`customer-purchases` against the company's `Inventory` ASSET account) plus a
`customer-payouts` call per non-zero cash/bank amount, then flips it to SYNCED. The
reconcile sweep is the backstop (it also retries purchases waiting on their party to sync).

| field | type | notes |
|---|---|---|
| `partyEntityId` | string | entity id of the party bought from (its HL id is resolved server-side); `unspecified-supplier` by default |
| `totalCost` | string | batch total = Σ per-unit reviewed cost — **decimal STRING** |
| `cashPaid` | string | cash paid to the party now; `"0"` when none — decimal STRING |
| `bankPaid` | string | bank paid to the party now; `"0"` when none — decimal STRING |
| `unitCount` | int | phones the batch brought in, recorded at intake (ticket #106). Absent on pre-#106 batches, which is precisely what makes them un-reversible. Counting serials instead would report a shrinking batch as units sell |
| `syncStatus` | `"PENDING"` \| `"SYNCED"` \| `"FAILED"` | client writes PENDING; the CF sets SYNCED/FAILED |
| `hlSyncedAt` | timestamp \| absent | CF-owned; set on successful sync |
| `hlSyncError` | string \| absent | CF-owned; last failure reason |
| `hlPurchaseTxnId` | string \| absent | CF-owned (#106). The posted purchase transaction — what a reversal reverses |
| `hlPayoutCashTxnId` | string \| absent | CF-owned; the cash payout leg, when one posted |
| `hlPayoutBankTxnId` | string \| absent | CF-owned; the bank payout leg, when one posted |
| `createdBy` | string | uid (sent to HL as `actorRef`) |
| `createdAt` | timestamp | the **business date** — the day the stock was bought (ticket #107). Equal to the entry time for a normal intake; earlier when old books are being entered. Stock History orders and filters on it, and the HL posting takes its accounting date from it |
| `enteredAt` | timestamp | when it was actually keyed in. Only the audit trail reads this |
| `updatedAt` | timestamp | |

### Reversal trail (ticket #106)

Reversing a batch un-books the purchase: the party stops being owed for it, money paid at intake
comes back, any commission it earned is reversed, and the phones leave stock. A batch is the unit
of reversal because it is the unit of posting — one HL transaction covers the whole batch, and HL
reverses a transaction whole.

| field | type | notes |
|---|---|---|
| `status` | `"ACTIVE"` \| `"REVERSED"` | absent reads as ACTIVE |
| `reversalRequestedAt` | timestamp | **the trigger.** Desktop's Admin SDK sets it; `onPurchaseWrite` edge-triggers on it changing. Mobile uses the `reverseStockBatch` callable instead |
| `reversalRequestedBy` | string | uid; the CF re-checks `users/{uid}.role == 'admin'` and never trusts this |
| `reversalReason` | string | required, non-blank |
| `reversalStatus` | `"PENDING"` \| `"DONE"` \| `"FAILED"` | CF-owned. PENDING is the window where the row must not offer Reverse again |
| `reversalError` | string \| absent | CF-owned; last failure reason |
| `hlReversalTxnIds` | map | CF-owned; leg key → reversal transaction id (`purchase`, `payout_cash`, `payout_bank`, `commission_<id>:accrue`, …). Persisted **per leg as it lands**, so a retry after a crash re-reverses nothing |

A reversal is refused, before any HL call, when the batch is not whole: a unit has left stock, a
unit was removed individually, or the batch predates `serials.purchaseId` and its phones can't be
identified. `assertBatchIsWhole` (functions) and `batchReversalBlock` (shared Kotlin) implement the
same rule; the function is the authority, because Desktop bypasses Firestore rules.

Rules enforce: read/create with `inventory` view/manage (the purchase is inseparable from
adding the stock it pays for); a client may only **start** a record as PENDING and may
**never** update or delete it — the CF (Admin SDK) owns the sync fields and bypasses rules. A
create may not carry `status: REVERSED`, any reversal field, or any `hl*TxnId`: a forged ledger id
would hand a later reversal someone else's transaction to reverse.
HL idempotency: `(appId="aromex", sourceId)` with `sourceId = purchase_<id>:{purchase|payout_cash|payout_bank}`.

## `commissionRules/{ruleId}` (Commission on intake · ticket #97)

An **admin-managed** standing arrangement: *whenever phones land at a location, a party earns
per phone.* Several rules may target one location; each fires independently at intake. Touches
no Humble Ledger — it only decides what a later intake will owe. Switching a rule off
(`isActive=false`) stops future matches but never touches commission already earned.

| field | type | notes |
|---|---|---|
| `ruleId` | string | == doc id |
| `locationAttributeId` | string | an `attributes` value of type LOCATION |
| `payeeEntityId` | string | entity id of the party who earns it |
| `rateKind` | `"PER_UNIT"` \| `"PERCENT_OF_COST"` | how `rate` is applied |
| `rate` | string | per-unit amount (`"5.00"`) or percent **fraction** (`"0.02"` = 2%) — **decimal STRING** |
| `isActive` | bool | switched-off rules don't fire; never hard-deleted |
| `createdBy` | string | uid |
| `createdAt` / `updatedAt` | timestamp | |

Rules enforce: **write** is **admin-only** (`isAdmin()`) — a rule silently creates money owed,
a settings-grade act; matches `SaveCommissionRuleUseCase`'s `role == ADMIN` gate. **Read** is
`inventory` view — the cashier who adds stock must see the commission a rule proposes and
confirm it at intake (`ObserveActiveCommissionRulesUseCase`), even though only an admin edits
the rule; the admin-only management screen is gated in-app. Never deleted (switch off instead).
Desktop's Admin SDK bypasses rules; the use cases are the authority there.

## `commissions/{commissionId}` (Commission on intake → HL · ticket #97)

One payee's commission earned from **one Add-Inventory batch** — written PENDING **in the same
Firestore transaction as the stock and its purchase** (never stock without its commission
obligation). The `onCommissionWrite` Cloud Function posts it to HL — **accrue** via
`customer-purchases` against a `Commission` EXPENSE account (the payee's balance moves in their
favour), then an optional **payout** via `customer-payouts` when the user paid it now — and
flips it to SYNCED. Reconcile sweep is the backstop (retries commissions waiting on their payee
to sync). Clawback on a later void/return is **out of scope in v1**.

| field | type | notes |
|---|---|---|
| `commissionId` | string | == doc id |
| `payeeEntityId` | string | entity id of the party who earns it (HL id resolved server-side) |
| `locationAttributeId` | string | the location whose units earned it |
| `ruleId` | string \| null | the rule that proposed it; **null** when the amount was hand-edited |
| `unitCount` | int | units at this location |
| `basisAmount` | string | summed cost a percent rule applied to; `"0"` for per-unit — decimal STRING |
| `amount` | string | what's owed to the payee (always accrued) — **decimal STRING** |
| `paidCash` | string | cash given now; `"0"` for none — decimal STRING |
| `paidBank` | string | bank given now; `"0"` for none — decimal STRING |
| `sourceBatchId` | string | the `purchases` doc id of the batch that earned it |
| `syncStatus` | `"PENDING"` \| `"SYNCED"` \| `"FAILED"` | client writes PENDING; the CF sets SYNCED/FAILED |
| `hlTransactionId` / `hlSyncedAt` / `hlSyncError` | | CF-owned |
| `createdBy` | string | uid (sent to HL as `actorRef`) |
| `createdAt` / `updatedAt` | timestamp | |

Rules enforce: read/create with `inventory` view/manage (inseparable from the stock that earned
it, exactly like purchases); a client may only **start** a record as PENDING and may **never**
update or delete it — the CF owns the sync fields and bypasses rules. HL idempotency:
`(appId="aromex", sourceId)` with `sourceId = commission_<id>` (accrue) plus
`commission_<id>:payout_cash` / `commission_<id>:payout_bank` for each non-zero give-now leg.
The full `amount` is always accrued; `paidCash + paidBank` (≤ `amount`) nets the balance down,
exactly like an inventory purchase's cash/bank split.

_No composite index is needed: every query on these collections is single-field equality
(`commissionRules.isActive`, `commissions.syncStatus`), which Firestore indexes automatically —
same as `purchases`._
