## 🎯 What we're building & why

The legacy app's best screen was its **transactions screen**: pick who gave, pick who got, type an
amount, add a note, done. One screen covered customers paying their bills, the shop paying people
back, and money lent between two parties. A shopkeeper needs no accounting vocabulary to use it.

We're rebuilding that screen — and *only* that screen, not the machinery under it, because Humble
Ledger already is the machinery, done properly.

## 🔥 Why the legacy version couldn't be trusted

Read before designing anything, because these are the failures we are explicitly buying our way out
of. All four verified in `/Users/ansh/Desktop/Projects/iOSDev/Aromex`:

1. **A lost-update race.** `TransactionManager.swift:671` reads a party's balance *outside* any
   transaction, then line 677 writes back an absolute `currentBalance + amount`. Two entries against
   the same party at the same moment: last write wins, and one entry's money silently vanishes from
   the balance while both transaction records persist. It doesn't even use `FieldValue.increment`.
2. **Balances are stored, not derived.** `Customers/{id}.balance` *is* the truth, so a lost,
   duplicated or misordered write makes it permanently wrong and **undetectable** — there is nothing
   to check it against. `balancesAfterTransaction` looks like an audit trail but snapshots the same
   mutable number, so it faithfully records the error too.
3. **It isn't double-entry.** No accounts, no debits and credits, no trial balance. Money can be
   created or destroyed by a bug and nothing anywhere notices.
4. **Money is `Double`.** Cents drift, and it compounds over thousands of entries.

Every one of those is structurally impossible in HL: debits must equal credits within a paisa,
entries are immutable (you reverse, never edit), balances are *derived* from entries, and writes are
idempotent on `(appId, sourceId)` — which kills the race at its root, because a retried write cannot
double-post. HL even ships `GET /reports/integrity`, which proves the books square on demand.

## ✅ What it must do

1. **Move money between any two accounts** — a party, Cash, or Bank — with an amount, a note and a
   date. Every combination is meaningful: customer→Cash is a collection, Cash→customer is a payout or
   a loan, customer→customer is one party settling another's balance, Cash→Bank is a deposit (which
   the legacy app couldn't even express).
2. **Show a party's statement** — every entry against them with a running balance.
3. **Never let the app's numbers disagree with the ledger's**, because the app stores no balances.

## 🌟 What "good" looks like

- A cashier records "Rajesh paid me $500" in under five seconds without knowing what a debit is.
- The running balance on a party's statement is the same number HL would give an accountant, because
  it *is* that number.
- Two people recording entries against the same party at the same moment both land, correctly. The
  legacy app could not promise this.

## 🚫 Non-negotiables

- **The app stores no balances.** Ever. Balances are read from HL. This is the single decision that
  makes the whole class of legacy bugs impossible.
- **Money as decimal strings**, converted to numbers only at the HL boundary — the existing rule.
- **HL credentials never on device.** The client writes a Firestore doc; the Cloud Function posts to
  HL. Same dual-write spine as sales — never a direct client→HL write.
- **Single currency.** The company currency, full stop. No `CurrencyBalances`, no exchange rates, no
  `isExchange`, no exchange-profit tracking. That was the majority of the legacy complexity and the
  least trustworthy part of it, and the PRD already fixes one currency per company.
- **Entries are never edited or deleted.** A mistake is corrected by reversing it, exactly as with a
  voided sale (#85).

## 🧭 Technical steers

**Verified against live HL (2026-07-31):**

- **Party-to-party works.** A raw `POST /api/v1/transactions` with `postingType: JOURNAL`,
  `DR Ansh Bajaj / CR Walk-in Customer`, posted cleanly. Integrity stayed `ok: true` with zero
  warnings, and — the confirmation that matters — **the balance sheet did not move**, because
  transferring a receivable between two parties shouldn't change total assets. It didn't.
- **`GET /ledger?accountId=…` is the statement view, already built.** Rows come back as
  `date, description, postingType, debit, credit, balance, transactionId` with a running balance, a
  `closingBalance`, and pagination meta.
- Endpoint per combination — prefer the high-level ones over a raw journal where one fits, because
  they do more (status recomputation, typed posting):

  | From → To | Endpoint |
  |---|---|
  | Party → Cash/Bank | `POST /payments` |
  | Cash/Bank → Party | `POST /customer-payouts` |
  | Party → Party | `POST /transactions` (`JOURNAL`) |
  | Cash → Bank (and back) | `POST /transactions` (`JOURNAL`) |

- **Payments here are deliberately *not* applied to specific invoices.** Money reduces what the party
  owes overall. Consequence, accepted knowingly: an old credit sale's invoice stays open in HL while
  the party's balance goes to zero — which is exactly why #86 was rewritten to lead with the ledger
  balance and treat invoice aging as drill-in detail. See #88 for the related bug where *sale*
  payments weren't linked at all.

**App-side:**

- New Firestore collection following the existing spine: client writes the entry `PENDING`, the CF
  posts to HL and flips it `SYNCED`, the reconcile sweep is the backstop. Deterministic `sourceId`
  from the doc id, so a retry can never double-post.
- Cash and Bank appear in the party dropdown as themselves — what you pick *is* the ledger account.
  No `myself_special_id` magic string, and no hidden mapping between what the cashier chose and where
  the money went.
- Entities are already unified (one party, roles are labels), so the legacy
  Customers/Middlemen/Suppliers probing has nowhere to come back from.

## 🧊 Happy to defer

- **Expenses** (`POST /expenses`) — decided: after this is stable.
- Phones. Desktop first, as with #83.
- Free-form any-account-to-any-account journal entry (an admin power tool). Not needed for the money
  movement a shop actually does, and it's the one entry type that lets someone post something
  balanced but meaningless.
- Attachments/receipts on an entry, recurring entries, scheduled reminders.

## 📎 References

- Legacy: `Aromex/Managers/TransactionManager.swift`, `Aromex/Views/AddEntryView.swift`,
  `TRANSACTION_QUICK_REFERENCE.md`
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json`
- Related: #86 (receivables, balance-first), #88 (sale payments now linked to invoices), #85 (reversal
  as the correction mechanism)
