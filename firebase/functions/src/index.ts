import { initializeApp } from 'firebase-admin/app';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions/v2';
import { GATEWAY_ADMIN_TOKEN, resolveConfig } from './config.js';
import { reversePurchaseCore } from './purchaseReversal.js';
import { renderStatementCore, StatementError, type RenderStatementRequest } from './statement.js';
import {
  issueSaleInvoice,
  profileChanged,
  retryInvoiceCore,
  syncCommission,
  syncEntity,
  syncMoneyEntry,
  syncPurchase,
  syncSale,
  voidSaleCore,
  VoidPermanentError,
  type CommissionData,
  type EntityData,
  type MoneyEntryData,
  type PurchaseData,
  type SaleData,
} from './syncWorker.js';

initializeApp();

/**
 * The HL dual-write spine.
 *
 * A client only ever writes entities/{id} with syncStatus=PENDING. This trigger
 * creates the HL customer (idempotent), posts any opening balance, and flips the doc
 * to SYNCED — the reusable pattern every later money feature copies.
 *
 * Loop safety: the function's own SYNCED patch re-fires this trigger, but that write
 * leaves syncStatus=SYNCED with the profile fields unchanged, so it falls through to
 * a no-op below and terminates.
 *
 * retry: true → on a thrown failure Eventarc redelivers this event (with backoff), so a
 * transient failure (e.g. gateway blip) re-syncs promptly. `reconcileEntities` is the
 * slower catch-all for anything redelivery gives up on.
 */
export const onEntityWrite = onDocumentWritten(
  { document: 'entities/{entityId}', secrets: [GATEWAY_ADMIN_TOKEN], retry: true },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return; // deleted — nothing to sync

    const entityId = event.params.entityId;
    const data = after.data() as EntityData;

    // Fresh save (or a client-requested re-sync): do the full create + opening + patch.
    if (data.syncStatus === 'PENDING') {
      await syncEntity(entityId, data, resolveConfig());
      return;
    }

    // Profile edit on an already-synced entity: push name/email/phone to HL.
    const before = event.data?.before;
    if (
      data.syncStatus === 'SYNCED' &&
      data.hlCustomerId &&
      before?.exists &&
      profileChanged(before.data() as EntityData, data)
    ) {
      await syncEntity(entityId, data, resolveConfig());
      return;
    }

    // Otherwise (incl. our own SYNCED write) → no-op.
  },
);

/**
 * The purchase dual-write (ticket #58). A client only ever writes purchases/{id} with
 * syncStatus=PENDING (alongside — never merged into — the inventory transaction). This
 * trigger books the batch cost in HL as an ASSET plus any cash/bank payout, then flips
 * the doc to SYNCED. Same spine as onEntityWrite: retry:true for prompt redelivery, with
 * the reconcile sweep as the slower backstop.
 *
 * Loop safety: our own SYNCED/FAILED patch re-fires this trigger but falls through to a
 * no-op (only PENDING syncs). The "party not synced yet" path leaves the doc untouched,
 * so it doesn't re-fire either.
 */
export const onPurchaseWrite = onDocumentWritten(
  { document: 'purchases/{purchaseId}', secrets: [GATEWAY_ADMIN_TOKEN], retry: true, timeoutSeconds: 120 },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return; // deleted — nothing to sync

    const data = after.data() as PurchaseData;
    const before = event.data?.before?.data() as PurchaseData | undefined;

    // Desktop-initiated batch reversal (ticket #106): the Admin SDK writes the request fields onto
    // the purchase doc; edge-trigger on `reversalRequestedAt` changing so the CF's own DONE/FAILED
    // writes don't loop. Same idempotent worker mobile's callable uses. Checked BEFORE the sync
    // branch: a reversal request never carries syncStatus=PENDING, but the ordering makes it
    // impossible for a future change to that to turn a reversal into a re-post.
    if (reversalRequested(before, data)) {
      try {
        await reversePurchaseCore(
          getFirestore(),
          resolveConfig(),
          event.params.purchaseId,
          data.reversalRequestedBy ?? undefined,
          data.reversalReason ?? '',
          new Date(),
        );
      } catch (err) {
        // Permanent failures (not-admin / no reason / sold units) are already settled on the doc —
        // swallow so retry:true doesn't redeliver forever. Transient errors rethrow.
        if (err instanceof VoidPermanentError) {
          logger.warn(`onPurchaseWrite: reversal ${event.params.purchaseId} permanently failed`, err);
        } else {
          throw err;
        }
      }
      return;
    }

    // Never post a batch that is reversed, or one whose reversal is in flight. The reversal's own
    // `reversalStatus: PENDING` write re-fires this trigger, and for a batch that never synced the
    // branch below would otherwise post it to HL midway through un-booking it.
    if (data.status === 'REVERSED' || data.reversalStatus === 'PENDING') return;

    if (data.syncStatus === 'PENDING') {
      await syncPurchase(event.params.purchaseId, data, resolveConfig());
    }
    // Otherwise (incl. our own SYNCED/FAILED write) → no-op.
  },
);

/**
 * True when `reversalRequestedAt` was just set/advanced (a Desktop reversal request) — the edge
 * that runs the reversal, so the CF's own resulting writes (which leave the timestamp unchanged)
 * don't loop.
 */
function reversalRequested(before: PurchaseData | undefined, after: PurchaseData): boolean {
  const a = after.reversalRequestedAt as Timestamp | undefined;
  if (!a) return false;
  const b = before?.reversalRequestedAt as Timestamp | undefined;
  return !b || !b.isEqual(a);
}

/**
 * The money-movement dual-write (ticket #90) — the rebuilt transactions screen.
 *
 * A client only ever writes moneyEntries/{id} with syncStatus=PENDING; this trigger routes it to
 * the right HL endpoint (payment / payout / raw journal, by which side is a party) and flips the
 * doc to SYNCED. Same spine as onPurchaseWrite.
 *
 * The legacy app mutated a stored balance from the client, so two concurrent entries against one
 * party could silently lose money. Here nothing stores a balance and the HL post is idempotent on
 * a sourceId derived from the doc id, so a redelivered trigger re-posts nothing.
 */
export const onMoneyEntryWrite = onDocumentWritten(
  { document: 'moneyEntries/{entryId}', secrets: [GATEWAY_ADMIN_TOKEN], retry: true },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return; // deleted — nothing to sync

    const data = after.data() as MoneyEntryData;
    if (data.syncStatus === 'PENDING') {
      await syncMoneyEntry(event.params.entryId, data, resolveConfig());
    }
    // Otherwise (incl. our own SYNCED/FAILED write) → no-op.
  },
);

/**
 * The commission dual-write (ticket #97). A client only ever writes commissions/{id} with
 * syncStatus=PENDING (co-committed with the stock + purchase in one transaction). This trigger
 * posts it to HL — accrue against a Commission expense account, then an optional payout when the
 * user paid it now — and flips the doc to SYNCED. Same spine as onPurchaseWrite: retry:true for
 * prompt redelivery, reconcile as the backstop.
 *
 * Loop safety: our own SYNCED/FAILED patch re-fires this trigger but falls through to a no-op
 * (only PENDING syncs). The "payee not synced yet" path leaves the doc untouched, so it doesn't
 * re-fire either.
 */
export const onCommissionWrite = onDocumentWritten(
  { document: 'commissions/{commissionId}', secrets: [GATEWAY_ADMIN_TOKEN], retry: true },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return; // deleted — nothing to sync

    const data = after.data() as CommissionData;
    // A commission whose batch was reversed is never posted — the reversal's own REVERSED write
    // lands here, and without this the still-PENDING commission would be accrued right after the
    // stock it was earned on was un-booked (ticket #106).
    if (data.status === 'REVERSED') return;
    if (data.syncStatus === 'PENDING') {
      await syncCommission(event.params.commissionId, data, resolveConfig());
    }
    // Otherwise (incl. our own SYNCED/FAILED write) → no-op.
  },
);

/**
 * The sale dual-write (ticket #61). A client only ever writes sales/{id} with
 * syncStatus=PENDING (co-committed with the mark-sold transaction). This trigger posts the
 * sale to HL — pre-tax revenue + AR + tax, COGS against the Inventory asset, and the
 * payment — then flips the doc to SYNCED. Same spine as onPurchaseWrite: retry:true for
 * prompt redelivery, reconcile as the backstop.
 *
 * Loop safety: our own SYNCED/FAILED patch re-fires this trigger but falls through to a
 * no-op (only PENDING syncs). The "party not synced yet" path leaves the doc untouched.
 */
export const onSaleWrite = onDocumentWritten(
  // 120 s: the HL post plus the bill-engine render, which can take ~30 s on a cold Lambda
  // (ticket #76). A timeout is still safe — SYNCED commits first, so the invoice just stays
  // PENDING for reconcile — but the extra headroom lets the common case issue on the first pass.
  { document: 'sales/{saleId}', secrets: [GATEWAY_ADMIN_TOKEN], retry: true, timeoutSeconds: 120 },
  async (event) => {
    const after = event.data?.after;
    if (!after?.exists) return; // deleted — nothing to sync

    const data = after.data() as SaleData;
    if (data.syncStatus === 'PENDING') {
      await syncSale(event.params.saleId, data, resolveConfig());
      return;
    }

    // Desktop-initiated invoice retry (ticket #77): Desktop's Admin SDK bumps
    // `invoiceRetryRequestedAt` to ask for an immediate re-issue (mobile can't write the sale
    // doc, so it uses the `retryInvoice` callable instead). Edge-trigger on the timestamp
    // changing so our own resulting ISSUED/FAILED write doesn't loop. Same idempotent worker.
    const before = event.data?.before?.data() as SaleData | undefined;

    // Desktop-initiated void (ticket #85): the Admin SDK writes the void request fields +
    // voidStatus=PENDING onto the sale doc; edge-trigger on `voidRequestedAt` changing so the
    // CF's own DONE/FAILED write doesn't loop. Same idempotent worker mobile's callable uses.
    if (voidRequested(before, data)) {
      try {
        await voidSaleCore(
          getFirestore(),
          resolveConfig(),
          event.params.saleId,
          data.voidRequestedBy ?? undefined,
          data.voidReason ?? '',
          new Date(),
        );
      } catch (err) {
        // A permanent failure (not-admin / no reason / re-used IMEI) is already settled FAILED on
        // the doc — swallow it so retry:true doesn't redeliver forever. Transient errors rethrow.
        if (err instanceof VoidPermanentError) {
          logger.warn(`onSaleWrite: void ${event.params.saleId} permanently failed`, err);
        } else {
          throw err;
        }
      }
      return;
    }

    if (data.syncStatus === 'SYNCED' && retryRequested(before, data)) {
      await retryInvoiceCore(getFirestore(), resolveConfig(), event.params.saleId, new Date());
    }
    // Otherwise (incl. our own SYNCED/FAILED write) → no-op.
  },
);

/**
 * True when `voidRequestedAt` was just set/advanced (a Desktop void request) — the edge that
 * runs the void, so the CF's own resulting writes (which leave the timestamp unchanged) don't loop.
 */
function voidRequested(before: SaleData | undefined, after: SaleData): boolean {
  const a = after.voidRequestedAt;
  if (!a) return false;
  const b = before?.voidRequestedAt;
  return !b || !b.isEqual(a);
}

/**
 * True when `invoiceRetryRequestedAt` was just set/advanced (a Desktop retry request) — the
 * edge that re-triggers issuance, so re-issuance's own writes (which leave the timestamp
 * unchanged) don't loop.
 */
function retryRequested(before: SaleData | undefined, after: SaleData): boolean {
  const a = after.invoiceRetryRequestedAt;
  if (!a) return false;
  const b = before?.invoiceRetryRequestedAt;
  return !b || !b.isEqual(a);
}

/** Skip docs updated within this window — the live trigger (and its retries) still owns them. */
const RECONCILE_MIN_AGE_MS = 3 * 60 * 1000;

/**
 * Give up re-issuing an invoice after this many failed render attempts (the live pass counts as
 * the first). A persistent failure — e.g. a sale that is SYNCED but for which HL never returned an
 * invoice number — would otherwise re-attempt every 5 minutes forever. Past the cap the sale is
 * left FAILED, logged at error level for alerting, and can be re-driven by hand once fixed.
 */
const MAX_INVOICE_ATTEMPTS = 5;

/**
 * Durability backstop (PRD §6.3). Re-runs the sync for any entity, purchase, sale, money entry
 * **or commission** still stuck PENDING/FAILED — catches records the trigger + its redelivery
 * didn't finish, and ones that were waiting on their party/payee to sync. Idempotent, so
 * re-running is safe.
 *
 * Staleness guard: only touches docs whose updatedAt is older than RECONCILE_MIN_AGE_MS
 * (filtered in memory → no composite index), so it doesn't race a still-in-flight trigger.
 * Docs with no updatedAt are treated as stale (processed).
 */
export const reconcileEntities = onSchedule(
  { schedule: 'every 5 minutes', secrets: [GATEWAY_ADMIN_TOKEN] },
  async () => {
    const db = getFirestore();
    const cfg = resolveConfig();
    const cutoffMs = Date.now() - RECONCILE_MIN_AGE_MS;

    const isStale = (doc: FirebaseFirestore.QueryDocumentSnapshot): boolean => {
      const updatedAt = doc.get('updatedAt') as Timestamp | undefined;
      return !(updatedAt && updatedAt.toMillis() > cutoffMs);
    };

    for (const status of ['PENDING', 'FAILED'] as const) {
      const entities = await db
        .collection('entities')
        .where('syncStatus', '==', status)
        .limit(200)
        .get();
      for (const doc of entities.docs) {
        if (!isStale(doc)) continue; // too fresh — leave to the trigger
        try {
          await syncEntity(doc.id, doc.data() as EntityData, cfg);
        } catch (err) {
          logger.warn(`reconcile: entity ${doc.id} still failing`, err);
          // leave it FAILED; the next sweep retries
        }
      }

      const purchases = await db
        .collection('purchases')
        .where('syncStatus', '==', status)
        .limit(200)
        .get();
      for (const doc of purchases.docs) {
        if (!isStale(doc)) continue;
        const purchase = doc.data() as PurchaseData;
        // Same reason as the trigger's guard: never post a batch that is being (or has been)
        // reversed. The sweep is the slow path, so it is the likeliest to arrive mid-reversal.
        if (purchase.status === 'REVERSED' || purchase.reversalStatus === 'PENDING') continue;
        try {
          await syncPurchase(doc.id, purchase, cfg);
        } catch (err) {
          logger.warn(`reconcile: purchase ${doc.id} still failing`, err);
        }
      }

      const sales = await db
        .collection('sales')
        .where('syncStatus', '==', status)
        .limit(200)
        .get();
      for (const doc of sales.docs) {
        if (!isStale(doc)) continue;
        try {
          await syncSale(doc.id, doc.data() as SaleData, cfg);
        } catch (err) {
          logger.warn(`reconcile: sale ${doc.id} still failing`, err);
        }
      }

      const moneyEntries = await db
        .collection('moneyEntries')
        .where('syncStatus', '==', status)
        .limit(200)
        .get();
      for (const doc of moneyEntries.docs) {
        if (!isStale(doc)) continue;
        try {
          await syncMoneyEntry(doc.id, doc.data() as MoneyEntryData, cfg);
        } catch (err) {
          logger.warn(`reconcile: money entry ${doc.id} still failing`, err);
        }
      }

      const commissions = await db
        .collection('commissions')
        .where('syncStatus', '==', status)
        .limit(200)
        .get();
      for (const doc of commissions.docs) {
        if (!isStale(doc)) continue; // too fresh — leave to the trigger
        const commission = doc.data() as CommissionData;
        if (commission.status === 'REVERSED') continue; // its batch was reversed (#106)
        try {
          await syncCommission(doc.id, commission, cfg);
        } catch (err) {
          logger.warn(`reconcile: commission ${doc.id} still failing`, err);
        }
      }
    }

    // Invoice retry (ticket #76): sales already posted to HL (syncStatus=SYNCED) whose PDF
    // invoice hasn't issued yet (invoiceStatus PENDING/FAILED). Distinct from the sweep above,
    // which retries the HL post itself. Re-issuing reuses the stored HL number → the same
    // permanent URL, never a second invoice (issueSaleInvoice skips an already-ISSUED sale).
    // Filter syncStatus in memory so no composite index is needed (mirrors the staleness guard).
    const pendingInvoices = await db
      .collection('sales')
      .where('invoiceStatus', 'in', ['PENDING', 'FAILED'])
      .limit(200)
      .get();
    for (const doc of pendingInvoices.docs) {
      if (!isStale(doc)) continue; // too fresh — the live path still owns it
      const data = doc.data() as SaleData;
      if (data.syncStatus !== 'SYNCED') continue; // not HL-synced yet → handled by the sweep above
      if ((data.invoiceAttempts ?? 0) >= MAX_INVOICE_ATTEMPTS) {
        // Persistent failure — stop churning and surface it at error level for alerting.
        logger.error(
          `reconcile: sale ${doc.id} invoice abandoned after ${data.invoiceAttempts} attempts`,
          { invoiceError: data.invoiceError ?? null },
        );
        continue;
      }
      try {
        await issueSaleInvoice(db, doc.id, data, cfg, data.invoiceNumber ?? undefined, new Date());
      } catch (err) {
        logger.warn(`reconcile: sale ${doc.id} invoice still failing`, err);
      }
    }
  },
);

/**
 * On-demand invoice retry (ticket #77) — the cashier-facing Retry on the Sale-complete screen.
 * A `FAILED` invoice is already retried automatically by the 5-minute reconcile sweep, but a
 * cashier standing with a customer can't wait that long, so this re-runs issuance **immediately**.
 *
 * It does **not** build a new invoice — it invokes T1's existing, idempotent `issueSaleInvoice`
 * (reusing HL's same number → the same PDF, never a duplicate; an already-ISSUED sale is a no-op).
 * It first flips the doc to `PENDING` so every live listener shows the "preparing" spinner, then
 * re-reads and returns the settled state for the caller.
 *
 * Mobile only: the client SDK may never write `invoice*` fields (Firestore rules), so it calls
 * this. Desktop uses its Admin-SDK access to nudge the doc directly and doesn't call this. Auth is
 * required — only a signed-in user of this workspace may trigger a retry.
 */
export const retryInvoice = onCall(
  { secrets: [GATEWAY_ADMIN_TOKEN], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Sign in to retry an invoice.');
    }
    const saleId = (request.data?.saleId ?? '') as string;
    if (!saleId) {
      throw new HttpsError('invalid-argument', 'saleId is required.');
    }
    try {
      return await retryInvoiceCore(getFirestore(), resolveConfig(), saleId, new Date());
    } catch (err) {
      if ((err as Error)?.message === 'not-found') {
        throw new HttpsError('not-found', 'Sale not found.');
      }
      throw err;
    }
  },
);

/**
 * Reverse an Add-Inventory batch (ticket #106) — the mobile transport into the idempotent
 * `reversePurchaseCore` (Desktop uses the Admin-SDK edge-trigger on `onPurchaseWrite` instead).
 *
 * A reversal un-books the purchase: the party stops being owed for it, money paid at intake comes
 * back, any commission the batch earned is reversed, and the phones leave stock. **Admin-only,
 * verified server-side** inside `reversePurchaseCore` (`users/{uid}.role`) — auth alone is not
 * enough here. Reason is required.
 */
export const reverseStockBatch = onCall(
  { secrets: [GATEWAY_ADMIN_TOKEN], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Sign in to reverse a batch.');
    }
    const purchaseId = (request.data?.purchaseId ?? '') as string;
    const reason = (request.data?.reason ?? '') as string;
    if (!purchaseId) throw new HttpsError('invalid-argument', 'purchaseId is required.');
    if (!reason.trim()) throw new HttpsError('invalid-argument', 'A reason is required.');
    try {
      return await reversePurchaseCore(
        getFirestore(),
        resolveConfig(),
        purchaseId,
        request.auth.uid,
        reason,
        new Date(),
      );
    } catch (err) {
      if (err instanceof VoidPermanentError) {
        const msg = err.message.replace(/^reverse batch:\s*/, '');
        const code = /only an admin/.test(msg) ? 'permission-denied' : 'failed-precondition';
        throw new HttpsError(code, msg.charAt(0).toUpperCase() + msg.slice(1));
      }
      throw err;
    }
  },
);

/**
 * Void a sale (ticket #85) — the mobile transport into the idempotent `voidSaleCore` (Desktop uses
 * the Admin-SDK edge-trigger on `onSaleWrite` instead). A void is a full reversal: HL cancels the
 * invoice + refunds the split, and the units go back on the shelf. **Admin-only, verified
 * server-side** inside `voidSaleCore` (`users/{uid}.role`) — auth alone is not enough here.
 *
 * The callable passes `request.auth.uid` as the requester, so the CF re-checks the *actual* signed-in
 * user's role, never a client claim. Reason is required. Mobile has no void UI in v1, but the
 * transport exists so it can adopt it without a functions change.
 */
export const voidSale = onCall(
  { secrets: [GATEWAY_ADMIN_TOKEN], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Sign in to void a sale.');
    }
    const saleId = (request.data?.saleId ?? '') as string;
    const reason = (request.data?.reason ?? '') as string;
    if (!saleId) throw new HttpsError('invalid-argument', 'saleId is required.');
    if (!reason.trim()) throw new HttpsError('invalid-argument', 'A reason is required.');
    try {
      return await voidSaleCore(
        getFirestore(),
        resolveConfig(),
        saleId,
        request.auth.uid,
        reason,
        new Date(),
      );
    } catch (err) {
      if (err instanceof VoidPermanentError) {
        const msg = err.message.replace(/^void:\s*/, '');
        // not-admin → permission-denied; everything else permanent → failed-precondition.
        const code = /only an admin/.test(msg) ? 'permission-denied' : 'failed-precondition';
        throw new HttpsError(code, msg.charAt(0).toUpperCase() + msg.slice(1));
      }
      throw err;
    }
  },
);

/**
 * Render a party's statement PDF (ticket #109) — the device's only path to the (unauthenticated)
 * Bill Engine. The shared Kotlin use case assembles the figures (paging, opening balance, FIFO
 * aging); this callable re-checks the caller holds `profiles: view` **server-side**, reads the
 * seller/buyer letterhead, stamps the issue date in the shop's timezone, and POSTs to the engine.
 * Returns the permanent PDF `{ url }`. Errors surface as HttpsError, matching `voidSale`'s shape.
 */
export const renderStatement = onCall(
  { secrets: [GATEWAY_ADMIN_TOKEN], timeoutSeconds: 120 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Sign in to print a statement.');
    }
    try {
      return await renderStatementCore(
        getFirestore(),
        resolveConfig(),
        request.auth.uid,
        request.data as RenderStatementRequest,
        new Date(),
      );
    } catch (err) {
      if (err instanceof StatementError) {
        const msg = err.message.charAt(0).toUpperCase() + err.message.slice(1);
        const code =
          err.code === 'not-found'
            ? 'not-found'
            : err.code === 'invalid-argument'
              ? 'invalid-argument'
              : 'permission-denied';
        throw new HttpsError(code, msg);
      }
      // Engine / network failures — readable, non-permanent.
      throw new HttpsError('internal', `Could not generate the statement: ${(err as Error)?.message ?? err}`);
    }
  },
);
