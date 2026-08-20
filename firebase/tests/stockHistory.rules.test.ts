/**
 * Firestore Security Rules — Add-Inventory batches and their reversal trail (ticket #106).
 *
 * Two things must hold no matter what a compromised mobile client sends:
 *
 *   1. A client may only **start** a batch. Everything the reversal writes — its status, the HL
 *      transaction ids, the reversal trail — is Cloud-Function owned. A client that could mint a
 *      batch carrying forged ledger ids would be handing the reversal transactions to reverse.
 *   2. A unit cannot be re-pointed at another batch. That would let a reversal pull phones it
 *      never paid for, and leave behind the ones it did.
 *
 * Run: `npm run test:rules`.
 *
 * Reversal itself is not reachable from a client at all: `allow update` is closed, and mobile goes
 * through the `reverseStockBatch` callable, which re-checks the caller is an admin server-side.
 */
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, setDoc, updateDoc, type Firestore } from 'firebase/firestore';
import { afterAll, beforeAll, beforeEach, describe, test } from 'vitest';

const PROJECT_ID = 'aromex-stock-history-rules-test';
const COMPANY_ID = 'co_test_1';
const SKU = 'aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked';

let testEnv: RulesTestEnvironment;

/** A well-formed purchases/{id} doc, as the client writes it at intake. */
function validPurchase(over: Record<string, unknown> = {}) {
  return {
    partyEntityId: 'e1',
    totalCost: '1000.00',
    cashPaid: '0',
    bankPaid: '0',
    unitCount: 2,
    syncStatus: 'PENDING',
    createdBy: 'u_manager',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    ...over,
  };
}

function validSerial(serialId: string, over: Record<string, unknown> = {}) {
  return {
    serialId,
    productId: SKU,
    imei: '356938035699001',
    cost: '560.00',
    condition: 'NEW',
    status: 'IN_STOCK',
    location: { attributeId: 'aLocWH_A', name: 'Warehouse A' },
    isActive: true,
    saleId: null,
    purchaseId: 'p1',
    createdBy: 'u_manager',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    ...over,
  };
}

function db(uid: string, opts: { admin?: boolean; company?: string } = {}): Firestore {
  return testEnv
    .authenticatedContext(uid, {
      hlCompanyId: opts.company ?? COMPANY_ID,
      admin: opts.admin ?? false,
    })
    .firestore() as unknown as Firestore;
}

const manageDb = () => db('u_manager');
const viewDb = () => db('u_viewer');
const adminDb = () => db('u_admin', { admin: true });

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.firestore();
    await setDoc(doc(d, 'companySettings/profile'), { hlCompanyId: COMPANY_ID });
    await setDoc(doc(d, 'users/u_manager'), { isActive: true, permissions: { inventory: 'manage' } });
    await setDoc(doc(d, 'users/u_viewer'), { isActive: true, permissions: { inventory: 'view' } });
    await setDoc(doc(d, 'users/u_admin'), { isActive: true, role: 'admin', permissions: {} });
  });
});

describe('purchases/{purchaseId}', () => {
  test('a manage user can start a batch', async () => {
    await assertSucceeds(setDoc(doc(manageDb(), 'purchases/p1'), validPurchase()));
  });

  test('a view user is DENIED creating one, but can read', async () => {
    await assertFails(setDoc(doc(viewDb(), 'purchases/p1'), validPurchase()));
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'purchases/p1'), validPurchase());
    });
    await assertSucceeds(getDoc(doc(viewDb(), 'purchases/p1')));
  });

  test('a batch must record how many phones it brought in', async () => {
    // Without unitCount a reversal cannot tell a whole batch from a partly-accounted-for one.
    const { unitCount, ...withoutCount } = validPurchase();
    await assertFails(setDoc(doc(manageDb(), 'purchases/p1'), withoutCount));
  });

  test('a client cannot mint a batch that already claims to be reversed', async () => {
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ status: 'REVERSED' })),
    );
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ reversalStatus: 'DONE' })),
    );
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ reversedAt: Date.now() })),
    );
  });

  test('a client cannot forge the ledger transaction ids a reversal would reverse', async () => {
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ hlPurchaseTxnId: 'txn-someone-elses' })),
    );
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ hlPayoutCashTxnId: 'txn-x' })),
    );
    await assertFails(
      setDoc(doc(manageDb(), 'purchases/p1'), validPurchase({ hlReversalTxnIds: { purchase: 'x' } })),
    );
  });

  test('nobody may edit or delete a batch — not even an admin', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'purchases/p1'), validPurchase());
    });
    // Reversal goes through the callable, which re-checks admin server-side; the doc itself is
    // closed so a client can never mark a batch reversed without the ledger moving.
    await assertFails(updateDoc(doc(adminDb(), 'purchases/p1'), { status: 'REVERSED' }));
    await assertFails(updateDoc(doc(manageDb(), 'purchases/p1'), { totalCost: '1.00' }));
    await assertFails(deleteDoc(doc(adminDb(), 'purchases/p1')));
  });
});

describe('serials.purchaseId', () => {
  async function seedUnit() {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'serials/s1'), validSerial('s1'));
    });
  }

  test('a unit is created carrying the batch that brought it in', async () => {
    await assertSucceeds(setDoc(doc(manageDb(), 'serials/s1'), validSerial('s1')));
  });

  test('a unit cannot be re-pointed at another batch', async () => {
    await seedUnit();
    // Would let a reversal pull phones it never paid for.
    await assertFails(updateDoc(doc(manageDb(), 'serials/s1'), { purchaseId: 'p2' }));
  });

  test('a unit cannot have its batch stripped', async () => {
    await seedUnit();
    // Would strand the phone: its batch could then never pull it back.
    await assertFails(updateDoc(doc(manageDb(), 'serials/s1'), { purchaseId: null }));
  });

  test('the ordinary edits still work', async () => {
    await seedUnit();
    await assertSucceeds(updateDoc(doc(manageDb(), 'serials/s1'), { cost: '600.00' }));
    await assertSucceeds(updateDoc(doc(manageDb(), 'serials/s1'), { status: 'SOLD' }));
    await assertSucceeds(updateDoc(doc(manageDb(), 'serials/s1'), { isActive: false }));
  });
});
