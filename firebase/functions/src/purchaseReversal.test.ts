import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';

// Only the network-touching HL calls are replaced; the real module's error classes survive.
vi.mock('./hl.js', async (importActual) => {
  const actual = await importActual<typeof import('./hl.js')>();
  return {
    ...actual,
    getHlToken: vi.fn(async () => 'fake-hl-token'),
    reverseTransaction: vi.fn(async () => ({ reversalTxnId: 'rev-1' })),
  };
});

// `syncPurchase` is the id-recovery path for pre-#106 batches; stubbed so the reversal tests
// don't drag the whole posting worker in.
vi.mock('./syncWorker.js', async (importActual) => {
  const actual = await importActual<typeof import('./syncWorker.js')>();
  return { ...actual, syncPurchase: vi.fn(async () => undefined) };
});

import { Timestamp } from 'firebase-admin/firestore';
import { reverseTransaction } from './hl.js';
import { VoidPermanentError } from './syncWorker.js';
import { assertBatchIsWhole, reversePurchaseCore } from './purchaseReversal.js';

const fakeCfg: SyncConfig = { gatewayBaseUrl: 'x', hlBaseUrl: 'y', adminToken: 'z', projectId: 'p' };

/**
 * In-memory Firestore fake with the surface this worker actually uses: doc get/set, a single
 * `where(field, '==', value)` query, and `runTransaction` with get/set/delete. Deliberately not a
 * general Firestore mock — it exists so the reversal's ordering and idempotency can be asserted.
 */
function makeFakeDb(seed: Record<string, Record<string, unknown>> = {}) {
  const store: Record<string, Record<string, unknown>> = JSON.parse(JSON.stringify(seed));

  /** Applies a merge patch, honouring FieldValue.delete() and serverTimestamp() sentinels. */
  function merge(path: string, data: Record<string, unknown>) {
    const next = { ...(store[path] ?? {}) };
    for (const [k, v] of Object.entries(data)) {
      const sentinel = (v as { methodName?: string } | undefined)?.methodName;
      if (sentinel === 'FieldValue.delete') delete next[k];
      else if (sentinel === 'FieldValue.serverTimestamp') next[k] = '<ts>';
      else next[k] = v;
    }
    store[path] = next;
  }

  function docHandle(path: string) {
    return {
      id: path.split('/')[1],
      get: async () => snapshotOf(path),
      set: async (data: Record<string, unknown>) => merge(path, data),
    };
  }

  function snapshotOf(path: string) {
    return {
      id: path.split('/')[1],
      exists: path in store,
      data: () => store[path],
      get: (field: string) => store[path]?.[field],
    };
  }

  const db = {
    collection: (name: string) => ({
      doc: (id: string) => docHandle(`${name}/${id}`),
      where: (field: string, _op: string, value: unknown) => ({
        get: async () => {
          const docs = Object.keys(store)
            .filter((p) => p.startsWith(`${name}/`) && store[p][field] === value)
            .map((p) => snapshotOf(p));
          return { docs, empty: docs.length === 0 };
        },
      }),
    }),
    runTransaction: async <T>(fn: (txn: unknown) => Promise<T>): Promise<T> => {
      const txn = {
        get: async (d: { id: string } | { path: string }) => snapshotOf(pathOf(d)),
        set: (d: unknown, data: Record<string, unknown>) => merge(pathOf(d), data),
        delete: (d: unknown) => {
          delete store[pathOf(d)];
        },
      };
      // Returns the callback's value — the reversal lease is decided inside a transaction.
      return fn(txn);
    },
  };

  // The fake's doc handles carry their own path; collection().doc() is the only producer.
  const paths = new WeakMap<object, string>();
  function pathOf(d: unknown): string {
    const known = paths.get(d as object);
    if (known) return known;
    return (d as { __path: string }).__path;
  }

  // Re-wrap so every handle carries __path for pathOf().
  const withPaths = {
    ...db,
    collection: (name: string) => ({
      doc: (id: string) => Object.assign(docHandle(`${name}/${id}`), { __path: `${name}/${id}` }),
      where: db.collection(name).where,
    }),
  };

  return { db: withPaths as unknown as Firestore, store };
}

/** A synced batch of two in-stock units, with all three ledger legs recorded. */
function seedBatch(over: { purchase?: Record<string, unknown>; units?: Record<string, unknown> } = {}) {
  return {
    'users/admin-1': { role: 'admin' },
    'users/member-1': { role: 'member' },
    'purchases/p1': {
      partyEntityId: 'e1',
      totalCost: '1000.00',
      cashPaid: '400.00',
      bankPaid: '0',
      unitCount: 2,
      syncStatus: 'SYNCED',
      status: 'ACTIVE',
      hlPurchaseTxnId: 'txn-purchase',
      hlPayoutCashTxnId: 'txn-payout-cash',
      createdBy: 'admin-1',
      ...over.purchase,
    },
    'serials/s1': { serialId: 's1', imei: '111', status: 'IN_STOCK', isActive: true, purchaseId: 'p1', ...over.units },
    'serials/s2': { serialId: 's2', imei: '222', status: 'IN_STOCK', isActive: true, purchaseId: 'p1' },
    'imeiIndex/111': { imei: '111', serialId: 's1', productId: 'sku' },
    'imeiIndex/222': { imei: '222', serialId: 's2', productId: 'sku' },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(reverseTransaction).mockResolvedValue({ reversalTxnId: 'rev-1' });
});

describe('assertBatchIsWhole', () => {
  const purchase = { partyEntityId: 'e1', totalCost: '1000.00', unitCount: 2 };

  it('accepts a batch whose units are all present and in stock', () => {
    expect(() =>
      assertBatchIsWhole('p1', purchase, [
        { serialId: 's1', imei: '111', status: 'IN_STOCK', isActive: true },
        { serialId: 's2', imei: '222', status: 'IN_STOCK', isActive: true },
      ]),
    ).not.toThrow();
  });

  it('rejects a batch with a sold unit, naming the IMEI', () => {
    expect(() =>
      assertBatchIsWhole('p1', purchase, [
        { serialId: 's1', imei: '111', status: 'IN_STOCK', isActive: true },
        { serialId: 's2', imei: '222', status: 'SOLD', isActive: true },
      ]),
    ).toThrow(/left stock.*222/s);
  });

  it('rejects a batch with an individually removed unit', () => {
    expect(() =>
      assertBatchIsWhole('p1', purchase, [
        { serialId: 's1', imei: '111', status: 'IN_STOCK', isActive: true },
        { serialId: 's2', imei: '222', status: 'IN_STOCK', isActive: false },
      ]),
    ).toThrow(/removed individually/);
  });

  it('rejects a pre-tagging batch rather than reversing zero phones', () => {
    expect(() => assertBatchIsWhole('p1', purchase, [])).toThrow(/predates unit tagging/);
  });

  it('rejects a batch whose units are only partly accounted for', () => {
    expect(() =>
      assertBatchIsWhole('p1', { ...purchase, unitCount: 5 }, [
        { serialId: 's1', imei: '111', status: 'IN_STOCK', isActive: true },
        { serialId: 's2', imei: '222', status: 'IN_STOCK', isActive: true },
      ]),
    ).toThrow(/only 2 of 5/);
  });
});

describe('reversePurchaseCore', () => {
  it('reverses every ledger leg, pulls the stock and frees the IMEIs', async () => {
    const { db, store } = makeFakeDb(seedBatch());

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));

    // Both legs reversed, by id.
    expect(vi.mocked(reverseTransaction).mock.calls.map((c) => c[2])).toEqual([
      'txn-purchase',
      'txn-payout-cash',
    ]);
    expect(store['purchases/p1'].status).toBe('REVERSED');
    expect(store['purchases/p1'].reversalStatus).toBe('DONE');
    expect(store['purchases/p1'].hlReversalTxnIds).toEqual({
      purchase: 'rev-1',
      payout_cash: 'rev-1',
    });
    // Stock is archived, never hard-deleted, and its IMEIs are free to be re-added.
    expect(store['serials/s1'].isActive).toBe(false);
    expect(store['serials/s2'].isActive).toBe(false);
    expect(store['imeiIndex/111']).toBeUndefined();
    expect(store['imeiIndex/222']).toBeUndefined();
  });

  it('is a no-op on an already reversed batch', async () => {
    const { db } = makeFakeDb(seedBatch({ purchase: { status: 'REVERSED' } }));
    const res = await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'again', new Date(0));
    expect(res).toEqual({ status: 'REVERSED' });
    expect(reverseTransaction).not.toHaveBeenCalled();
  });

  it('refuses a non-admin, server-side, and touches no ledger', async () => {
    const { db, store } = makeFakeDb(seedBatch());
    await expect(
      reversePurchaseCore(db, fakeCfg, 'p1', 'member-1', 'Wrong supplier', new Date(0)),
    ).rejects.toThrow(VoidPermanentError);
    expect(reverseTransaction).not.toHaveBeenCalled();
    expect(store['purchases/p1'].status).toBe('ACTIVE');
    expect(store['serials/s1'].isActive).toBe(true);
  });

  it('requires a reason', async () => {
    const { db } = makeFakeDb(seedBatch());
    await expect(
      reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', '   ', new Date(0)),
    ).rejects.toThrow(/reason is required/);
    expect(reverseTransaction).not.toHaveBeenCalled();
  });

  it('blocks a sold unit BEFORE any ledger call, leaving the books untouched', async () => {
    const { db, store } = makeFakeDb(seedBatch({ units: { status: 'SOLD' } }));
    await expect(
      reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0)),
    ).rejects.toThrow(/left stock/);
    // The crux: nothing posted, so the batch can still be reversed once the sale is voided.
    expect(reverseTransaction).not.toHaveBeenCalled();
    expect(store['purchases/p1'].status).toBe('ACTIVE');
    expect(store['purchases/p1'].reversalStatus).toBe('FAILED');
    expect(store['serials/s1'].isActive).toBe(true);
  });

  it('a retry after a mid-way crash re-reverses nothing', async () => {
    const { db, store } = makeFakeDb(
      seedBatch({ purchase: { hlReversalTxnIds: { purchase: 'rev-earlier' } } }),
    );

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));

    // Only the payout leg is left to reverse; the purchase leg's recorded id short-circuits it.
    expect(vi.mocked(reverseTransaction).mock.calls.map((c) => c[2])).toEqual(['txn-payout-cash']);
    expect(store['purchases/p1'].hlReversalTxnIds).toEqual({
      purchase: 'rev-earlier',
      payout_cash: 'rev-1',
    });
  });

  it('records a leg HL acknowledged without an id, so a retry cannot double-reverse it', async () => {
    vi.mocked(reverseTransaction).mockResolvedValue({ reversalTxnId: undefined });
    const { db, store } = makeFakeDb(seedBatch({ purchase: { hlPayoutCashTxnId: undefined } }));

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));

    expect(store['purchases/p1'].hlReversalTxnIds).toEqual({ purchase: '(reversed)' });
  });

  it('reverses the commissions the batch earned, so no debt survives the stock', async () => {
    const { db, store } = makeFakeDb({
      ...seedBatch(),
      'commissions/c1': {
        payeeEntityId: 'e9',
        amount: '50.00',
        sourceBatchId: 'p1',
        hlAccrueTxnId: 'txn-commission',
        hlPayoutCashTxnId: 'txn-commission-cash',
      },
      // A commission from a DIFFERENT batch must not be touched.
      'commissions/c2': { payeeEntityId: 'e9', amount: '10.00', sourceBatchId: 'other', hlAccrueTxnId: 'txn-other' },
    });

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));

    expect(vi.mocked(reverseTransaction).mock.calls.map((c) => c[2])).toEqual([
      'txn-purchase',
      'txn-payout-cash',
      'txn-commission',
      'txn-commission-cash',
    ]);
    expect(store['commissions/c1'].status).toBe('REVERSED');
    expect(store['commissions/c2'].status).toBeUndefined();
  });

  it('skips the ledger entirely for a batch that never reached HL', async () => {
    const { db, store } = makeFakeDb(
      seedBatch({ purchase: { syncStatus: 'FAILED', hlPurchaseTxnId: undefined, hlPayoutCashTxnId: undefined } }),
    );

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Never posted', new Date(0));

    expect(reverseTransaction).not.toHaveBeenCalled();
    // Stock still comes off: there is nothing to un-book, but the phones were never bought.
    expect(store['purchases/p1'].status).toBe('REVERSED');
    expect(store['serials/s1'].isActive).toBe(false);
  });

  it('a batch reversed while never synced does not later get posted', async () => {
    // The reversal writes reversalStatus: PENDING, which re-fires onPurchaseWrite. The guard in
    // that trigger is what stops the sync branch from booking a purchase mid-un-booking; this
    // asserts the state it keys on actually gets written.
    const { db, store } = makeFakeDb(
      seedBatch({ purchase: { syncStatus: 'PENDING', hlPurchaseTxnId: undefined, hlPayoutCashTxnId: undefined } }),
    );

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Entered twice', new Date(0));

    expect(store['purchases/p1'].reversalStatus).toBe('DONE');
    expect(store['purchases/p1'].status).toBe('REVERSED');
  });

  it('refuses to run while another reversal holds the lease', async () => {
    // HL's reverse endpoint takes no sourceId, so it is NOT idempotent: two overlapping runs
    // would each post a reversal and credit the party twice.
    const { db, store } = makeFakeDb(seedBatch());
    store['purchases/p1'].reversalLeaseUntil = Timestamp.fromMillis(600_000);

    await expect(
      reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0)),
    ).rejects.toThrow(/already running/);

    expect(reverseTransaction).not.toHaveBeenCalled();
    // The other run's lease is untouched — releasing one we didn't take would defeat the point.
    expect(store['purchases/p1'].reversalLeaseUntil).toBeDefined();
    expect(store['purchases/p1'].status).toBe('ACTIVE');
  });

  it('takes an expired lease, so a run that died cannot block the batch forever', async () => {
    const { db, store } = makeFakeDb(seedBatch());
    store['purchases/p1'].reversalLeaseUntil = Timestamp.fromMillis(1_000);

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(60_000));

    expect(store['purchases/p1'].status).toBe('REVERSED');
  });

  it('releases the lease on the way out, so a redelivery can finish the job', async () => {
    const { db, store } = makeFakeDb(seedBatch());
    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));
    expect(store['purchases/p1'].reversalLeaseUntil).toBeUndefined();
  });

  it('releases the lease even when the reversal fails', async () => {
    const { db, store } = makeFakeDb(seedBatch({ units: { status: 'SOLD' } }));
    await expect(
      reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0)),
    ).rejects.toThrow(/left stock/);
    // Otherwise a blocked batch would stay locked for the whole lease window after every attempt.
    expect(store['purchases/p1'].reversalLeaseUntil).toBeUndefined();
  });

  it('leaves an IMEI that was re-added under a different unit alone', async () => {
    const seed = seedBatch();
    // The handset came back and was re-added as a fresh unit; the index now points elsewhere.
    seed['imeiIndex/111'] = { imei: '111', serialId: 's99', productId: 'sku' };
    const { db, store } = makeFakeDb(seed);

    await reversePurchaseCore(db, fakeCfg, 'p1', 'admin-1', 'Wrong supplier', new Date(0));

    // Deleting it would have freed an IMEI that is genuinely in stock under s99.
    expect(store['imeiIndex/111']).toEqual({ imei: '111', serialId: 's99', productId: 'sku' });
    expect(store['imeiIndex/222']).toBeUndefined();
    expect(store['serials/s1'].isActive).toBe(false);
  });
});
