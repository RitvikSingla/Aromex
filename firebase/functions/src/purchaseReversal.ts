import { FieldValue, Timestamp, type Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';
import { getHlToken, reverseTransaction } from './hl.js';
import {
  assertAdmin,
  isPermanentHlError,
  syncPurchase,
  VoidPermanentError,
  type CommissionData,
  type PurchaseData,
} from './syncWorker.js';

/**
 * Reversing an Add-Inventory batch (ticket #106) — the mirror of voiding a sale, and built the
 * same way for the same reasons.
 *
 * A batch is the unit of reversal because it is the unit of posting: one Humble Ledger transaction
 * books the whole batch, and HL reverses a transaction whole. Undoing one phone out of eight would
 * leave a posted purchase describing stock that no longer exists.
 *
 * A reversal is **never a delete**. In order:
 *  1. Already `REVERSED` → no-op success (the whole path gets retried).
 *  2. Re-verify the requester is an admin **server-side** and that a reason was given — the client
 *     is never trusted, and Desktop's Admin SDK bypasses Firestore rules.
 *  3. Re-check the batch is whole: every unit still in stock, still active, and all of them
 *     accounted for. This runs **before** any HL call, so a blocked batch fails while the ledger
 *     is still untouched.
 *  4. Reverse each HL leg by id — the purchase, each payout, and every commission the batch
 *     earned. Each reversal id is persisted as it lands, so a retry skips what already posted.
 *  5. Pull the stock and flip `REVERSED` atomically.
 *
 * Step 4 before step 5 matches the sale void: if the stock step fails, the trail says FAILED and a
 * replay skips the already-reversed legs and finishes the job.
 */

export type ReverseBatchResult = { status: 'REVERSED' };

/**
 * How long one run may hold the reversal.
 *
 * Unlike every other Humble Ledger call in this codebase, `POST /transactions/{id}/reverse`
 * takes **no `sourceId`** — it is not idempotent. Two overlapping runs would each read an empty
 * `hlReversalTxnIds` and each post a reversal, crediting the party twice. The lease makes entry
 * exclusive; it is released on the way out (success or failure) so a redelivery isn't locked out,
 * and it expires on its own if a run dies mid-flight.
 */
const REVERSAL_LEASE_MS = 10 * 60 * 1000;

const ADMIN_MESSAGES = {
  noUser: 'reverse batch: no requesting user',
  notAdmin: 'reverse batch: only an admin can reverse a stock batch',
};

/** One reversible ledger posting: where its id lives, and the key its reversal is filed under. */
type ReversibleLeg = { key: string; transactionId: string };

type UnitDoc = {
  serialId: string;
  imei?: string;
  status?: string;
  isActive?: boolean;
};

export async function reversePurchaseCore(
  db: Firestore,
  cfg: SyncConfig,
  purchaseId: string,
  requestedByUid: string | undefined,
  reason: string,
  now: Date,
): Promise<ReverseBatchResult> {
  const ref = db.collection('purchases').doc(purchaseId);
  const snap = await ref.get();
  if (!snap.exists) throw new VoidPermanentError('reverse batch: purchase not found');
  let data = snap.data() as PurchaseData;

  // 1. Idempotent no-op.
  if (data.status === 'REVERSED') return { status: 'REVERSED' };

  // 1b. Claim the reversal. Exclusive because the HL reverse call is not idempotent.
  const claim = await claimReversal(db, ref, now);
  if (claim === 'ALREADY_REVERSED') return { status: 'REVERSED' };
  if (claim === 'BUSY') {
    throw new VoidPermanentError('reverse batch: a reversal for this batch is already running');
  }

  try {
    // 2. Server-side gates. Stamp the trail so it survives even mid-flight.
    await assertAdmin(db, requestedByUid, ADMIN_MESSAGES);
    const trimmedReason = reason.trim();
    if (!trimmedReason) throw new VoidPermanentError('reverse batch: a reason is required');
    await ref.set(
      {
        reversalReason: trimmedReason,
        reversalRequestedBy: requestedByUid,
        reversalStatus: 'PENDING',
        reversalError: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    // 3. Pre-flight wholeness check — BEFORE any irreversible HL call.
    const units = await loadBatchUnits(db, purchaseId);
    assertBatchIsWhole(purchaseId, data, units);

    // 4. HL reversal — only when the batch actually reached HL. A never-synced batch has nothing
    //    to reverse; pulling the stock and flipping REVERSED is enough, and the REVERSED guard in
    //    `onPurchaseWrite` stops it being posted afterwards.
    const reversalIds: Record<string, string> = { ...(readReversalIds(data) ?? {}) };
    const commissions = await loadBatchCommissions(db, purchaseId);

    if (data.syncStatus === 'SYNCED') {
      const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);

      // Batches posted before the leg ids were persisted don't carry them. An idempotent
      // `syncPurchase` replay returns the ORIGINAL transactions, so re-running it recovers the
      // same ids rather than posting anything new.
      if (!data.hlPurchaseTxnId) {
        await syncPurchase(purchaseId, data, cfg);
        data = (await ref.get()).data() as PurchaseData;
      }

      const legs = purchaseLegs(data);
      if (legs.length === 0) {
        throw new VoidPermanentError(
          `reverse batch: cannot resolve the ledger transaction for batch ${purchaseId}`,
        );
      }

      for (const leg of [...legs, ...commissionLegs(commissions)]) {
        if (reversalIds[leg.key]) continue; // already reversed on an earlier pass
        const res = await reverseTransaction(cfg.hlBaseUrl, token, leg.transactionId, trimmedReason);
        // '(reversed)' when HL returns no id: the leg IS reversed, and the marker is what stops a
        // retry from reversing it a second time. Losing that would be worse than losing the id.
        reversalIds[leg.key] = res.reversalTxnId ?? '(reversed)';
        // Persisted per leg, not once at the end: a crash between two legs must not re-reverse
        // the first one on redelivery.
        await ref.set({ hlReversalTxnIds: reversalIds }, { merge: true });
      }
    }

    // 5. Pull the stock, settle the commissions, flip REVERSED — all in one commit.
    await removeStockAndReverse(db, purchaseId, units, commissions, now, reversalIds);

    return { status: 'REVERSED' };
  } catch (err) {
    await ref.set(
      {
        reversalStatus: 'FAILED',
        reversalError: (err as Error)?.message ?? String(err),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    if (err instanceof VoidPermanentError) throw err;
    if (isPermanentHlError(err)) throw new VoidPermanentError((err as Error).message);
    throw err;
  } finally {
    // Release only the lease this run took. A redelivery must be able to finish the job
    // immediately — every leg it already reversed is recorded, so the replay is safe.
    await ref.set({ reversalLeaseUntil: FieldValue.delete() }, { merge: true });
  }
}

/**
 * Take the reversal lease, atomically. Returns BUSY when another run holds it — the caller must
 * NOT release a lease it did not take, so this is the only place one is granted.
 */
async function claimReversal(
  db: Firestore,
  ref: FirebaseFirestore.DocumentReference,
  now: Date,
): Promise<'CLAIMED' | 'BUSY' | 'ALREADY_REVERSED'> {
  return db.runTransaction(async (txn) => {
    const snap = await txn.get(ref);
    const data = snap.data() as (PurchaseData & { reversalLeaseUntil?: Timestamp }) | undefined;
    if (data?.status === 'REVERSED') return 'ALREADY_REVERSED';

    const held = data?.reversalLeaseUntil;
    if (held && held.toMillis() > now.getTime()) return 'BUSY';

    txn.set(
      ref,
      { reversalLeaseUntil: Timestamp.fromMillis(now.getTime() + REVERSAL_LEASE_MS) },
      { merge: true },
    );
    return 'CLAIMED';
  });
}

// ---------- reads ----------

/** Every serial tagged with this batch, whatever its status — the un-reversible ones included. */
async function loadBatchUnits(db: Firestore, purchaseId: string): Promise<UnitDoc[]> {
  const snap = await db.collection('serials').where('purchaseId', '==', purchaseId).get();
  return snap.docs.map((d) => ({ serialId: d.id, ...(d.data() as Omit<UnitDoc, 'serialId'>) }));
}

/** The commissions this batch earned — reversed with it, or they become a debt for stock that never came. */
async function loadBatchCommissions(
  db: Firestore,
  purchaseId: string,
): Promise<Array<CommissionData & { commissionId: string }>> {
  const snap = await db.collection('commissions').where('sourceBatchId', '==', purchaseId).get();
  return snap.docs.map((d) => ({ commissionId: d.id, ...(d.data() as CommissionData) }));
}

function readReversalIds(data: PurchaseData): Record<string, string> | undefined {
  const raw = (data as { hlReversalTxnIds?: unknown }).hlReversalTxnIds;
  return raw && typeof raw === 'object' && !Array.isArray(raw)
    ? (raw as Record<string, string>)
    : undefined;
}

// ---------- rules ----------

/**
 * Throws unless the batch is exactly as it was booked. Mirrors `batchReversalBlock` in shared
 * Kotlin so the screen's greyed-out reason and this gate never disagree — but this one is the
 * authority, because Desktop bypasses Firestore rules.
 */
export function assertBatchIsWhole(purchaseId: string, data: PurchaseData, units: UnitDoc[]): void {
  const expected = data.unitCount ?? 0;

  if (units.length === 0 && expected > 0) {
    throw new VoidPermanentError(
      `reverse batch: batch ${purchaseId} predates unit tagging, so its phones can't be identified`,
    );
  }

  const sold = units.filter((u) => (u.status ?? 'IN_STOCK') !== 'IN_STOCK');
  if (sold.length > 0) {
    throw new VoidPermanentError(
      `reverse batch: ${sold.length} unit(s) have left stock (${sold.map((u) => u.imei).join(', ')}) — void those sales first`,
    );
  }

  const removed = units.filter((u) => u.isActive === false);
  if (removed.length > 0) {
    throw new VoidPermanentError(
      `reverse batch: ${removed.length} unit(s) were already removed individually (${removed.map((u) => u.imei).join(', ')})`,
    );
  }

  if (expected > 0 && units.length < expected) {
    throw new VoidPermanentError(
      `reverse batch: only ${units.length} of ${expected} units can be accounted for`,
    );
  }
}

/** The batch's own ledger legs, in the order they were posted. */
function purchaseLegs(data: PurchaseData): ReversibleLeg[] {
  const legs: ReversibleLeg[] = [];
  if (data.hlPurchaseTxnId) legs.push({ key: 'purchase', transactionId: data.hlPurchaseTxnId });
  if (data.hlPayoutCashTxnId) legs.push({ key: 'payout_cash', transactionId: data.hlPayoutCashTxnId });
  if (data.hlPayoutBankTxnId) legs.push({ key: 'payout_bank', transactionId: data.hlPayoutBankTxnId });
  return legs;
}

/**
 * Every commission leg the batch created. A commission already reversed (its own `status`) is
 * skipped; one whose ledger ids were never persisted is left alone rather than guessed at — the
 * batch still reverses, and the orphan shows up in the integrity report rather than being
 * silently double-posted.
 */
function commissionLegs(commissions: Array<CommissionData & { commissionId: string }>): ReversibleLeg[] {
  return commissions
    .filter((c) => c.status !== 'REVERSED')
    .flatMap((c) => {
      const legs: ReversibleLeg[] = [];
      if (c.hlAccrueTxnId) legs.push({ key: `commission_${c.commissionId}:accrue`, transactionId: c.hlAccrueTxnId });
      if (c.hlPayoutCashTxnId) {
        legs.push({ key: `commission_${c.commissionId}:payout_cash`, transactionId: c.hlPayoutCashTxnId });
      }
      if (c.hlPayoutBankTxnId) {
        legs.push({ key: `commission_${c.commissionId}:payout_bank`, transactionId: c.hlPayoutBankTxnId });
      }
      return legs;
    });
}

// ---------- the commit ----------

/**
 * Archive every unit, release its IMEI, mark the batch's commissions reversed, and flip the
 * purchase REVERSED — in **one** transaction, carrying the HL trail into the same commit.
 *
 * Each serial is re-read inside the transaction: a unit that sold between the pre-flight check
 * and here must stop the commit rather than be archived out from under a live sale.
 */
async function removeStockAndReverse(
  db: Firestore,
  purchaseId: string,
  units: UnitDoc[],
  commissions: Array<CommissionData & { commissionId: string }>,
  now: Date,
  reversalIds: Record<string, string>,
): Promise<void> {
  const ref = db.collection('purchases').doc(purchaseId);

  await db.runTransaction(async (txn) => {
    // Idempotent: a concurrent reversal may have already finished.
    const snap = await txn.get(ref);
    if ((snap.data() as PurchaseData | undefined)?.status === 'REVERSED') return;

    // ── Reads first (Firestore requires every read before any write) ──
    const releases: Array<{
      serialRef: FirebaseFirestore.DocumentReference;
      indexRef?: FirebaseFirestore.DocumentReference;
    }> = [];

    for (const unit of units) {
      const serialRef = db.collection('serials').doc(unit.serialId);
      const serialSnap = await txn.get(serialRef);
      if (!serialSnap.exists) continue; // already gone — nothing to pull

      const status = (serialSnap.get('status') as string | undefined) ?? 'IN_STOCK';
      const isActive = serialSnap.get('isActive') !== false;
      if (status !== 'IN_STOCK' || !isActive) {
        throw new VoidPermanentError(
          `reverse batch: unit ${unit.imei ?? unit.serialId} changed while the reversal was in ` +
            `progress; the ledger reversal has already posted — put it back in stock and retry.`,
        );
      }

      const imei = (serialSnap.get('imei') as string | undefined)?.trim() || (unit.imei ?? '').trim();
      let indexRef: FirebaseFirestore.DocumentReference | undefined;
      if (imei) {
        const candidate = db.collection('imeiIndex').doc(imei);
        const indexSnap = await txn.get(candidate);
        // Only release an index entry that still points at THIS unit. If the IMEI was re-added
        // under a different serial, deleting it would free a handset that is genuinely in stock.
        if (indexSnap.exists && (indexSnap.get('serialId') as string | undefined) === unit.serialId) {
          indexRef = candidate;
        }
      }
      releases.push({ serialRef, indexRef });
    }

    // ── Writes ──
    for (const r of releases) {
      txn.set(
        r.serialRef,
        { isActive: false, updatedAt: FieldValue.serverTimestamp() },
        { merge: true },
      );
      if (r.indexRef) txn.delete(r.indexRef);
    }

    for (const c of commissions) {
      if (c.status === 'REVERSED') continue;
      txn.set(
        db.collection('commissions').doc(c.commissionId),
        {
          status: 'REVERSED',
          reversedAt: FieldValue.serverTimestamp(),
          reversedByBatchId: purchaseId,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
    }

    txn.set(
      ref,
      {
        status: 'REVERSED',
        reversalStatus: 'DONE',
        reversedAt: now,
        hlReversalTxnIds: reversalIds,
        reversalError: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });
}
