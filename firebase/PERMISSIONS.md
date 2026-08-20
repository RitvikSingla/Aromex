# Permissions Catalog

Permissions live in `users/{uid}.permissions` (see [SCHEMA.md](./SCHEMA.md)).
There are **11 feature scopes** (each `manage` | `view` | `none`) and **one
boolean** (`userMgmt`, admin-only).

PRD reference: §7.2.

## The three levels

| level | meaning |
|---|---|
| `manage` | full access — view, create, edit, delete |
| `view` | read-only |
| `none` | feature is hidden in the app's navigation; rules deny reads + writes |

A user with `role: "admin"` bypasses these checks (rules grant admin
unconditional read+write within the project). Members are gated by their
per-feature level.

## The 11 features

| key | what it covers |
|---|---|
| `sales` | Sales workflow + per-sale records |
| `purchases` | Purchase workflow + per-purchase records |
| `inventory` | Phones (by IMEI), brands, models, colors, carriers, locations |
| `transactions` | Manual money movements (payments, transfers between parties/accounts) |
| `profiles` | Customer / supplier / middleman profiles |
| `balances` | Home/dashboard balance cards (Cash, Bank, Credit Card) and balance adjustments |
| `reports` | P&L, balance sheet, trial balance |
| `statistics` | Aggregated insights (sales over time, etc.) |
| `histories` | Per-entity change logs |
| `ledgers` | Per-account ledger views |
| `settings` | Company settings (logo, tax, addresses) — admin write usually, but a senior staff member could have `view` |

## The boolean

| key | meaning |
|---|---|
| `userMgmt` | `true` lets the user add/edit/deactivate staff (via the future Cloud Function). Admin-only in practice (rules enforce `role: "admin"` for any write to `users/`), so this flag really controls whether the "Users" admin screen is shown. |

## Canonical "admin" permissions value

```json
{
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
}
```

Used by the setup script for the first admin. Also a reasonable default
template when an admin "promote to admin" action is added in a later ticket.

## Canonical "no access" template

For starting a brand-new staff member, copy this and set the features they
should have access to:

```json
{
  "sales": "none",
  "purchases": "none",
  "inventory": "none",
  "transactions": "none",
  "profiles": "none",
  "balances": "none",
  "reports": "none",
  "statistics": "none",
  "histories": "none",
  "ledgers": "none",
  "settings": "none",
  "userMgmt": false
}
```

## How the app should consume these

1. On login, read `users/{uid}` once and cache.
2. Hide menu items where `permissions[feature] === "none"`.
3. For each screen, gate write actions on `permissions[feature] === "manage"`.
4. Rely on Firestore rules as the mobile backstop, but treat shared app
   logic as the real source of truth (Desktop bypasses rules — see CLAUDE.md).
