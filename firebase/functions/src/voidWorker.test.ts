import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FieldValue } from 'firebase-admin/firestore';
import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';

// Mock the network-touching HL client; keep the real module otherwise (types, HlHttpError).
vi.mock('./hl.js', async (importActual) => {
  const actual = await importActual<typeof import('./hl.js')>();
  return {
    ...actual,
    getHlToken: vi.fn(async () => 'fake-hl-token'),
    getInvoice: vi.fn(async (_u: string, _t: string, id: string) => ({ id, status: 'FINALIZED' })),
    cancelInvoice: vi.fn(async () => ({ reversalTxnId: 'rev-1' })),
    reverseTransaction: vi.fn(async () => ({ reversalTxnId: 'rev-txn-1' })),
    refundPayment: vi.fn(async () => 'refund-id'),
    getOrCreateAccount: vi.fn(async (_u: string, _t: string, name: string) => `acct-${name}`),
    // For the pre-#85 id-recovery replay only:
    createSale: vi.fn(async () => ({
      data: { invoice: { id: 'hl-inv-recovered', invoiceNumber: 'INV-9' }, transaction: { id: 'hl-txn-recovered' } },
    })),
    createPayment: vi.fn(async () => undefined),
    createCustomer: vi.fn(async () => ({ id: 'cust-1', accountId: 'acct-1', idempotent: true })),
  };
});
// Bill engine render (used by syncSale during the id-recovery replay) → a fixed URL, no network.
vi.mock('./billEngine.js', async (importActual) => {
  const actual = await importActual<typeof import('./billEngine.js')>();
  return { ...actual, renderInvoice: vi.fn(async () => 'https://pdf/x.pdf') };
});

import { cancelInvoice, getInvoice, HlHttpError, refundPayment, reverseTransaction } from './hl.js';
import { voidSaleCore, VoidPermanentError, voidSourceId } from './syncWorker.js';

const cfg: SyncConfig = {
  gatewayBaseUrl: 'g',
  hlBaseUrl: 'h',
  adminToken: 't',
  projectId: 'p',
  billEngineUrl: 'b',
};

const DELETE = FieldValue.delete();
function isDelete(v: unknown): boolean {
  return typeof (v as { isEqual?: (o: unknown) => boolean })?.isEqual === 'function' && DELETE.isEqual(v as never);
}

/** In-memory Firestore fake with transaction support, enough for `voidSaleCore`. */
function makeFakeDb(seed: Record<string, Record<string, unknown>> = {}) {
  const store: Record<string, Record<string, unknown>> = {};
  for (const [k, v] of Object.entries(seed)) store[k] = { ...v };

  const applySet = (path: string, data: Record<string, unknown>, merge: boolean) => {
    const next: Record<string, unknown> = merge ? { ...(store[path] ?? {}) } : {};
    for (const [k, v] of Object.entries(data)) {
      if (isDelete(v)) delete next[k];
      else next[k] = v;
    }
    store[path] = next;
  };
  const snap = (path: string) => ({
    exists: path in store,
    data: () => store[path],
    get: (field: string) => store[path]?.[field],
  });
  const ref = (name: string, id: string) => ({
    id,
    path: `${name}/${id}`,
    get: async () => snap(`${name}/${id}`),
    set: async (data: Record<string, unknown>, opts?: { merge?: boolean }) =>
      applySet(`${name}/${id}`, data, opts?.merge ?? false),
    update: async (data: Record<string, unknown>) => applySet(`${name}/${id}`, data, true),
  });
  const db = {
    collection: (name: string) => ({ doc: (id: string) => ref(name, id) }),
    runTransaction: async (fn: (txn: unknown) => Promise<unknown>) => {
      const txn = {
        get: (r: { path: string }) => Promise.resolve(snap(r.path)),
        set: (r: { path: string }, data: Record<string, unknown>, opts?: { merge?: boolean }) =>
          applySet(r.path, data, opts?.merge ?? false),
        update: (r: { path: string }, data: Record<string, unknown>) => applySet(r.path, data, true),
        delete: (r: { path: string }) => {
          delete store[r.path];
        },
      };
      return fn(txn);
    },
  };
  return { db: db as unknown as Firestore, store };
}

/** A SYNCED, invoiced sale of one unit (ser1 / IMEI 111), paid as given. */
function seedSale(over: Record<string, unknown> = {}) {
  return {
    'users/u-admin': { role: 'admin' },
    'users/u-member': { role: 'member' },
    'sales/s1': {
      saleId: 's1',
      status: 'COMPLETED',
      syncStatus: 'SYNCED',
      hlInvoiceId: 'hl-inv-1',
      hlSaleId: 'hl-txn-1',
      amountPaid: '0',
      payments: { cash: '0', card: '0', bank: '0' },
      lines: [{ kind: 'INVENTORY', imei: '111', serialId: 'ser1', productId: 'p1', label: 'iPhone', netPrice: '100' }],
      ...over,
    },
    'serials/ser1': { imei: '111', productId: 'p1', status: 'SOLD', saleId: 's1', isActive: true },
  };
}

beforeEach(() => vi.clearAllMocks());
afterEach(() => vi.restoreAllMocks());

describe('voidSourceId', () => {
  it('is a stable per-refund-leg idempotency key', () => {
    expect(voidSourceId('s1', 'refund_cash')).toBe('void_s1:refund_cash');
    expect(voidSourceId('s1', 'refund_card')).toBe('void_s1:refund_card');
    expect(voidSourceId('s1', 'refund_bank')).toBe('void_s1:refund_bank');
  });
});

describe('voidSaleCore — happy paths', () => {
  it('unpaid, synced, invoiced: cancels invoice, no refund, restocks, marks VOIDED', async () => {
    const { db, store } = makeFakeDb(seedSale());
    const res = await voidSaleCore(db, cfg, 's1', 'u-admin', 'wrong customer', new Date());

    expect(res).toEqual({ status: 'VOIDED' });
    expect(cancelInvoice).toHaveBeenCalledWith('h', 'fake-hl-token', 'hl-inv-1', 'wrong customer');
    expect(refundPayment).not.toHaveBeenCalled();
    // Unit back on the shelf, saleId cleared, imeiIndex restored.
    expect(store['serials/ser1'].status).toBe('IN_STOCK');
    expect(store['serials/ser1'].saleId).toBeUndefined();
    expect(store['imeiIndex/111']).toEqual({ imei: '111', serialId: 'ser1', productId: 'p1' });
    // Sale settled.
    expect(store['sales/s1'].status).toBe('VOIDED');
    expect(store['sales/s1'].voidStatus).toBe('DONE');
    expect(store['sales/s1'].voidReason).toBe('wrong customer');
    expect(store['sales/s1'].hlVoidTxnId).toBe('rev-1');
  });

  it('paid sale: refunds the amount split across the SAME accounts the payment used', async () => {
    const { db } = makeFakeDb(
      seedSale({ amountPaid: '300', payments: { cash: '200', card: '100', bank: '0' } }),
    );
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'return', new Date());

    expect(cancelInvoice).toHaveBeenCalledTimes(1);
    // Cash → Cash account; card → Bank account (inverting syncSale). Two legs, targeted by account.
    expect(refundPayment).toHaveBeenCalledTimes(2);
    expect(refundPayment).toHaveBeenCalledWith('h', 'fake-hl-token', expect.objectContaining({
      invoiceId: 'hl-inv-1', amount: '200', paymentAccountId: 'acct-Cash', sourceId: 'void_s1:refund_cash',
    }));
    expect(refundPayment).toHaveBeenCalledWith('h', 'fake-hl-token', expect.objectContaining({
      invoiceId: 'hl-inv-1', amount: '100', paymentAccountId: 'acct-Bank', sourceId: 'void_s1:refund_card',
    }));
  });

  it('amountPaid == 0: cancel only, no refunds', async () => {
    const { db } = makeFakeDb(seedSale());
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(cancelInvoice).toHaveBeenCalledTimes(1);
    expect(refundPayment).not.toHaveBeenCalled();
  });

  it('partially paid: refunds exactly amountPaid, split by method', async () => {
    const { db } = makeFakeDb(
      seedSale({ amountPaid: '50', payments: { cash: '50', card: '0', bank: '0' } }),
    );
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(refundPayment).toHaveBeenCalledTimes(1);
    expect(refundPayment).toHaveBeenCalledWith('h', 'fake-hl-token', expect.objectContaining({
      amount: '50', paymentAccountId: 'acct-Cash',
    }));
  });
});

describe('voidSaleCore — edge cases', () => {
  it('already VOIDED: no-op success, no HL calls', async () => {
    const { db } = makeFakeDb(seedSale({ status: 'VOIDED', voidStatus: 'DONE' }));
    const res = await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(res).toEqual({ status: 'VOIDED' });
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(refundPayment).not.toHaveBeenCalled();
  });

  it('never reached HL (syncStatus PENDING): skips HL, still restocks + VOIDED', async () => {
    const { db, store } = makeFakeDb(
      seedSale({ syncStatus: 'PENDING', hlInvoiceId: null, hlSaleId: null }),
    );
    const res = await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(res).toEqual({ status: 'VOIDED' });
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(reverseTransaction).not.toHaveBeenCalled();
    expect(store['serials/ser1'].status).toBe('IN_STOCK');
    expect(store['sales/s1'].status).toBe('VOIDED');
  });

  it('no cancellable invoice (only a transaction): reverses the SALE transaction instead', async () => {
    const { db } = makeFakeDb(seedSale({ hlInvoiceId: null, hlSaleId: 'hl-txn-1' }));
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(reverseTransaction).toHaveBeenCalledWith('h', 'fake-hl-token', 'hl-txn-1', 'r');
    expect(cancelInvoice).not.toHaveBeenCalled();
  });

  it('refund NOTHING_TO_REFUND (cancel already returned the money): void still completes', async () => {
    // Cancelling a paid, invoice-applied sale clears the invoice's paid balance itself, so the
    // per-method refund 422s with NOTHING_TO_REFUND — benign for a void (money already back).
    vi.mocked(refundPayment).mockRejectedValueOnce(
      new HlHttpError(422, 'HL POST /refunds → HTTP 422', '{"code":"NOTHING_TO_REFUND"}'),
    );
    const { db, store } = makeFakeDb(
      seedSale({ amountPaid: '1353', payments: { cash: '1353', card: '0', bank: '0' } }),
    );
    const res = await voidSaleCore(db, cfg, 's1', 'u-admin', 'mistake', new Date());

    expect(res).toEqual({ status: 'VOIDED' });
    expect(cancelInvoice).toHaveBeenCalledTimes(1);
    // The void completes despite the refund 422 — stock restored, sale VOIDED, not FAILED.
    expect(store['serials/ser1'].status).toBe('IN_STOCK');
    expect(store['sales/s1'].status).toBe('VOIDED');
    expect(store['sales/s1'].voidStatus).toBe('DONE');
  });

  it('refund with a genuine 422 (INVALID_STATE) still fails the void', async () => {
    vi.mocked(refundPayment).mockRejectedValueOnce(
      new HlHttpError(422, 'HL POST /refunds → HTTP 422', '{"code":"INVALID_STATE"}'),
    );
    const { db, store } = makeFakeDb(
      seedSale({ amountPaid: '100', payments: { cash: '100', card: '0', bank: '0' } }),
    );
    await expect(voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
    expect(store['sales/s1'].status).not.toBe('VOIDED');
    expect(store['sales/s1'].voidStatus).toBe('FAILED');
  });

  it('a synced sale whose PDF failed still cancels HLs real invoice (Decision 2)', async () => {
    // invoiceStatus FAILED tracks only the PDF; HL minted the invoice at sale time → cancel it.
    const { db, store } = makeFakeDb(seedSale({ invoiceStatus: 'FAILED' }));
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(cancelInvoice).toHaveBeenCalledWith('h', 'fake-hl-token', 'hl-inv-1', 'r');
    expect(store['sales/s1'].status).toBe('VOIDED');
  });

  it('re-used IMEI index (different serial): fails BEFORE any HL reversal; index + ledger untouched', async () => {
    const seed = seedSale({ amountPaid: '100', payments: { cash: '100', card: '0', bank: '0' } });
    seed['imeiIndex/111'] = { imei: '111', serialId: 'OTHER-serial', productId: 'p9' }; // re-added handset
    const { db, store } = makeFakeDb(seed);

    await expect(voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
    // Pre-flight guard runs before HL: nothing is cancelled or refunded (ledger untouched).
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(refundPayment).not.toHaveBeenCalled();
    // The other unit's index is untouched; the sale is left FAILED, not VOIDED, with no HL trail.
    expect(store['imeiIndex/111']).toEqual({ imei: '111', serialId: 'OTHER-serial', productId: 'p9' });
    expect(store['sales/s1'].status).not.toBe('VOIDED');
    expect(store['sales/s1'].voidStatus).toBe('FAILED');
    expect(store['sales/s1'].hlVoidTxnId).toBeUndefined();
    expect(store['sales/s1'].voidError).toContain('111');
  });

  it('refund ids are persisted immediately, so a later step-4 failure cannot lose the trail', async () => {
    // The pre-flight guard passes (no index yet), the refund succeeds, then — simulating the tight
    // race — the handset is re-added under a different serial right after, so the atomic restore's
    // guard trips and the void fails. The refund id must already be on the doc regardless (#85).
    const seed = seedSale({ amountPaid: '100', payments: { cash: '100', card: '0', bank: '0' } });
    const { db, store } = makeFakeDb(seed);
    vi.mocked(refundPayment).mockImplementationOnce(async () => {
      store['imeiIndex/111'] = { imei: '111', serialId: 'OTHER', productId: 'p9' }; // re-added post-refund
      return 'refund-id';
    });

    await expect(voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
    expect(refundPayment).toHaveBeenCalledTimes(1);
    // Trail survived the failed void: the refund id is on the doc even though status stayed FAILED.
    expect(store['sales/s1'].hlRefundIds).toEqual(['refund-id']);
    expect(store['sales/s1'].status).not.toBe('VOIDED');
  });

  it('mid-way resume (HL already reversed, Firestore not): does not cancel or reverse again', async () => {
    // hlVoidTxnId already recorded → the ledger was reversed on a prior attempt.
    const { db, store } = makeFakeDb(seedSale({ hlVoidTxnId: 'rev-1' }));
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(reverseTransaction).not.toHaveBeenCalled();
    expect(store['sales/s1'].status).toBe('VOIDED');
  });

  it('resume when HL invoice already CANCELLED: skips cancel (cancel is not idempotent)', async () => {
    vi.mocked(getInvoice).mockResolvedValueOnce({ id: 'hl-inv-1', status: 'CANCELLED' });
    const { db, store } = makeFakeDb(seedSale());
    await voidSaleCore(db, cfg, 's1', 'u-admin', 'r', new Date());
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(store['sales/s1'].status).toBe('VOIDED');
  });

  // NOTE: the pre-#85 id-recovery path (a SYNCED sale with no persisted hlInvoiceId/hlSaleId, which
  // replays the idempotent `syncSale` to recover them) isn't unit-tested here: `syncSale` reads the
  // default `getFirestore()` internally rather than the injected fake, so it needs a live app. It is
  // covered by the manual before/after trial-balance verification on the dev company (AC #3).
});

describe('voidSaleCore — server-side gates', () => {
  it('non-admin is blocked even when the request reached the doc (Admin SDK bypasses rules)', async () => {
    const { db, store } = makeFakeDb(seedSale());
    await expect(voidSaleCore(db, cfg, 's1', 'u-member', 'r', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
    expect(cancelInvoice).not.toHaveBeenCalled();
    expect(store['sales/s1'].status).not.toBe('VOIDED');
    expect(store['sales/s1'].voidStatus).toBe('FAILED');
  });

  it('a blank reason is rejected server-side', async () => {
    const { db } = makeFakeDb(seedSale());
    await expect(voidSaleCore(db, cfg, 's1', 'u-admin', '   ', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
    expect(cancelInvoice).not.toHaveBeenCalled();
  });

  it('a missing sale is a permanent failure', async () => {
    const { db } = makeFakeDb({ 'users/u-admin': { role: 'admin' } });
    await expect(voidSaleCore(db, cfg, 'nope', 'u-admin', 'r', new Date())).rejects.toBeInstanceOf(VoidPermanentError);
  });
});
