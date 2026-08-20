import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';

// Spread the real module so the genuine `HlHttpError` class survives (syncWorker uses
// `instanceof HlHttpError`), while the network-touching functions are replaced with mocks.
vi.mock('./hl.js', async (importActual) => {
  const actual = await importActual<typeof import('./hl.js')>();
  return {
    ...actual,
    getHlToken: vi.fn(async () => 'fake-hl-token'),
    createCustomer: vi.fn(async () => ({ id: 'cust-1', accountId: 'acct-1', idempotent: false })),
    updateCustomer: vi.fn(async () => undefined),
    createCustomerPurchase: vi.fn(),
    createCustomerPayout: vi.fn(),
    createSale: vi.fn(),
    createPayment: vi.fn(),
    getOrCreateAccount: vi.fn(),
    postOpeningBalance: vi.fn(),
  };
});

import { createCustomer, HlHttpError } from './hl.js';
import {
  mapDirectionToHl,
  openingSourceId,
  primaryPhone,
  profileChanged,
  purchaseSourceId,
  repartyHlCustomerId,
  resolvePartyHlCustomerId,
  saleSourceId,
  UNSPECIFIED_SUPPLIER_ID,
  WALK_IN_CUSTOMER_ID,
  withCustomerSelfHeal,
  type EntityData,
} from './syncWorker.js';

/** Minimal in-memory Firestore fake — just enough surface (`collection().doc().get()/.set()`)
 *  for `resolvePartyHlCustomerId`'s reads/writes. Not a general-purpose Firestore mock. */
function makeFakeDb(seed: Record<string, Record<string, unknown>> = {}) {
  const store: Record<string, Record<string, unknown>> = { ...seed };
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

const fakeCfg: SyncConfig = { gatewayBaseUrl: 'x', hlBaseUrl: 'y', adminToken: 'z', projectId: 'p' };

describe('sync worker pure helpers', () => {
  it('primaryPhone returns the first phone or undefined', () => {
    expect(primaryPhone(['111', '222'])).toBe('111');
    expect(primaryPhone([])).toBeUndefined();
    expect(primaryPhone(undefined)).toBeUndefined();
  });

  it('mapDirectionToHl maps our CREDIT to HL PAYABLE', () => {
    expect(mapDirectionToHl('CREDIT')).toBe('PAYABLE');
    expect(mapDirectionToHl('RECEIVABLE')).toBe('RECEIVABLE');
  });

  it('openingSourceId is a stable extensible key', () => {
    expect(openingSourceId('e1')).toBe('entity_e1:opening');
  });

  it('purchaseSourceId is a stable per-leg idempotency key', () => {
    expect(purchaseSourceId('p1', 'purchase')).toBe('purchase_p1:purchase');
    expect(purchaseSourceId('p1', 'payout_cash')).toBe('purchase_p1:payout_cash');
    expect(purchaseSourceId('p1', 'payout_bank')).toBe('purchase_p1:payout_bank');
  });

  it('the reserved Unspecified Supplier id is fixed (never generated)', () => {
    expect(UNSPECIFIED_SUPPLIER_ID).toBe('unspecified-supplier');
  });

  it('saleSourceId is a stable per-leg idempotency key', () => {
    expect(saleSourceId('s1', 'sale')).toBe('sale_s1:sale');
    expect(saleSourceId('s1', 'payment_cash')).toBe('sale_s1:payment_cash');
    expect(saleSourceId('s1', 'payment_card')).toBe('sale_s1:payment_card');
    expect(saleSourceId('s1', 'payment_bank')).toBe('sale_s1:payment_bank');
  });

  it('the reserved Walk-in Customer id is fixed (never generated)', () => {
    expect(WALK_IN_CUSTOMER_ID).toBe('walk-in-customer');
  });

  describe('profileChanged (fields HL stores)', () => {
    const base: EntityData = { name: 'Acme', email: 'a@b.com', phones: ['111', '999'] };

    it('is false when name/email/primary-phone are unchanged (extra phones ignored)', () => {
      expect(profileChanged(base, { ...base, phones: ['111', '000'] })).toBe(false);
    });

    it('detects a name change', () => {
      expect(profileChanged(base, { ...base, name: 'Acme Inc' })).toBe(true);
    });

    it('detects an email change (null vs value)', () => {
      expect(profileChanged(base, { ...base, email: null })).toBe(true);
    });

    it('detects a primary-phone change', () => {
      expect(profileChanged(base, { ...base, phones: ['222', '999'] })).toBe(true);
    });
  });

  describe('resolvePartyHlCustomerId — placeholder bootstrap race (ticket #63 regression)', () => {
    it('bootstrapping a fresh placeholder returns undefined WITHOUT calling createCustomer inline — regression guard for the two-concurrent-createCustomer race', async () => {
      const { db } = makeFakeDb(); // walk-in-customer doc does not exist yet

      const result = await resolvePartyHlCustomerId(db, WALK_IN_CUSTOMER_ID, fakeCfg);

      expect(result).toBeUndefined();
      expect(vi.mocked(createCustomer)).not.toHaveBeenCalled();
    });

    it('an already-synced placeholder returns its cached hlCustomerId without re-creating it', async () => {
      const { db } = makeFakeDb({
        [`entities/${WALK_IN_CUSTOMER_ID}`]: { name: 'Walk-in Customer', hlCustomerId: 'cust-existing' },
      });

      const result = await resolvePartyHlCustomerId(db, WALK_IN_CUSTOMER_ID, fakeCfg);

      expect(result).toBe('cust-existing');
      expect(vi.mocked(createCustomer)).not.toHaveBeenCalled();
    });
  });

  describe('stale cached hlCustomerId self-heal (ticket #73)', () => {
    beforeEach(() => {
      vi.clearAllMocks();
    });

    it('repartyHlCustomerId re-creates the HL customer (idempotent externalId) and writes the corrected id back', async () => {
      const { db, store } = makeFakeDb({
        [`entities/${UNSPECIFIED_SUPPLIER_ID}`]: { name: 'Unspecified Supplier', hlCustomerId: 'cust-old' },
      });
      vi.mocked(createCustomer).mockResolvedValueOnce({ id: 'cust-new', accountId: 'acct-new', idempotent: true });

      const healed = await repartyHlCustomerId(db, UNSPECIFIED_SUPPLIER_ID, fakeCfg, 'tok');

      expect(healed).toBe('cust-new');
      expect(vi.mocked(createCustomer)).toHaveBeenCalledWith(
        fakeCfg.hlBaseUrl,
        'tok',
        expect.objectContaining({ externalId: UNSPECIFIED_SUPPLIER_ID, name: 'Unspecified Supplier' }),
      );
      expect(store[`entities/${UNSPECIFIED_SUPPLIER_ID}`].hlCustomerId).toBe('cust-new');
      expect(store[`entities/${UNSPECIFIED_SUPPLIER_ID}`].hlAccountId).toBe('acct-new');
    });

    it('re-resolves the party and replays the post once when the cached id 404s', async () => {
      const { db, store } = makeFakeDb({
        [`entities/${WALK_IN_CUSTOMER_ID}`]: { name: 'Walk-in Customer', hlCustomerId: 'cust-bad' },
      });
      vi.mocked(createCustomer).mockResolvedValueOnce({ id: 'cust-good', accountId: 'acct-good', idempotent: true });
      const seen: string[] = [];
      const post = vi.fn(async (cid: string) => {
        seen.push(cid);
        if (cid === 'cust-bad') throw new HlHttpError(404, 'HL POST /sales → HTTP 404');
        return 'ok';
      });

      const result = await withCustomerSelfHeal(db, WALK_IN_CUSTOMER_ID, fakeCfg, 'tok', 'cust-bad', post);

      expect(result).toBe('ok');
      expect(seen).toEqual(['cust-bad', 'cust-good']); // failed once, replayed with the healed id
      expect(vi.mocked(createCustomer)).toHaveBeenCalledOnce();
      expect(store[`entities/${WALK_IN_CUSTOMER_ID}`].hlCustomerId).toBe('cust-good');
    });

    it('propagates a non-404 failure without re-resolving', async () => {
      const { db } = makeFakeDb({
        [`entities/${WALK_IN_CUSTOMER_ID}`]: { name: 'Walk-in Customer', hlCustomerId: 'cust-x' },
      });
      const post = vi.fn(async () => {
        throw new HlHttpError(500, 'HL POST /sales → HTTP 500');
      });

      await expect(
        withCustomerSelfHeal(db, WALK_IN_CUSTOMER_ID, fakeCfg, 'tok', 'cust-x', post),
      ).rejects.toThrow('HTTP 500');
      expect(post).toHaveBeenCalledOnce();
      expect(vi.mocked(createCustomer)).not.toHaveBeenCalled();
    });

    it('replays at most once — a persistent 404 after healing propagates (no retry loop)', async () => {
      const { db } = makeFakeDb({
        [`entities/${UNSPECIFIED_SUPPLIER_ID}`]: { name: 'Unspecified Supplier', hlCustomerId: 'cust-bad' },
      });
      vi.mocked(createCustomer).mockResolvedValueOnce({ id: 'cust-still-bad', accountId: 'a', idempotent: true });
      const post = vi.fn(async () => {
        throw new HlHttpError(404, 'HL POST /customer-purchases → HTTP 404');
      });

      await expect(
        withCustomerSelfHeal(db, UNSPECIFIED_SUPPLIER_ID, fakeCfg, 'tok', 'cust-bad', post),
      ).rejects.toBeInstanceOf(HlHttpError);
      expect(post).toHaveBeenCalledTimes(2); // original + one replay, then give up
      expect(vi.mocked(createCustomer)).toHaveBeenCalledOnce();
    });
  });
});
