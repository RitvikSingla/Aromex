import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SyncConfig } from './config.js';

// A mutable holder for the fake Firestore the mocked getFirestore() returns (hoisted so the
// vi.mock factory can reference it).
const h = vi.hoisted(() => ({ db: undefined as unknown }));

vi.mock('firebase-admin/firestore', () => ({
  getFirestore: () => h.db,
  FieldValue: { serverTimestamp: () => 'ts', delete: () => 'del' },
}));

// Mock the network-touching HL client; keep the real module otherwise.
vi.mock('./hl.js', async (importActual) => {
  const actual = await importActual<typeof import('./hl.js')>();
  return {
    ...actual,
    getHlToken: vi.fn(async () => 'fake-hl-token'),
    getOrCreateAccount: vi.fn(async (_u: string, _t: string, name: string) => `acct-${name}`),
    createCustomerPurchase: vi.fn(async () => undefined),
    createCustomerPayout: vi.fn(async () => 'payout-txn-1'),
    createCustomer: vi.fn(async () => ({ id: 'cust-1', accountId: 'acct-1', idempotent: true })),
  };
});

import { createCustomerPayout, createCustomerPurchase, getOrCreateAccount } from './hl.js';
import { commissionSourceId, syncCommission, type CommissionData } from './syncWorker.js';

const cfg: SyncConfig = { gatewayBaseUrl: 'g', hlBaseUrl: 'h', adminToken: 't', projectId: 'p' };

/** Minimal in-memory Firestore fake: collection().doc().get()/.set(). */
function makeFakeDb(seed: Record<string, Record<string, unknown>> = {}) {
  const store: Record<string, Record<string, unknown>> = {};
  for (const [k, v] of Object.entries(seed)) store[k] = { ...v };
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
  return { db, store };
}

/** An already-synced payee, so resolvePartyHlCustomerId returns the id without touching HL. */
function seedPayee(payeeId = 'rajesh') {
  return { [`entities/${payeeId}`]: { hlCustomerId: `cust-${payeeId}`, syncStatus: 'SYNCED' } };
}

const accrueOnly: CommissionData = {
  payeeEntityId: 'rajesh',
  locationAttributeId: 'loc-a',
  ruleId: 'r1',
  unitCount: 12,
  basisAmount: '0',
  amount: '60.00',
  paidCash: '0',
  paidBank: '0',
  sourceBatchId: 'batch-1',
  createdBy: 'u1',
  syncStatus: 'PENDING',
};

describe('commissionSourceId', () => {
  it('is deterministic and distinct per leg', () => {
    expect(commissionSourceId('c1', 'accrue')).toBe('commission_c1');
    expect(commissionSourceId('c1', 'payout_cash')).toBe('commission_c1:payout_cash');
    expect(commissionSourceId('c1', 'payout_bank')).toBe('commission_c1:payout_bank');
    // Same commission id → same keys every call (the idempotency contract).
    expect(commissionSourceId('c1', 'accrue')).toBe(commissionSourceId('c1', 'accrue'));
  });
});

describe('syncCommission', () => {
  beforeEach(() => vi.clearAllMocks());

  it('add to balance (accrue only): posts a customer-purchase against Commission expense, no payout', async () => {
    const { db, store } = makeFakeDb(seedPayee());
    h.db = db;

    await syncCommission('c1', accrueOnly, cfg);

    expect(vi.mocked(getOrCreateAccount)).toHaveBeenCalledWith(cfg.hlBaseUrl, 'fake-hl-token', 'Commission', 'EXPENSE');
    expect(vi.mocked(createCustomerPurchase)).toHaveBeenCalledOnce();
    expect(vi.mocked(createCustomerPurchase).mock.calls[0][2]).toMatchObject({
      customerId: 'cust-rajesh',
      amount: '60.00',
      expenseAccountId: 'acct-Commission',
      sourceId: 'commission_c1',
    });
    expect(vi.mocked(createCustomerPayout)).not.toHaveBeenCalled();
    expect(store['commissions/c1'].syncStatus).toBe('SYNCED');
  });

  it('give now split: accrues the full amount, then a cash + a bank payout for each non-zero leg', async () => {
    const { db } = makeFakeDb(seedPayee());
    h.db = db;

    // $60 owed, given now as $40 cash + $20 bank.
    await syncCommission('c2', { ...accrueOnly, paidCash: '40.00', paidBank: '20.00' }, cfg);

    expect(vi.mocked(createCustomerPurchase).mock.calls[0][2]).toMatchObject({ amount: '60.00', sourceId: 'commission_c2' });
    expect(vi.mocked(createCustomerPayout)).toHaveBeenCalledTimes(2);
    const payouts = vi.mocked(createCustomerPayout).mock.calls.map((c) => c[2]);
    expect(payouts).toContainEqual(expect.objectContaining({ amount: '40.00', method: 'CASH', sourceId: 'commission_c2:payout_cash' }));
    expect(payouts).toContainEqual(expect.objectContaining({ amount: '20.00', method: 'BANK', sourceId: 'commission_c2:payout_bank' }));
  });

  it('give now, one method only: a zero leg posts no payout', async () => {
    const { db } = makeFakeDb(seedPayee());
    h.db = db;

    await syncCommission('c4', { ...accrueOnly, paidCash: '60.00', paidBank: '0' }, cfg);

    expect(vi.mocked(createCustomerPayout)).toHaveBeenCalledOnce();
    expect(vi.mocked(createCustomerPayout).mock.calls[0][2]).toMatchObject({ method: 'CASH', sourceId: 'commission_c4:payout_cash' });
  });

  it('replaying does not change the sourceIds — HL dedupes, so no double-post (AC7)', async () => {
    const { db } = makeFakeDb(seedPayee());
    h.db = db;

    await syncCommission('c3', { ...accrueOnly, paidCash: '60.00' }, cfg);
    const firstAccrue = vi.mocked(createCustomerPurchase).mock.calls[0][2].sourceId;
    const firstPayout = vi.mocked(createCustomerPayout).mock.calls[0][2].sourceId;

    // Redelivered trigger → run again with the same doc.
    await syncCommission('c3', { ...accrueOnly, paidCash: '60.00' }, cfg);
    const secondAccrue = vi.mocked(createCustomerPurchase).mock.calls[1][2].sourceId;
    const secondPayout = vi.mocked(createCustomerPayout).mock.calls[1][2].sourceId;

    expect(secondAccrue).toBe(firstAccrue);
    expect(secondPayout).toBe(firstPayout);
    expect(firstAccrue).toBe('commission_c3');
    expect(firstPayout).toBe('commission_c3:payout_cash');
  });

  it('on HL failure: marks the doc FAILED and rethrows', async () => {
    const { db, store } = makeFakeDb(seedPayee());
    h.db = db;
    vi.mocked(createCustomerPurchase).mockRejectedValueOnce(new Error('HL 500'));

    await expect(syncCommission('c5', accrueOnly, cfg)).rejects.toThrow('HL 500');
    expect(store['commissions/c5'].syncStatus).toBe('FAILED');
    expect(store['commissions/c5'].hlSyncError).toContain('HL 500');
  });
});
