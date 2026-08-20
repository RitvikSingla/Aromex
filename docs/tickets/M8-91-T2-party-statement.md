> Brief: #89 · Milestone M8 · **Desktop** · pairs with the M8 entry screen

## 📖 Story / Why

Once money can move (M8 T1), the immediate next question at the counter is *"so what does Rajesh owe
me, and how did we get here?"* Today the Entities screen shows a party's current HL balance and
nothing else — a number with no story behind it.

The legacy app answered this by replaying stored balance snapshots. HL answers it properly:
`GET /ledger?accountId=…` returns every entry against an account with a **running balance already
computed**, and an opening balance that stays correct even when you filter to a narrow date range.
This ticket renders that.

## 🎯 What you're building

A **statement** section on the existing Entity detail view — not a new screen, so there stays exactly
one place to look a person up.

Under the party's current balance, a chronological ledger: date, description, posting type, debit or
credit, and the running balance after each entry. Newest first, paged. A date-range filter, because
"what happened between April and June" is how people actually reconcile with a customer.

Each row links to what created it where we can resolve it — a sale row opens that sale in Sales
History (#83), a money entry opens it in the M8 feed. Rows we can't resolve (posted directly in HL,
or predating the app) still render, just without a link. **Never hide a ledger row because the app
can't explain it** — that's the row the customer will ask about.

## 🔬 The endpoint (verified 2026-07-31)

`GET /api/v1/ledger?accountId=…&from=&to=&page=&limit=` returns:

```
account:        { id, name, type, isActive }
closingBalance: 75
rows: [ { date, description, postingType, debit, credit, balance, transactionId } ]
meta:           { page, limit, total, totalPages, hasMore }
```

`debit` and `credit` are decimal **strings**, one of them null per row. `balance` is the running
balance after that row. `postingType` is one of `SALE | PAYMENT | ADVANCE | ADVANCE_APPLIED | EXPENSE
| PURCHASE | PAYOUT | REFUND | REVERSAL | JOURNAL` — render it as a human label, not the raw enum.

## 🧠 Notes

- **Render HL's `balance`; never compute a running total yourself.** The moment the app accumulates
  its own, it can disagree with the ledger — and HL's is correct across pagination and date filters,
  which a client-side accumulator is not (page 2 has no idea what page 1 summed to).
- Money stays strings. A `Double` here is a bug even when it displays correctly.
- `REVERSAL` rows are normal and must be shown, not filtered — a reversed entry and its mirror
  together are the audit trail. Style them so the pair reads as a correction rather than as two
  unrelated movements.
- Dates in the shop's `timezone` (`companySettings/profile.timezone`).
- Page the rows. A party with two years of history is not a small response.

## ✅ Scope

- `sharedLogic`: `LedgerRow` + `AccountStatement` models; extend the existing HL read path (the same
  one Entity balances already use) with `getLedger(accountId, from, to, page)`;
  `ObserveAccountStatementUseCase` gated on `transactions: view`.
- `desktopApp`: statement section on the Entity detail view + ViewModel; date-range control; paging;
  row → source navigation where resolvable.
- Tests: rendering from a fixture payload including a `REVERSAL` pair, a row with no resolvable
  source, date-range paging, and the empty state.

## 🖼️ UI standards (Definition of Done)

- Theme tokens, light + dark. Reuse the #55 table patterns.
- Debit and credit are separate columns, right-aligned, with the running balance last — the layout
  anyone who has seen a bank statement already knows.
- i18n for every string, including the posting-type labels.
- Empty state distinguishes "no activity yet" from "no activity in this date range".
- Explicit error state when HL is unreachable. Never render an empty statement as though the party
  has no history.

## 🎯 Acceptance Criteria

1. A party's Entity detail shows their statement, newest first, with a running balance that matches
   `GET /ledger` exactly — spot-check three rows against a raw API response and paste the comparison.
2. The date-range filter narrows the rows and the opening balance stays correct (verify by comparing a
   filtered range's first row against the unfiltered statement).
3. Paging works and the running balance stays continuous across page boundaries.
4. A `REVERSAL` row and its original both appear and read as a matched correction.
5. A row with no resolvable local source renders without a link rather than being dropped.
6. Rows link to the sale (#83) or money entry (M8 T1) that created them, where resolvable.
7. No local running-total computation — the app renders HL's `balance` field.
8. Grep for `toDouble()`/`Double(`/`Float` in new code returns nothing.
9. Light + dark; `:desktopApp:test` and `:sharedLogic:jvmTest` green.

## 🚫 Out of scope

- Exporting or printing a statement (worth its own ticket once people ask)
- Emailing a statement to a customer · aging buckets (that's #86)
- Editing anything from this view — it is strictly a read surface
- Phones

## 🔗 Dependencies

- The M8 entry screen for the row → money-entry link (ship without that link if it lands first).
- #83 for the row → sale link, same.
- Entity detail view — already shipped.

## 📚 References

- `sharedLogic/.../repository/EntityLedgerRepository.kt` — the HL read path already in place
- `desktopApp/.../ui/entities/EntitiesScreen.kt` — where this section goes
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json` → `GET /api/v1/ledger`

## 🤖 Kickoff prompt

> Read brief #89 and this ticket. Add a party statement to the existing Entity detail view, rendering
> `GET /ledger` — date, description, posting type, debit/credit, running balance — paged with a
> date-range filter. Render HL's running balance rather than computing your own, show `REVERSAL` rows
> as matched corrections, and never drop a row just because the app can't link it to a local record.
