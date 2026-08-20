import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPayment, createSale } from './hl.js';

/** Captures the request body handed to `fetch`, so we can assert on the exact JSON sent to HL. */
function mockHlOnce(body: unknown = { success: true }) {
  const fetchMock = vi.fn(
    async () => ({ ok: true, status: 200, json: async () => body }) as unknown as Response,
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function sentBody(fetchMock: ReturnType<typeof mockHlOnce>): Record<string, unknown> {
  const init = fetchMock.mock.calls[0]![1] as RequestInit;
  return JSON.parse(init.body as string) as Record<string, unknown>;
}

describe('createPayment — invoice application', () => {
  afterEach(() => vi.unstubAllGlobals());

  /**
   * The bug this guards: a sale's payments were posted with no `invoiceId`, so HL moved the
   * customer's AR but never touched the invoice. Every paid sale — including a walk-in paying
   * cash in full — left an invoice stuck at `amountPaid: 0` / `PENDING`, and `GET /receivables`
   * went on reporting money that was collected at the counter. Observed live before the fix:
   * two parties with $0 ledger balances and $8,833 of "outstanding" invoices between them.
   */
  it('sends invoiceId when the payment settles a specific invoice', async () => {
    const fetchMock = mockHlOnce();

    await createPayment('https://hl', 'tok', {
      customerId: 'cust-1',
      amount: '105.00',
      paymentAccountId: 'acct-cash',
      invoiceId: 'inv-9',
      sourceId: 'sale_s1_payment_cash',
    });

    expect(sentBody(fetchMock).invoiceId).toBe('inv-9');
  });

  it('omits the invoiceId KEY entirely for an unapplied payment — never sends undefined', async () => {
    const fetchMock = mockHlOnce();

    await createPayment('https://hl', 'tok', {
      customerId: 'cust-1',
      amount: '50.00',
      paymentAccountId: 'acct-cash',
      sourceId: 'balance-paydown-1',
    });

    // `'invoiceId' in body` — not `body.invoiceId === undefined` — because JSON.stringify drops
    // an undefined value, and we want the key genuinely absent rather than accidentally absent.
    expect('invoiceId' in sentBody(fetchMock)).toBe(false);
  });

  it('still sends the money fields and idempotency key alongside the invoice link', async () => {
    const fetchMock = mockHlOnce();

    await createPayment('https://hl', 'tok', {
      customerId: 'cust-1',
      amount: '105.00',
      paymentAccountId: 'acct-bank',
      invoiceId: 'inv-9',
      sourceId: 'sale_s1_payment_card',
      actorRef: 'uid-7',
    });

    const body = sentBody(fetchMock);
    expect(body).toMatchObject({
      customerId: 'cust-1',
      amount: 105, // decimal string → number only at the HL boundary
      paymentAccountId: 'acct-bank',
      invoiceId: 'inv-9',
      appId: 'aromex',
      sourceId: 'sale_s1_payment_card',
      actorRef: 'uid-7',
    });
  });
});

describe('createSale — result parsing', () => {
  afterEach(() => vi.unstubAllGlobals());

  /** The invoice **id** is what the payments must reference; parsing only `invoiceNumber`
   *  (as before) left the caller with no way to link them. */
  it('exposes both the invoice id and its number', async () => {
    mockHlOnce({ success: true, data: { invoice: { id: 'inv-9', invoiceNumber: 'INV-000042' } } });

    const result = await createSale('https://hl', 'tok', {
      customerId: 'cust-1',
      amount: '100.00',
      description: 'Sale (Aromex)',
      revenueAccountId: 'acct-rev',
      taxLines: [],
      sourceId: 'sale_s1_sale',
    });

    expect(result.data?.invoice?.id).toBe('inv-9');
    expect(result.data?.invoice?.invoiceNumber).toBe('INV-000042');
  });
});
