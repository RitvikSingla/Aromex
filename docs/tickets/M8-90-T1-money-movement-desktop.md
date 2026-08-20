> Brief: #89 · Milestone M8 · **Desktop** · CF + shared + UI

## 📖 Story / Why

The shop can create debt but has no way to settle it. A customer who bought on credit can pay you
cash and there is nowhere to record it. You can't pay someone back, and you can't record money lent.
The legacy app's transactions screen did all of this on one screen, and it's the piece people
actually miss.

This ticket rebuilds that screen. **Read #89 first** — especially the four verified defects in the
legacy implementation, because the entire point of this design is that they become impossible rather
than merely fixed.

## 🎯 What you're building

One entry form, deliberately as plain as the old one:

**From** · **To** · **Amount** · **Note** · **Date**

From and To are the same searchable dropdown: every active party, plus **Cash** and **Bank**. What
the cashier picks *is* the ledger account — no `myself_special_id`, no hidden mapping. Below the form,
a chronological feed of recent entries.

### Routing
The From/To pair picks the endpoint. Prefer the high-level ones over a raw journal where one fits —
they carry proper posting types and HL treats them as first-class:

| From → To | HL call | Meaning |
|---|---|---|
| Party → Cash/Bank | `POST /payments` | they paid you |
| Cash/Bank → Party | `POST /customer-payouts` | you paid or lent them |
| Party → Party | `POST /transactions` (`JOURNAL`) | one settles another's balance |
| Cash ↔ Bank | `POST /transactions` (`JOURNAL`) | deposit / withdrawal |

**Do not pass `invoiceId` on the payments.** Decided in #89: money reduces what the party owes
overall rather than settling a named invoice. (Contrast #88, where a *sale's own* payments must carry
it.)

### Writing it
Follow the existing dual-write spine exactly — client → Firestore `PENDING` → CF posts to HL →
`SYNCED`, reconcile sweep as backstop. **The client never talks to HL.**

```
moneyEntries/{entryId}
  entryId, fromAccountRef, toAccountRef      // {kind: PARTY|CASH|BANK, entityId?}
  amount                                     // decimal STRING
  note, entryDate, createdBy, createdAt, updatedAt
  syncStatus: "PENDING" | "SYNCED" | "FAILED"
  hlTransactionId, hlSyncedAt, hlSyncError
```

`sourceId` derives deterministically from `entryId`, so a CF retry can never double-post — the
property that makes the legacy race structurally impossible here.

## 🧠 The things that make this correct

**Store no balances.** Not on the party, not on the entry, not as a "balance after" snapshot. The
legacy app's whole failure mode was a stored number that could drift with nothing to check it
against. Balances come from HL, always. If you feel the urge to cache one for display speed, don't —
cache the *response*, with a visible staleness indicator, never a number you maintain.

**Validate before writing, and re-validate server-side.** From ≠ To, amount > 0, both accounts exist
and are active. The client checks for a good error message; the CF checks because the client can lie.

**The date is the accounting date.** It goes to HL as the transaction date and can be backdated — a
cashier recording yesterday's payment is normal. It is not `createdAt`; keep both.

**Correct by reversing.** No edit, no delete. A wrong entry is fixed by posting its reverse via
`POST /transactions/{id}/reverse` — the same mechanism as a voided sale (#85). v1 needs at minimum a
Reverse action on a synced entry; it does not need editing.

## ✅ Scope

- `sharedLogic`: `MoneyEntry` + `MoneyAccountRef` models; `MoneyEntryRepository`;
  `RecordMoneyEntryUseCase` (gates on `transactions: manage`), `ObserveMoneyEntriesUseCase`,
  `ReverseMoneyEntryUseCase`.
- `firebase/functions`: `onMoneyEntryWrite` trigger + `syncMoneyEntry` (routing table above,
  idempotent); reconcile sweep coverage; `hl.ts` gains `createJournalEntry` and reuses
  `createPayment`/`createCustomerPayout`.
- `desktopApp`: `ui/money/` entry form + recent feed + ViewModel; nav entry.
- `firebase/firestore.rules` + `firestore.indexes.json` + `docs/SCHEMA.md` for the new collection —
  client may create `PENDING` and may never write `hl*` or `syncStatus` after create, mirroring sales.
- Tests: functions tests per routing combination + idempotent replay; Desktop VM tests for validation,
  the account picker, and the sync state machine.

## 🖼️ UI standards (Definition of Done)

- Theme tokens, light + dark. Reuse the existing searchable-dropdown and money-field components —
  the same ones the Sales checkout uses. Do not build a third dropdown.
- Cash and Bank are visually distinguishable from parties in the list (they're your accounts, not
  people) but selected the same way.
- Show the selected party's **current balance** next to the picker, read from HL, so the cashier sees
  what they owe before typing an amount. Mark it clearly if it can't be loaded — never render a blank
  or zero as if it were a balance.
- Money via `MoneyFormat`; dates in the shop's `timezone`; every string via i18n.
- The entry stays visible in the feed while it syncs, with its PENDING state shown — a committed
  entry must never look lost just because HL hasn't answered yet.

## 🎯 Acceptance Criteria

1. All four From/To combinations post to HL and land as the right posting type; verify each against
   `GET /ledger` for both sides and paste the running balances.
2. Recording an entry writes Firestore `PENDING` and the CF settles it `SYNCED` with `hlTransactionId`
   stored. The client makes no HL call.
3. **Replaying the CF for the same entry does not double-post** — same `sourceId`, HL returns the
   original. Test it.
4. Two entries against the same party recorded concurrently both land and both are reflected in that
   party's balance. This is the legacy race; prove it's gone.
5. `GET /reports/integrity` reports `ok: true` with zero warnings after a batch of mixed entries.
6. From == To, zero, negative, and non-numeric amounts are all rejected — client and CF.
7. A backdated entry appears at its entry date in the party's statement, not at creation time.
8. Reversing an entry restores the party's balance exactly; the original stays visible.
9. `transactions: manage` gates recording; `transactions: view` can read the feed.
10. Grep the new code for `toDouble()`/`Double(`/`Float` — must return nothing.
11. Light + dark; `:desktopApp:test`, `:sharedLogic:jvmTest`, functions tests green.

## 🚫 Out of scope

- Expenses (deferred by decision) · phones · multi-currency of any kind
- Applying payments to specific invoices (see #86's balance-first design)
- Free-form any-account journal entry · attachments · recurring entries
- The party statement view — that's T2

## 🔗 Dependencies

- Entities (unified party model) — already shipped.
- #88 for the `createPayment` signature.
- Independent of M7; can run alongside.

## 📚 References

- **Legacy, for the UX only:** `Aromex/Views/AddEntryView.swift` (the From/To/amount/note/date form).
  Its `TransactionManager` is the anti-pattern, documented in #89 — do not port its logic.
- `firebase/functions/src/syncWorker.ts` → `syncSale` as the dual-write template
- `firebase/functions/src/hl.ts` → gateway token + `hlPost`
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json`

## 🤖 Kickoff prompt

> Read brief #89 and this ticket. Build the Desktop money-movement screen: one From/To/amount/note/date
> form where Cash and Bank sit alongside parties in the picker, writing through the existing
> Firestore-PENDING → CF → HL spine. Route each From/To combination to the endpoint in the table. Store
> **no balances anywhere** — that is the whole point. Prove the concurrent-entry case that the legacy
> app got wrong.
