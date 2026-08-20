import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';
import {
  formatIssueDate,
  formatIssueDateTime,
  issueSaleInvoice,
  retryInvoiceCore,
  type SaleData,
} from './syncWorker.js';

// No module mock: we exercise the REAL renderInvoice and stub only the network (global fetch),
// so there's a genuine engine round-trip in the test — and no mocked-promise rejection quirks.

/** Minimal in-memory Firestore fake: collection().doc().get()/.set(merge). */
function makeFakeDb(seed: Record<string, Record<string, unknown>> = {}) {
  const store: Record<string, Record<string, unknown>> = structuredClone(seed);
  const db = {
    collection: (name: string) => ({
      doc: (id: string) => {
        const path = `${name}/${id}`;
        return {
          get: async () => ({ exists: path in store, data: () => store[path] }),
          set: async (data: Record<string, unknown>) => {
            store[path] = { ...(store[path] ?? {}), ...data };
          },
        };
      },
    }),
  };
  return { db: db as unknown as Firestore, store };
}

/** Stub global fetch to a fixed engine response, and return the spy for payload assertions. */
function stubEngine(status: number, body: unknown) {
  const fetchMock = vi.fn(async () => ({ status, json: async () => body }) as unknown as Response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** The invoice payload the engine received, parsed from the fetch body. */
function sentPayload(fetchMock: ReturnType<typeof stubEngine>) {
  const init = fetchMock.mock.calls[0][1] as { body: string };
  return JSON.parse(init.body) as { appId: string; data: Record<string, unknown> };
}

const cfg: SyncConfig = {
  gatewayBaseUrl: 'g',
  hlBaseUrl: 'h',
  adminToken: 't',
  projectId: 'p',
  billEngineUrl: 'https://engine',
};

const profileSeed = {
  'companySettings/profile': {
    companyName: 'Pukhraj Mobiles',
    legalName: 'Pukhraj Mobiles Ltd.',
    taxNumber: '123456789 RT0001',
    contactPhone: '+1 604',
  },
};

const walkInSale: SaleData = {
  customerEntityId: 'walk-in-customer',
  isWalkIn: true,
  taxableAmount: '900.00',
  lines: [{ kind: 'INVENTORY', imei: '111', label: 'iPhone', netPrice: '900.00' }],
  subtotal: '900.00',
  grandTotal: '945.00',
  amountPaid: '945.00',
  balanceRemaining: '0.00',
  taxLines: [{ name: 'GST', rate: '0.05', amount: '45.00' }],
  invoiceStatus: 'PENDING',
};

const now = new Date(Date.UTC(2026, 6, 29));

describe('issueSaleInvoice', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders and stores the invoice on success → ISSUED', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/aromex-INV-42.pdf' });
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...walkInSale } });

    await issueSaleInvoice(db, 's1', walkInSale, cfg, 'INV-42', now);

    const sale = store['sales/s1'];
    expect(sale.invoiceStatus).toBe('ISSUED');
    expect(sale.invoiceUrl).toBe('https://s3/aromex-INV-42.pdf');
    expect(sale.invoiceNumber).toBe('INV-42');
    expect(sentPayload(fetchMock).data.invoiceNumber).toBe('INV-42'); // same HL number on the PDF
    // #80: a nameless walk-in must fill "Bill To" with the placeholder, never an empty box.
    expect(sentPayload(fetchMock).data.customer).toMatchObject({ name: 'Walk-in Customer' });
  });

  it('a walk-in with a captured name uses that name, not the placeholder (#80)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const named: SaleData = { ...walkInSale, buyerName: 'Amrit Singh', buyerPhone: '+1 778' };
    const { db } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...named } });

    await issueSaleInvoice(db, 's1', named, cfg, 'INV-42', now);

    expect(sentPayload(fetchMock).data.customer).toEqual({ name: 'Amrit Singh', phone: '+1 778' });
  });

  it('a named sale whose entities doc is missing falls back to a non-empty Bill To (#80)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    // isWalkIn=false but no entities/e1 doc seeded → resolveInvoiceBuyer must not omit customer.
    const orphan: SaleData = { ...walkInSale, isWalkIn: false, customerEntityId: 'e1' };
    const { db } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...orphan } });

    await issueSaleInvoice(db, 's1', orphan, cfg, 'INV-42', now);

    expect(sentPayload(fetchMock).data.customer).toMatchObject({ name: 'Walk-in Customer' });
  });

  it('a render failure sets FAILED + invoiceError and does NOT throw (books stay intact)', async () => {
    stubEngine(500, { error: 'boom', details: { reason: 'template missing' } });
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...walkInSale } });

    let threw = false;
    await issueSaleInvoice(db, 's1', walkInSale, cfg, 'INV-42', now).catch(() => {
      threw = true;
    });
    expect(threw).toBe(false);

    const sale = store['sales/s1'];
    expect(sale.invoiceStatus).toBe('FAILED');
    expect(String(sale.invoiceError)).toContain('HTTP 500');
    expect(sale.invoiceUrl).toBeUndefined();
  });

  it('is idempotent: an already-ISSUED sale is skipped (no second render/number)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const issued: SaleData = { ...walkInSale, invoiceStatus: 'ISSUED', invoiceNumber: 'INV-42' };
    const { db } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...issued } });

    await issueSaleInvoice(db, 's1', issued, cfg, 'INV-42', now);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('a retry reuses the stored invoice number → same URL', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/aromex-INV-42.pdf' });
    // A FAILED first attempt: number already stored, awaiting reconcile.
    const failed: SaleData = { ...walkInSale, invoiceStatus: 'FAILED', invoiceNumber: 'INV-42' };
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...failed } });

    await issueSaleInvoice(db, 's1', failed, cfg, failed.invoiceNumber ?? undefined, now);

    expect(sentPayload(fetchMock).data.invoiceNumber).toBe('INV-42');
    expect(store['sales/s1'].invoiceStatus).toBe('ISSUED');
    expect(store['sales/s1'].invoiceUrl).toBe('https://s3/aromex-INV-42.pdf');
  });

  it('marks FAILED when no invoice number is available', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...walkInSale } });

    await issueSaleInvoice(db, 's1', walkInSale, cfg, undefined, now);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(store['sales/s1'].invoiceStatus).toBe('FAILED');
  });

  it('dates the invoice from the sale instant in the shop timezone, not render time (#80)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    // Sale rung up Aug 1 03:00 UTC = Jul 31 20:00 in Vancouver; rendered "now" a day later.
    const saleCreated = new Date(Date.UTC(2026, 7, 1, 3, 0, 0));
    const timedSale: SaleData = { ...walkInSale, createdAt: { toDate: () => saleCreated } as never };
    // Seed the store with a plain sale (the fake db structuredClones the seed, so no functions);
    // createdAt is read off the `data` arg passed to issueSaleInvoice, not re-read from the store.
    const { db } = makeFakeDb({
      'companySettings/profile': { ...profileSeed['companySettings/profile'], timezone: 'America/Vancouver' },
      'sales/s1': { ...walkInSale },
    });
    const renderTime = new Date(Date.UTC(2026, 7, 2, 0, 0, 0)); // a day later — must not be used

    await issueSaleInvoice(db, 's1', timedSale, cfg, 'INV-42', renderTime);

    expect(sentPayload(fetchMock).data.issueDate).toBe('31 Jul 2026, 8:00 PM PDT');
  });

  it('resolves a named buyer from the entities doc', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const namedSale: SaleData = { ...walkInSale, isWalkIn: false, customerEntityId: 'e1' };
    const { db } = makeFakeDb({
      ...profileSeed,
      'entities/e1': { name: 'Rajesh Traders', phones: ['+1 555'], email: 'r@t.com' },
      'sales/s1': { ...namedSale },
    });

    await issueSaleInvoice(db, 's1', namedSale, cfg, 'INV-7', now);

    expect(sentPayload(fetchMock).data.customer).toEqual({
      name: 'Rajesh Traders',
      phone: '+1 555',
      email: 'r@t.com',
    });
  });

  it('prefers the phone snapshotted on the sale over the contact’s stored number', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    // The cashier typed a different number at checkout and did NOT save it back — the bill must
    // show what they typed, not the contact's saved number (mirrors the tax-number behaviour).
    const namedSale: SaleData = {
      ...walkInSale,
      isWalkIn: false,
      customerEntityId: 'e1',
      buyerPhone: '+1 999',
    };
    const { db } = makeFakeDb({
      ...profileSeed,
      'entities/e1': { name: 'Rajesh Traders', phones: ['+1 555'], email: 'r@t.com' },
      'sales/s1': { ...namedSale },
    });

    await issueSaleInvoice(db, 's1', namedSale, cfg, 'INV-8', now);

    expect(sentPayload(fetchMock).data.customer).toEqual({
      name: 'Rajesh Traders',
      phone: '+1 999',
      email: 'r@t.com',
    });
  });
});

describe('formatIssueDate (ticket #80)', () => {
  it('formats in UTC when no timezone is configured (v1 behavior, unchanged)', () => {
    expect(formatIssueDate(new Date(Date.UTC(2026, 6, 29, 6, 0, 0)))).toBe('29 Jul 2026');
  });

  it("uses the shop's zone so a late-evening sale stays on its own calendar day", () => {
    // Jul 30 03:00 UTC is still Jul 29 (20:00 PDT) in Vancouver — UTC would misprint the 30th.
    const instant = new Date(Date.UTC(2026, 6, 30, 3, 0, 0));
    expect(formatIssueDate(instant, 'America/Vancouver')).toBe('29 Jul 2026');
    expect(formatIssueDate(instant)).toBe('30 Jul 2026'); // the bug this fixes
  });

  it('keeps a month-boundary sale in the correct (earlier) month', () => {
    // Aug 1 03:00 UTC = Jul 31 20:00 PDT — must print July, not August (tax-period correctness).
    expect(formatIssueDate(new Date(Date.UTC(2026, 7, 1, 3, 0, 0)), 'America/Vancouver')).toBe(
      '31 Jul 2026',
    );
  });

  it('falls back to UTC on an invalid IANA id (never throws)', () => {
    const instant = new Date(Date.UTC(2026, 6, 30, 3, 0, 0));
    expect(formatIssueDate(instant, 'Not/AZone')).toBe('30 Jul 2026');
  });
});

describe('formatIssueDateTime (ticket #80 — invoice header stamp)', () => {
  it('appends the local time-of-day and zone abbreviation', () => {
    // Aug 1 03:00 UTC = Jul 31 20:00 PDT — date and time both in the shop's zone.
    expect(formatIssueDateTime(new Date(Date.UTC(2026, 7, 1, 3, 0, 0)), 'America/Vancouver')).toBe(
      '31 Jul 2026, 8:00 PM PDT',
    );
  });

  it('falls back to UTC time when no zone is configured', () => {
    expect(formatIssueDateTime(new Date(Date.UTC(2026, 6, 29, 15, 5, 0)))).toBe(
      '29 Jul 2026, 3:05 PM UTC',
    );
  });
});

describe('retryInvoiceCore (ticket #77)', () => {
  afterEach(() => vi.unstubAllGlobals());

  const failedSyncedSale: SaleData = {
    ...walkInSale,
    syncStatus: 'SYNCED',
    invoiceStatus: 'FAILED',
    invoiceNumber: 'INV-42',
  };

  it('re-issues a FAILED+SYNCED sale immediately → ISSUED with url', async () => {
    stubEngine(200, { url: 'https://s3/aromex-INV-42.pdf' });
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...failedSyncedSale } });

    const result = await retryInvoiceCore(db, cfg, 's1', now);

    expect(result.status).toBe('ISSUED');
    expect(result.invoiceUrl).toBe('https://s3/aromex-INV-42.pdf');
    expect(result.invoiceNumber).toBe('INV-42'); // reused HL number, never a duplicate
    expect(store['sales/s1'].invoiceStatus).toBe('ISSUED');
  });

  it('a still-failing render leaves it FAILED (books never touched) and reports FAILED', async () => {
    stubEngine(500, { error: 'boom' });
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...failedSyncedSale } });

    const result = await retryInvoiceCore(db, cfg, 's1', now);

    expect(result.status).toBe('FAILED');
    expect(result.invoiceUrl).toBeNull();
    expect(store['sales/s1'].invoiceStatus).toBe('FAILED');
  });

  it('a failing manual retry does NOT consume the automatic-retry budget', async () => {
    stubEngine(500, { error: 'boom' });
    // Already at one automatic attempt; a manual Retry that still fails must leave it there,
    // so the reconcile sweep (MAX_INVOICE_ATTEMPTS) is never exhausted by a cashier's taps.
    const withAttempt: SaleData = { ...failedSyncedSale, invoiceAttempts: 1 };
    const { db, store } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...withAttempt } });

    await retryInvoiceCore(db, cfg, 's1', now);

    expect(store['sales/s1'].invoiceStatus).toBe('FAILED');
    expect(store['sales/s1'].invoiceAttempts).toBe(1); // unchanged by the manual path
  });

  it('is idempotent: an already-ISSUED sale is a no-op (no second render)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const issued: SaleData = { ...failedSyncedSale, invoiceStatus: 'ISSUED', invoiceUrl: 'https://s3/done.pdf' };
    const { db } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...issued } });

    const result = await retryInvoiceCore(db, cfg, 's1', now);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.status).toBe('ISSUED');
    expect(result.invoiceUrl).toBe('https://s3/done.pdf');
  });

  it('does not render when the sale is not yet HL-synced (the sweep owns it)', async () => {
    const fetchMock = stubEngine(200, { url: 'https://s3/x.pdf' });
    const pending: SaleData = { ...walkInSale, syncStatus: 'PENDING', invoiceStatus: 'PENDING' };
    const { db } = makeFakeDb({ ...profileSeed, 'sales/s1': { ...pending } });

    const result = await retryInvoiceCore(db, cfg, 's1', now);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.status).toBe('PENDING');
  });

  it('throws not-found for a missing sale (caller maps to an HttpsError)', async () => {
    const { db } = makeFakeDb({ ...profileSeed });
    await expect(retryInvoiceCore(db, cfg, 'missing', now)).rejects.toThrow('not-found');
  });
});
