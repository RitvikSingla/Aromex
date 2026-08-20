import { FieldValue, getFirestore, type Firestore, type Timestamp } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';
import { BillEngineError, renderInvoice } from './billEngine.js';
import {
  cancelInvoice,
  createCustomer,
  createCustomerPayout,
  createCustomerPurchase,
  createJournalEntry,
  createPayment,
  createSale,
  getHlToken,
  getInvoice,
  getOrCreateAccount,
  HlHttpError,
  postOpeningBalance,
  refundPayment,
  reverseTransaction,
  updateCustomer,
  type JournalEntryLine,
  type SaleTaxLineInput,
} from './hl.js';

/**
 * The reusable HL sync worker: read a pending operational doc → broker an HL token →
 * create/update the HL customer (idempotent) → post the opening balance once → patch
 * the doc to SYNCED. On failure, mark FAILED and rethrow — the trigger runs with
 * retry:true so Eventarc redelivers, and reconcileEntities is the slower catch-all.
 *
 * Later money features (Purchase/Sales) follow the same shape — swap the HL calls.
 */

export type OpeningField = {
  amount: string;
  direction: 'RECEIVABLE' | 'CREDIT';
  posted?: boolean;
};

export type EntityData = {
  name: string;
  email?: string | null;
  phones?: string[];
  opening?: OpeningField | null;
  hlCustomerId?: string | null;
  createdBy?: string | null;
  syncStatus?: string;
};

// ---------- pure helpers (unit-tested) ----------

export function primaryPhone(phones?: string[]): string | undefined {
  return phones && phones.length > 0 ? phones[0] : undefined;
}

/** Our balance vocabulary is RECEIVABLE/CREDIT; HL's opening endpoint uses PAYABLE for "we owe them". */
export function mapDirectionToHl(direction: 'RECEIVABLE' | 'CREDIT'): 'RECEIVABLE' | 'PAYABLE' {
  return direction === 'CREDIT' ? 'PAYABLE' : 'RECEIVABLE';
}

/** Stable, extensible idempotency key: "<collection>_<docId>[:<kind>]". */
export function openingSourceId(entityId: string): string {
  return `entity_${entityId}:opening`;
}

/** True when a field HL stores (name/email/primary phone) differs between two versions. */
export function profileChanged(before: EntityData, after: EntityData): boolean {
  return (
    before.name !== after.name ||
    (before.email ?? null) !== (after.email ?? null) ||
    primaryPhone(before.phones) !== primaryPhone(after.phones)
  );
}

// ---------- the worker ----------

export async function syncEntity(
  entityId: string,
  data: EntityData,
  cfg: SyncConfig,
): Promise<void> {
  const ref = getFirestore().collection('entities').doc(entityId);
  try {
    const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);
    const phone = primaryPhone(data.phones);

    const customer = await createCustomer(cfg.hlBaseUrl, token, {
      name: data.name,
      email: data.email ?? undefined,
      phone,
      externalId: entityId,
    });

    // If the customer already existed, push any profile edits made since.
    if (customer.idempotent) {
      await updateCustomer(cfg.hlBaseUrl, token, customer.id, {
        name: data.name,
        email: data.email ?? undefined,
        phone,
      });
    }

    const patch: Record<string, unknown> = {
      hlCustomerId: customer.id,
      hlAccountId: customer.accountId,
      syncStatus: 'SYNCED',
      hlSyncedAt: FieldValue.serverTimestamp(),
      hlSyncError: FieldValue.delete(),
    };

    // Opening balance — posted at most once (idempotent on sourceId as a backstop).
    const opening = data.opening;
    if (opening && opening.posted !== true) {
      await postOpeningBalance(cfg.hlBaseUrl, token, customer.id, {
        amount: opening.amount,
        direction: mapDirectionToHl(opening.direction),
        sourceId: openingSourceId(entityId),
        actorRef: data.createdBy ?? undefined,
      });
      patch.opening = { posted: true }; // merge:true deep-merges, preserving amount/direction
    }

    await ref.set(patch, { merge: true });
  } catch (err) {
    await ref.set(
      { syncStatus: 'FAILED', hlSyncError: (err as Error)?.message ?? String(err) },
      { merge: true },
    );
    throw err; // trigger has retry:true → Eventarc redelivers; reconcile is the backstop
  }
}

// ---------- inventory purchase sync (ticket #58) ----------

/**
 * The reserved "Unspecified Supplier" party used when a purchase is recorded without
 * naming who it was bought from. Its id is FIXED (never generated) so concurrent
 * first-uses can't create duplicates; mirrors UNSPECIFIED_SUPPLIER_ID in shared Kotlin.
 */
export const UNSPECIFIED_SUPPLIER_ID = 'unspecified-supplier';
const UNSPECIFIED_SUPPLIER_NAME = 'Unspecified Supplier';

/**
 * The reserved "Walk-in Customer" party used when a sale is rung up without naming the
 * buyer (ticket #61). Fixed id (never generated) so concurrent first-uses can't duplicate;
 * mirrors WALK_IN_CUSTOMER_ID in shared Kotlin.
 */
export const WALK_IN_CUSTOMER_ID = 'walk-in-customer';
const WALK_IN_CUSTOMER_NAME = 'Walk-in Customer';

/** The two reserved placeholder parties, lazily bootstrapped on first use. */
const PLACEHOLDER_PARTIES: Record<
  string,
  { name: string; roles: string[]; isWalkIn: boolean; createdBy: string }
> = {
  [UNSPECIFIED_SUPPLIER_ID]: {
    name: UNSPECIFIED_SUPPLIER_NAME,
    roles: ['SUPPLIER'],
    isWalkIn: false,
    createdBy: 'onPurchaseWrite',
  },
  [WALK_IN_CUSTOMER_ID]: {
    name: WALK_IN_CUSTOMER_NAME,
    roles: ['CUSTOMER'],
    isWalkIn: true,
    createdBy: 'onSaleWrite',
  },
};

export type PurchaseData = {
  partyEntityId: string;
  totalCost: string;
  cashPaid?: string;
  bankPaid?: string;
  /** Units the batch created, recorded at intake (ticket #106). Absent on pre-#106 batches. */
  unitCount?: number;
  /** The batch's BUSINESS date (ticket #107) — the day the stock was bought. */
  createdAt?: Timestamp | null;
  createdBy?: string | null;
  syncStatus?: string;
  /** HL transaction ids per leg — what a reversal reverses. Persisted by [syncPurchase]. */
  hlPurchaseTxnId?: string | null;
  hlPayoutCashTxnId?: string | null;
  hlPayoutBankTxnId?: string | null;
  // ── reversal trail (ticket #106; CF-owned except the request fields) ──
  status?: string;
  reversalRequestedAt?: unknown;
  reversalRequestedBy?: string | null;
  reversalReason?: string | null;
  reversalStatus?: string;
  reversalError?: string;
  hlReversalTxnIds?: string[];
};

/** Deterministic idempotency key per HL leg: "purchase_<docId>:<kind>". */
export function purchaseSourceId(
  purchaseId: string,
  kind: 'purchase' | 'payout_cash' | 'payout_bank',
): string {
  return `purchase_${purchaseId}:${kind}`;
}

function isPositiveAmount(v?: string): boolean {
  return v != null && Number(v) > 0;
}

/**
 * Drops keys whose value is undefined. Firestore rejects an explicit `undefined`, and a leg that
 * didn't post (no cash paid) must leave its field absent rather than blanking one an earlier
 * successful run wrote.
 */
function definedOnly<T extends Record<string, unknown>>(obj: T): Partial<T> {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as Partial<T>;
}

/**
 * Resolve the party's HL customer id, lazily bootstrapping a reserved placeholder party
 * (Unspecified Supplier / Walk-in Customer — fixed id → no duplicates) on first use, and
 * syncing an already-existing-but-not-yet-synced party inline so the first purchase/sale
 * doesn't wait for the entity reconcile sweep. Returns undefined when the party still
 * can't be synced yet → caller leaves the purchase/sale PENDING for the trigger/reconcile.
 */
export async function resolvePartyHlCustomerId(
  db: Firestore,
  partyEntityId: string,
  cfg: SyncConfig,
): Promise<string | undefined> {
  const entityRef = db.collection('entities').doc(partyEntityId);
  const snap = await entityRef.get();

  const placeholder = PLACEHOLDER_PARTIES[partyEntityId];
  if (!snap.exists && placeholder) {
    await entityRef.set(
      {
        name: placeholder.name,
        phones: [],
        roles: placeholder.roles,
        isWalkIn: placeholder.isWalkIn,
        isActive: true,
        syncStatus: 'PENDING',
        createdBy: placeholder.createdBy,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    // This write's syncStatus=PENDING already fires onEntityWrite, which syncs this
    // placeholder on its own. Do NOT also call syncEntity inline here — that races the
    // trigger with a second, concurrent createCustomer call for the same externalId
    // (this is what broke a party's first-ever use, e.g. the first Walk-in Customer
    // sale). Leave it PENDING; the trigger (or reconcile) populates hlCustomerId and
    // this sale/purchase is retried once it's there.
    return undefined;
  }
  if (!snap.exists) throw new Error(`party ${partyEntityId} not found`);

  const entity = snap.data() as EntityData & { hlCustomerId?: string | null };
  if (entity.hlCustomerId) return entity.hlCustomerId;

  try {
    await syncEntity(partyEntityId, entity, cfg);
  } catch {
    return undefined; // transient party-sync failure → leave the purchase/sale for reconcile
  }
  const after = (await entityRef.get()).data() as { hlCustomerId?: string | null } | undefined;
  return after?.hlCustomerId ?? undefined;
}

/**
 * True when [err] is an HL 404. On a customer-scoped post (sale/payment/purchase/payout)
 * a 404 means the cached `hlCustomerId` no longer resolves to a live HL customer — i.e. it
 * is stale/poisoned and the party must be re-resolved.
 */
function isStaleCustomerError(err: unknown): boolean {
  return err instanceof HlHttpError && err.status === 404;
}

/**
 * Repair a stale cached `hlCustomerId` (ticket #73): re-create the HL customer — idempotent
 * on `externalId`, so it returns the authoritative id — persist the corrected id back onto
 * the entity doc, and return it. Covers both reserved placeholders (Walk-in Customer /
 * Unspecified Supplier) and named parties alike.
 */
export async function repartyHlCustomerId(
  db: Firestore,
  partyEntityId: string,
  cfg: SyncConfig,
  token: string,
): Promise<string> {
  const entityRef = db.collection('entities').doc(partyEntityId);
  const entity = (await entityRef.get()).data() as EntityData | undefined;
  const customer = await createCustomer(cfg.hlBaseUrl, token, {
    name: entity?.name ?? partyEntityId,
    email: entity?.email ?? undefined,
    phone: primaryPhone(entity?.phones),
    externalId: partyEntityId,
  });
  await entityRef.set(
    {
      hlCustomerId: customer.id,
      hlAccountId: customer.accountId,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
  return customer.id;
}

/**
 * Run a customer-scoped HL post [post] with [hlCustomerId]; if it fails with a stale-customer
 * 404, re-resolve the party ([repartyHlCustomerId]) and replay [post] **exactly once** with
 * the corrected id. Every HL leg is idempotent on appId+sourceId, so the replay never
 * double-posts, and a second failure propagates (→ FAILED) so there is no retry loop.
 * This is the self-heal that stops the resolver from trusting a bad cached id forever.
 */
export async function withCustomerSelfHeal<T>(
  db: Firestore,
  partyEntityId: string,
  cfg: SyncConfig,
  token: string,
  hlCustomerId: string,
  post: (hlCustomerId: string) => Promise<T>,
): Promise<T> {
  try {
    return await post(hlCustomerId);
  } catch (err) {
    if (!isStaleCustomerError(err)) throw err;
    const healed = await repartyHlCustomerId(db, partyEntityId, cfg, token);
    return post(healed);
  }
}

/**
 * Post one inventory purchase to HL: the batch cost as an ASSET increase against the
 * company's `Inventory` account (get-or-created), plus a payout per non-zero cash/bank
 * amount. All legs idempotent on appId+sourceId, so retries are safe. On success →
 * SYNCED; on genuine failure → FAILED + rethrow (retry:true redelivers). When the party
 * isn't synced yet, the record is quietly left PENDING for the reconcile sweep.
 */
export async function syncPurchase(
  purchaseId: string,
  data: PurchaseData,
  cfg: SyncConfig,
): Promise<void> {
  const db = getFirestore();
  const ref = db.collection('purchases').doc(purchaseId);
  try {
    const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);

    const hlCustomerId = await resolvePartyHlCustomerId(db, data.partyEntityId, cfg);
    if (!hlCustomerId) {
      // Party not synced to HL yet. Leave PENDING WITHOUT rewriting the doc (a rewrite
      // would re-fire this trigger in a hot loop); the reconcile sweep retries it.
      return;
    }

    // Inventory account isn't customer-scoped — resolve it once, before the self-heal wrapper.
    const inventoryAccountId = await getOrCreateAccount(cfg.hlBaseUrl, token, 'Inventory', 'ASSET');
    // `createdAt` is the batch's BUSINESS date (ticket #107) — today for a normal intake, the
    // real purchase date when old books are being entered.
    const date = isoDate(data.createdAt, await shopTimeZone(db));

    // Customer-scoped posts (purchase + payouts). Wrapped so a stale cached hlCustomerId
    // (HL 404) self-heals: re-resolve the party and replay once (idempotent → no double-post).
    //
    // Each leg's transaction id is kept: reversing a batch (ticket #106) reverses transactions
    // BY ID, and without these the only way back would be guesswork. An idempotent replay
    // returns the original transaction, so a re-run recovers the same ids rather than new ones.
    const legIds: Pick<PurchaseData, 'hlPurchaseTxnId' | 'hlPayoutCashTxnId' | 'hlPayoutBankTxnId'> = {};
    await withCustomerSelfHeal(db, data.partyEntityId, cfg, token, hlCustomerId, async (cid) => {
      legIds.hlPurchaseTxnId = await createCustomerPurchase(cfg.hlBaseUrl, token, {
        customerId: cid,
        amount: data.totalCost,
        expenseAccountId: inventoryAccountId,
        description: 'Inventory purchase (Add-Inventory batch)',
        date,
        sourceId: purchaseSourceId(purchaseId, 'purchase'),
        actorRef: data.createdBy ?? undefined,
      });

      // Split payment → one payout per non-zero method (HL has no single split call).
      if (isPositiveAmount(data.cashPaid)) {
        legIds.hlPayoutCashTxnId = await createCustomerPayout(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.cashPaid as string,
          method: 'CASH',
          date,
          sourceId: purchaseSourceId(purchaseId, 'payout_cash'),
          actorRef: data.createdBy ?? undefined,
        });
      }
      if (isPositiveAmount(data.bankPaid)) {
        legIds.hlPayoutBankTxnId = await createCustomerPayout(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.bankPaid as string,
          method: 'BANK',
          date,
          sourceId: purchaseSourceId(purchaseId, 'payout_bank'),
          actorRef: data.createdBy ?? undefined,
        });
      }
    });

    await ref.set(
      {
        syncStatus: 'SYNCED',
        hlSyncedAt: FieldValue.serverTimestamp(),
        hlSyncError: FieldValue.delete(),
        ...definedOnly(legIds),
      },
      { merge: true },
    );
  } catch (err) {
    await ref.set(
      { syncStatus: 'FAILED', hlSyncError: (err as Error)?.message ?? String(err) },
      { merge: true },
    );
    throw err;
  }
}

// ---------- commission sync (ticket #97) ----------

export type CommissionData = {
  payeeEntityId: string;
  locationAttributeId?: string;
  ruleId?: string | null;
  unitCount?: number;
  basisAmount?: string;
  /** Decimal string — what's owed to the payee (always accrued to their balance). */
  amount: string;
  /** Decimal string — cash given to the payee now; "0"/absent for none (accrue only). */
  paidCash?: string;
  /** Decimal string — bank given to the payee now; "0"/absent for none. */
  paidBank?: string;
  sourceBatchId?: string;
  createdBy?: string | null;
  syncStatus?: string;
  /** HL transaction ids per leg — what reversing the batch that earned it reverses (#106). */
  /** The commission's business date — the batch's, so a backdated intake carries its commission. */
  createdAt?: Timestamp | null;
  hlAccrueTxnId?: string | null;
  hlPayoutCashTxnId?: string | null;
  hlPayoutBankTxnId?: string | null;
  status?: string;
};

/** Deterministic idempotency key per HL leg: "commission_<docId>[:payout_cash|:payout_bank]". */
export function commissionSourceId(
  commissionId: string,
  kind: 'accrue' | 'payout_cash' | 'payout_bank',
): string {
  return kind === 'accrue' ? `commission_${commissionId}` : `commission_${commissionId}:${kind}`;
}

/**
 * Post one intake commission to HL (ticket #97). A commission is a cost to the business and
 * money owed to the payee until settled — the same netting shape as buying stock on credit:
 *
 *  - **Accrue** — `/customer-purchases` against a `Commission` EXPENSE account: DR Commission
 *    expense, CR payee → the payee's balance moves in their favour (always, by the full amount).
 *  - **Give now (optional)** — a `/customer-payouts` per non-zero `paidCash`/`paidBank`: DR payee,
 *    CR Cash/Bank → nets the payee's balance back down (exactly as an inventory purchase splits
 *    its payment). "Add to balance" leaves both at "0", so no payout legs post.
 *
 * Every leg is idempotent on appId+sourceId, so a redelivered trigger re-posts nothing. On
 * success → SYNCED; on genuine failure → FAILED + rethrow (retry:true redelivers). When the
 * payee isn't synced to HL yet, the record is quietly left PENDING for the reconcile sweep —
 * WITHOUT rewriting the doc (a rewrite would re-fire this trigger in a hot loop). Mirrors
 * `syncPurchase`.
 */
export async function syncCommission(
  commissionId: string,
  data: CommissionData,
  cfg: SyncConfig,
): Promise<void> {
  const db = getFirestore();
  const ref = db.collection('commissions').doc(commissionId);

  // Never accrue a commission for a batch that has been reversed (ticket #106). A commission
  // sits PENDING until its payee reaches HL; if the batch is reversed during that window, the
  // reversal marks this doc REVERSED — and that very write re-fires `onCommissionWrite`, which
  // would otherwise post a debt to the payee for stock that never arrived. Mirrors the VOIDED
  // guard in `syncSale`.
  if (data.status === 'REVERSED') return;

  try {
    const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);

    const hlCustomerId = await resolvePartyHlCustomerId(db, data.payeeEntityId, cfg);
    if (!hlCustomerId) {
      // Payee not synced to HL yet: leave PENDING without rewriting the doc; the reconcile
      // sweep retries it once the payee entity syncs.
      return;
    }

    // The Commission expense account isn't customer-scoped — resolve it once, before the
    // self-heal wrapper (same shape as `Inventory` in syncPurchase).
    const commissionAccountId = await getOrCreateAccount(cfg.hlBaseUrl, token, 'Commission', 'EXPENSE');
    // Written with the batch's business date, so a backdated intake's commission lands with it.
    const date = isoDate(data.createdAt, await shopTimeZone(db));

    // Customer-scoped posts (accrue + optional cash/bank payouts). Wrapped so a stale cached
    // hlCustomerId (HL 404) self-heals: re-resolve the payee and replay once (idempotent → no
    // double-post). Each leg's transaction id is kept so reversing the batch that earned this
    // commission can reverse it by id (ticket #106) instead of leaving a debt for stock that
    // never arrived.
    const legIds: Pick<CommissionData, 'hlAccrueTxnId' | 'hlPayoutCashTxnId' | 'hlPayoutBankTxnId'> = {};
    await withCustomerSelfHeal(db, data.payeeEntityId, cfg, token, hlCustomerId, async (cid) => {
      legIds.hlAccrueTxnId = await createCustomerPurchase(cfg.hlBaseUrl, token, {
        customerId: cid,
        amount: data.amount,
        expenseAccountId: commissionAccountId,
        description: 'Commission on intake',
        date,
        sourceId: commissionSourceId(commissionId, 'accrue'),
        actorRef: data.createdBy ?? undefined,
      });

      // Split give-now → one payout per non-zero method (HL has no single split call).
      if (isPositiveAmount(data.paidCash)) {
        legIds.hlPayoutCashTxnId = await createCustomerPayout(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.paidCash as string,
          method: 'CASH',
          date,
          sourceId: commissionSourceId(commissionId, 'payout_cash'),
          actorRef: data.createdBy ?? undefined,
        });
      }
      if (isPositiveAmount(data.paidBank)) {
        legIds.hlPayoutBankTxnId = await createCustomerPayout(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.paidBank as string,
          method: 'BANK',
          date,
          sourceId: commissionSourceId(commissionId, 'payout_bank'),
          actorRef: data.createdBy ?? undefined,
        });
      }
    });

    await ref.set(
      {
        syncStatus: 'SYNCED',
        hlSyncedAt: FieldValue.serverTimestamp(),
        hlSyncError: FieldValue.delete(),
        ...definedOnly(legIds),
      },
      { merge: true },
    );
  } catch (err) {
    await ref.set(
      { syncStatus: 'FAILED', hlSyncError: (err as Error)?.message ?? String(err) },
      { merge: true },
    );
    throw err;
  }
}

// ---------- sale sync (ticket #61) ----------

export type SaleTaxLineData = { name: string; rate?: string; amount: string };

export type SaleLineData =
  // serialId/productId are stored on the doc (BackendSalesRepository.lineData) but weren't needed
  // by syncSale; a void needs them to restore the serial and re-key its imeiIndex (ticket #85).
  | { kind: 'INVENTORY'; imei: string; label: string; netPrice: string; serialId?: string; productId?: string }
  | { kind: 'CUSTOM'; name: string; netPrice: string };

export type InvoiceStatus = 'PENDING' | 'ISSUED' | 'FAILED';

export type SaleData = {
  customerEntityId: string;
  isWalkIn?: boolean;
  /** Pre-tax (tax-exclusive) revenue. */
  taxableAmount: string;
  taxLines?: SaleTaxLineData[];
  cogsTotal?: string;
  payments?: { cash?: string; card?: string; bank?: string };
  createdBy?: string | null;
  syncStatus?: string;
  /** When the sale was rung up (client-set on create). The invoice date anchors to this — not the
   *  CF's render time — so a later retry can't reprint a different day (ticket #80). */
  createdAt?: Timestamp | null;

  // ── fields the invoice payload needs (ticket #76), all snapshotted on the sale doc ──
  lines?: SaleLineData[];
  subtotal?: string;
  saleDiscount?: string;
  grandTotal?: string;
  amountPaid?: string;
  balanceRemaining?: string;
  note?: string | null;
  /** Walk-in buyer capture (client-set on create; UI in T2). */
  buyerName?: string | null;
  buyerPhone?: string | null;
  /** How the sale was priced — snapshotted for reporting (ticket #106). Absent → tax-exclusive. */
  taxInclusive?: boolean | null;
  /** The buyer's tax/GST number, snapshotted from their contact at record time (ticket #106) — the
   *  invoice's Bill-To "GST/HST No: …" line. Absent for a walk-in or a customer without one. */
  buyerTaxNumber?: string | null;

  /** HL's operational lifecycle mirror. Absent/`COMPLETED` for a live sale; `VOIDED` once
   *  reversed (ticket #85). The sync paths skip a VOIDED sale so it can't be (re)posted to HL. */
  status?: 'COMPLETED' | 'VOIDED';

  // ── HL ids the void needs (CF-owned; persisted by syncSale, ticket #85) ──
  /** HL's SALE transaction id — the fallback a void reverses when no invoice can be cancelled. */
  hlSaleId?: string | null;
  /** HL's invoice id (distinct from the human `invoiceNumber`) — what a void cancels + refunds against. */
  hlInvoiceId?: string | null;

  // ── void spine (ticket #85) ──
  voidStatus?: 'PENDING' | 'DONE' | 'FAILED' | null;
  voidReason?: string | null;
  voidRequestedBy?: string | null;
  voidRequestedAt?: Timestamp | null;
  voidedAt?: Timestamp | null;
  voidError?: string | null;
  hlVoidTxnId?: string | null;
  hlRefundIds?: string[] | null;

  // ── invoice state (CF-owned) ──
  invoiceNumber?: string | null;
  invoiceUrl?: string | null;
  invoiceStatus?: InvoiceStatus;
  invoiceError?: string | null;
  /** Count of failed render attempts — bounds the reconcile retry so a permanent failure
   *  (e.g. HL returned no invoice number) doesn't churn forever. Moot once ISSUED (the sale
   *  is no longer re-queried). */
  invoiceAttempts?: number;
  /** Desktop-initiated retry request (ticket #77): the Admin SDK bumps this to ask `onSaleWrite`
   *  for an immediate re-issue. Mobile uses the `retryInvoice` callable instead. */
  invoiceRetryRequestedAt?: Timestamp | null;
};

/** The seller letterhead, read from `companySettings/profile` (ticket #76). */
export type CompanyProfileData = {
  companyName?: string;
  legalName?: string | null;
  logoUrl?: string | null;
  taxNumber?: string | null;
  businessAddress?: string | null;
  contactEmail?: string | null;
  contactPhone?: string | null;
  /**
   * The shop's IANA timezone (e.g. `"America/Vancouver"`), set at company provisioning. The invoice
   * date is formatted in this zone (ticket #80); absent/invalid → the CF falls back to UTC.
   */
  timezone?: string | null;
};

/** The resolved buyer block for the invoice (named party's profile, or a walk-in's capture). */
export type InvoiceBuyer = { name?: string; address?: string; phone?: string; email?: string };

/** Deterministic idempotency key per HL leg: "sale_<docId>:<kind>". */
export function saleSourceId(
  saleId: string,
  kind: 'sale' | 'payment_cash' | 'payment_card' | 'payment_bank',
): string {
  return `sale_${saleId}:${kind}`;
}

/** Maps a snapshotted tax leg's name to its HL liability account name. */
function taxAccountName(taxLineName: string): string {
  return `${taxLineName} Payable`; // "GST" → "GST Payable", "PST"/"HST" likewise
}

// ---------- invoice payload (ticket #76) ----------
//
// The TypeScript mirror of the canonical, unit-tested Kotlin `BuildInvoicePayloadUseCase`.
// No device ever calls the bill engine (unauthenticated endpoint → CF-only), so this shape is
// kept in lockstep with the Kotlin spec, exactly like `saleSourceId`. Money crosses to a JSON
// number only here, at the real HTTP boundary; the `*Disp` fields carry pre-formatted CAD
// strings (the engine formats money Indian-style, so we never let it format our totals).

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Returns `timeZone` when it's a valid IANA id, else `"UTC"` — so a missing or mistyped zone
 * degrades to UTC instead of throwing (`Intl` throws `RangeError` on an unknown id). One place to
 * resolve the zone, shared by the date and the time formatting below.
 */
function resolveZone(timeZone?: string | null): string {
  if (!timeZone) return 'UTC';
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return timeZone;
  } catch {
    return 'UTC';
  }
}

/**
 * Formats an instant as a display date, e.g. `"29 Jul 2026"`, in the shop's IANA timezone
 * (`companySettings/profile.timezone`, e.g. `"America/Vancouver"`) — ticket #80.
 *
 * Formatting in the shop's zone keeps an evening sale on its own calendar day, so it can't slip
 * into the next day (and, at a month/quarter boundary, the wrong tax period) the way a raw UTC
 * date does west of UTC. Falls back to **UTC** when no zone is configured or the id is invalid: an
 * un-provisioned company keeps the prior v1 behavior, and a config typo never breaks rendering.
 */
export function formatIssueDate(d: Date, timeZone?: string | null): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: resolveZone(timeZone),
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
  }).formatToParts(d);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
  return `${Number(get('day'))} ${MONTHS[Number(get('month')) - 1]} ${get('year')}`;
}

/**
 * The invoice header stamp: the date **plus** the sale's local time-of-day and zone abbreviation,
 * e.g. `"29 Jul 2026, 8:32 PM PDT"` — the sale instant rendered in the shop's timezone (ticket #80).
 *
 * The bill-engine template prints the `issueDate` string verbatim, so the time rides along in that
 * same field — no template change needed. Uses the same UTC fallback as [formatIssueDate].
 */
export function formatIssueDateTime(d: Date, timeZone?: string | null): string {
  const time = new Intl.DateTimeFormat('en-US', {
    timeZone: resolveZone(timeZone),
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
    timeZoneName: 'short',
  }).format(d);
  return `${formatIssueDate(d, timeZone)}, ${time}`;
}

/** Trimmed non-empty string, or undefined (so JSON.stringify drops the key — never a blank). */
export function blank(s?: string | null): string | undefined {
  const t = s?.trim();
  return t ? t : undefined;
}

/**
 * Rounds a non-negative decimal string **half-up to exactly 2 dp** for display — pure BigInt,
 * no float touching money (CLAUDE.md). Kept in lockstep with the shared Kotlin
 * `Money.multiplyRate(x, "1")`, so the CAD `*Disp` strings match the canonical spec even on a
 * >2-dp input (a truncate here would diverge from Kotlin's half-up). The sign, when an amount
 * reduces the balance, is added by the caller (`dispNegative`).
 */
function money2(s: string): string {
  const [intPart, fracRaw = ''] = s.trim().split('.');
  const guard = (fracRaw + '000').slice(0, 3); // 2 kept fraction digits + 1 rounding guard
  let cents = BigInt((intPart || '0') + guard.slice(0, 2)); // value × 100, exact
  if (guard.charCodeAt(2) - 48 >= 5) cents += 1n; // half-up on the guard digit
  const str = cents.toString().padStart(3, '0'); // ≥3 chars so the 2-dp split is safe
  return `${str.slice(0, -2)}.${str.slice(-2)}`;
}

/** Inserts thousands separators: `"2530.00" → "2,530.00"`. */
function groupThousands(v: string): string {
  const [i, f] = v.split('.');
  const grouped = i.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return f ? `${grouped}.${f}` : grouped;
}

/** CAD display: `"2530" → "$2,530.00"`. Undefined in → undefined out. */
function disp(s?: string): string | undefined {
  if (s == null) return undefined;
  return `$${groupThousands(money2(s))}`;
}

/** CAD display with a leading minus, for amounts that reduce the balance. */
function dispNegative(s: string): string {
  return `-${disp(s)}`;
}

/**
 * Decimal rate string → a trimmed percent: `"0.05" → "5"`, `"0.075" → "7.5"`, `"0.13" → "13"`.
 *
 * Pure string/BigInt (no float), in lockstep with the Kotlin
 * `trimZeros(Money.multiplyRate(rate, "100"))`: shift the rate two places (× 100), round half-up
 * to 2 dp, then drop trailing zeros. An earlier float mirror could drift from the Kotlin spec.
 */
function formatPercent(rate: string): string {
  return trimZeros(money2(shiftRightTwo(rate)));
}

/** Multiply a non-negative decimal string by 100 by shifting the point two places (exact). */
function shiftRightTwo(s: string): string {
  const t = s.trim();
  const dot = t.indexOf('.');
  if (dot < 0) return `${t}00`;
  const intPart = t.slice(0, dot);
  const frac = t.slice(dot + 1);
  return frac.length <= 2
    ? intPart + frac.padEnd(2, '0') // point clears the fraction → an integer
    : `${intPart}${frac.slice(0, 2)}.${frac.slice(2)}`; // remainder stays fractional
}

/** Drops a trailing `.00`/`.50`→`.5` from a fixed-2-dp string for a clean percent label. */
function trimZeros(v: string): string {
  return v.includes('.') ? v.replace(/\.?0+$/, '') : v;
}

/** `"GST"` + `"0.05"` → `"GST 5%"`; no rate → just the name. */
function taxLabel(name: string, rate?: string): string {
  return rate ? `${name} ${formatPercent(rate)}%` : name;
}

/** `"+1 …" · "a@b.com"`, omitting blanks; undefined when both absent. */
export function composeContact(phone?: string | null, email?: string | null): string | undefined {
  const parts = [blank(phone), blank(email)].filter(Boolean);
  return parts.length ? parts.join('  ·  ') : undefined;
}

/**
 * Build the bill-engine payload from the snapshotted sale + company letterhead + HL's invoice
 * number. Pure — no I/O. Mirrors the Kotlin `BuildInvoicePayloadUseCase`; keep the two in sync.
 */
export function buildInvoicePayload(
  sale: SaleData,
  profile: CompanyProfileData,
  invoiceNumber: string,
  issueDate: string,
  buyer?: InvoiceBuyer,
): Record<string, unknown> {
  const lineItems = (sale.lines ?? []).map((line) =>
    line.kind === 'INVENTORY'
      ? { name: line.label, hsn: line.imei, qty: 1, rate: Number(line.netPrice) } // hsn = IMEI column
      : { name: line.name, qty: 1, rate: Number(line.netPrice) }, // custom line — no hsn
  );

  const tax1 = sale.taxLines?.[0];
  const tax2 = sale.taxLines?.[1];
  const hasDiscount = Number(sale.saleDiscount ?? '0') > 0;
  const hasPaid = Number(sale.amountPaid ?? '0') > 0;

  const customer =
    buyer && (buyer.name || buyer.address || buyer.phone || buyer.email)
      ? {
          name: blank(buyer.name),
          address: blank(buyer.address),
          phone: blank(buyer.phone),
          email: blank(buyer.email),
        }
      : undefined;

  const data: Record<string, unknown> = {
    invoiceNumber,
    issueDate,
    // "USD" is sent PURELY to select the `$` glyph — NOT a currency claim. The engine has no
    // CAD symbol; "USD" never prints (the template says "…Canadian Dollars (CAD)") and the sale
    // stays CAD in Firestore/HL. Do NOT read this as a USD sale.
    currency: 'USD',

    // ── Seller (letterhead) ──
    sellerName: blank(profile.legalName) ?? blank(profile.companyName),
    sellerAddress: blank(profile.businessAddress),
    sellerContact: composeContact(profile.contactPhone, profile.contactEmail),
    sellerPhone: blank(profile.contactPhone),
    sellerTaxLine: blank(profile.taxNumber) ? `GST/HST No: ${blank(profile.taxNumber)}` : undefined,
    logoUrl: blank(profile.logoUrl),

    // ── Buyer ──
    customer,
    // The buyer's tax number, snapshotted on the sale (ticket #106) — mirrors `sellerTaxLine`.
    // `undefined` when absent so the key is omitted and the template's `{{customerTaxLine}}`
    // placeholder renders as an empty string (no stray label/row).
    customerTaxLine: blank(sale.buyerTaxNumber) ? `GST/HST No: ${blank(sale.buyerTaxNumber)}` : undefined,

    // ── Items + raw subtotal (a JSON number for the engine) ──
    lineItems,
    subtotal: Number(sale.subtotal ?? '0'),

    // ── Totals — pre-formatted CAD `*Disp` strings ──
    subtotalDisp: disp(sale.subtotal),
    discountLabel: hasDiscount ? 'Discount' : undefined,
    discountDisp: hasDiscount ? dispNegative(sale.saleDiscount as string) : undefined,
    tax1Label: tax1 ? taxLabel(tax1.name, tax1.rate) : undefined,
    tax1Disp: tax1 ? disp(tax1.amount) : undefined,
    tax2Label: tax2 ? taxLabel(tax2.name, tax2.rate) : undefined,
    tax2Disp: tax2 ? disp(tax2.amount) : undefined,
    totalDisp: disp(sale.grandTotal),
    amountPaidLabel: hasPaid ? 'Amount Paid' : undefined,
    amountPaidDisp: hasPaid ? dispNegative(sale.amountPaid as string) : undefined,
    balanceLabel: 'Balance Due',
    balanceDisp: disp(sale.balanceRemaining),

    notesText: blank(sale.note),
  };

  return { appId: 'aromex', data };
}

/**
 * Resolve the buyer block for the invoice's "Bill To" box. Always returns a buyer with a
 * non-empty `name` so the box never renders blank (ticket #80): a walk-in uses the captured
 * `buyerName` (falling back to `WALK_IN_CUSTOMER_NAME` when none was typed — the common case);
 * a named customer uses their `entities/{id}` profile, falling back to the same placeholder when
 * that doc is missing or unnamed.
 */
export async function resolveInvoiceBuyer(db: Firestore, data: SaleData): Promise<InvoiceBuyer> {
  if (data.isWalkIn) {
    const name = blank(data.buyerName);
    const phone = blank(data.buyerPhone);
    return { name: name ?? WALK_IN_CUSTOMER_NAME, phone };
  }
  const snap = await db.collection('entities').doc(data.customerEntityId).get();
  const e = snap.data() as
    | { name?: string; email?: string | null; phones?: string[]; address?: string | null }
    | undefined;
  if (!e) return { name: WALK_IN_CUSTOMER_NAME };
  return {
    name: blank(e.name) ?? WALK_IN_CUSTOMER_NAME,
    address: blank(e.address),
    // Prefer the phone snapshotted on the sale — it's prefilled from the contact but editable at
    // checkout and may not have been saved back, so it must win over the stored number (mirrors how
    // `customerTaxLine` uses `sale.buyerTaxNumber`). Fall back to the contact's primary number for
    // sales recorded before the phone was snapshotted for named customers.
    phone: blank(data.buyerPhone) ?? blank(e.phones?.[0]),
    email: blank(e.email),
  };
}

/**
 * Issue the sale's PDF invoice (ticket #76): read the seller letterhead, build the payload, POST
 * it to the bill engine, and persist `invoiceUrl` + `invoiceStatus: ISSUED`. **Never throws** —
 * a render failure marks `invoiceStatus: FAILED` + `invoiceError` and leaves the sale and the HL
 * legs untouched; the reconcile sweep retries. Idempotent: an already-ISSUED sale is skipped, so
 * a retry reuses HL's same number → the same permanent URL, never a second invoice.
 *
 * `countAttempt` (default `true`) governs whether a failure increments `invoiceAttempts`, which
 * bounds the automatic reconcile sweep (`MAX_INVOICE_ATTEMPTS`). Automatic issuance counts;
 * a cashier's manual Retry passes `false` so it can't exhaust — and permanently disable — the
 * automatic safety net (ticket #77).
 */
export async function issueSaleInvoice(
  db: Firestore,
  saleId: string,
  data: SaleData,
  cfg: SyncConfig,
  invoiceNumber: string | undefined,
  now: Date,
  countAttempt = true,
): Promise<void> {
  const ref = db.collection('sales').doc(saleId);
  if (data.invoiceStatus === 'ISSUED') return; // already done — no second PDF/number

  const number = invoiceNumber ?? data.invoiceNumber ?? undefined;
  try {
    if (!number) throw new Error('no HL invoice number available to issue');
    const profile = ((await db.collection('companySettings').doc('profile').get()).data() ??
      {}) as CompanyProfileData;
    const buyer = await resolveInvoiceBuyer(db, data);
    // Anchor the invoice stamp to the sale instant (falling back to now for legacy docs), rendered
    // as date + local time in the shop's timezone — see formatIssueDateTime (ticket #80).
    const saleInstant = data.createdAt?.toDate() ?? now;
    const issueDate = formatIssueDateTime(saleInstant, profile.timezone);
    const payload = buildInvoicePayload(data, profile, number, issueDate, buyer);
    const url = await renderInvoice(cfg.billEngineUrl, payload);
    await ref.set(
      {
        invoiceNumber: number,
        invoiceUrl: url,
        invoiceStatus: 'ISSUED',
        invoiceIssuedAt: FieldValue.serverTimestamp(),
        invoiceError: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  } catch (err) {
    const details = err instanceof BillEngineError && err.details != null ? ` :: ${safeJson(err.details)}` : '';
    await ref.set(
      {
        invoiceStatus: 'FAILED',
        invoiceError: `${(err as Error)?.message ?? String(err)}${details}`,
        // Only automatic issuance counts toward MAX_INVOICE_ATTEMPTS (see index.ts); a manual
        // Retry must not exhaust the automatic sweep's budget (ticket #77).
        ...(countAttempt ? { invoiceAttempts: FieldValue.increment(1) } : {}),
        ...(number ? { invoiceNumber: number } : {}),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    // Deliberately no rethrow: the books + sale are already correct; only the PDF is pending.
  }
}

/** The client-facing invoice projection returned by [retryInvoiceCore]. */
export type InvoiceResult = {
  status: InvoiceStatus | 'PENDING';
  invoiceNumber: string | null;
  invoiceUrl: string | null;
};

function invoiceResultOf(data: SaleData): InvoiceResult {
  return {
    status: data.invoiceStatus ?? 'PENDING',
    invoiceNumber: data.invoiceNumber ?? null,
    invoiceUrl: data.invoiceUrl ?? null,
  };
}

/**
 * On-demand invoice retry (ticket #77) — the work behind the cashier-facing Retry, factored out of
 * the `retryInvoice` callable so it is unit-testable. Re-runs T1's idempotent [issueSaleInvoice]
 * immediately instead of waiting for the reconcile sweep; builds no new invoice (reuses HL's
 * number). Flips the doc to `PENDING` first so live listeners show "preparing", then returns the
 * settled state.
 *
 * @throws Error('not-found') if the sale doc is missing (mapped to an HttpsError by the caller).
 */
export async function retryInvoiceCore(
  db: Firestore,
  cfg: SyncConfig,
  saleId: string,
  now: Date,
): Promise<InvoiceResult> {
  const ref = db.collection('sales').doc(saleId);
  const snap = await ref.get();
  if (!snap.exists) throw new Error('not-found');
  const data = snap.data() as SaleData;

  // Already issued (idempotent) → return the good state; no second render.
  if (data.invoiceStatus === 'ISSUED') return invoiceResultOf(data);
  // Not yet posted to HL → issuance isn't its turn; the sale-sync trigger/sweep owns it.
  if (data.syncStatus !== 'SYNCED') return invoiceResultOf(data);

  // Flip to PENDING first so live listeners show "preparing" during the re-render.
  await ref.set({ invoiceStatus: 'PENDING', updatedAt: FieldValue.serverTimestamp() }, { merge: true });

  // Re-run T1's idempotent worker — it never throws; it settles the doc to ISSUED or FAILED.
  // `countAttempt: false` — a manual Retry must not consume the automatic reconcile budget.
  await issueSaleInvoice(db, saleId, data, cfg, data.invoiceNumber ?? undefined, now, false);

  const after = (await ref.get()).data() as SaleData;
  return invoiceResultOf(after);
}

/** Best-effort JSON for the engine's error `details` (never throws). */
function safeJson(v: unknown): string {
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

/**
 * Post one sale to HL: recognise pre-tax revenue + AR + 0–2 tax legs and (when there are
 * inventory lines) relieve the Inventory asset + book COGS via the `/sales` cogs trio,
 * then settle the paid part with one `/payments` per non-zero method (card → Bank, ticket
 * #61). All legs idempotent on appId+sourceId. On success → SYNCED; on genuine failure →
 * FAILED + rethrow (retry:true redelivers). When the (named) party isn't synced yet, the
 * record is quietly left PENDING for the reconcile sweep.
 */
export async function syncSale(saleId: string, data: SaleData, cfg: SyncConfig): Promise<void> {
  const db = getFirestore();
  const ref = db.collection('sales').doc(saleId);
  // A voided sale must never be (re)posted to HL (ticket #85): a void may have restored stock and
  // reversed the ledger while syncStatus was still PENDING/FAILED, and reconcile/onSaleWrite would
  // otherwise re-drive this. The void itself is what settles the books.
  if (data.status === 'VOIDED') return;
  try {
    const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);

    const hlCustomerId = await resolvePartyHlCustomerId(db, data.customerEntityId, cfg);
    if (!hlCustomerId) {
      // Named customer not synced to HL yet. Leave PENDING WITHOUT rewriting the doc (a
      // rewrite would re-fire this trigger in a hot loop); the reconcile sweep retries it.
      return;
    }

    const revenueAccountId = await getOrCreateAccount(cfg.hlBaseUrl, token, 'Sales Revenue', 'INCOME');
    // The sale's business date, in the shop's zone — every leg below is dated with it.
    const saleDate = isoDate(data.createdAt, await shopTimeZone(db));

    // Resolve one HL tax account per snapshotted tax leg (GST/PST/HST Payable).
    const taxLines: SaleTaxLineInput[] = [];
    for (const leg of data.taxLines ?? []) {
      if (!isPositiveAmount(leg.amount)) continue;
      const accountId = await getOrCreateAccount(
        cfg.hlBaseUrl,
        token,
        taxAccountName(leg.name),
        'LIABILITY',
        true, // isTaxAccount
      );
      taxLines.push({ amount: leg.amount, accountId });
    }

    // COGS trio only when the sale relieved stock (all-or-none; HL requires cogsAmount ≥ 0.01).
    let cogs: { cogsAmount: string; inventoryAccountId: string; cogsAccountId: string } | undefined;
    if (isPositiveAmount(data.cogsTotal)) {
      const inventoryAccountId = await getOrCreateAccount(cfg.hlBaseUrl, token, 'Inventory', 'ASSET');
      const cogsAccountId = await getOrCreateAccount(
        cfg.hlBaseUrl,
        token,
        'Cost of Goods Sold',
        'EXPENSE',
      );
      cogs = { cogsAmount: data.cogsTotal as string, inventoryAccountId, cogsAccountId };
    }

    // Settle the paid part: one payment per non-zero method. Card lands in Bank (ticket #61).
    // Payment accounts aren't customer-scoped — resolve them before the self-heal wrapper so
    // a retry replays only the customer-scoped posts.
    const payments = data.payments ?? {};
    const cashAccountId = isPositiveAmount(payments.cash)
      ? await getOrCreateAccount(cfg.hlBaseUrl, token, 'Cash', 'ASSET')
      : undefined;
    const bankAccountId =
      isPositiveAmount(payments.bank) || isPositiveAmount(payments.card)
        ? await getOrCreateAccount(cfg.hlBaseUrl, token, 'Bank', 'ASSET')
        : undefined;

    // Customer-scoped posts (sale + payments). Wrapped so a stale cached hlCustomerId
    // (HL 404) self-heals: re-resolve the party and replay once (idempotent → no double-post).
    // Returns HL's sale response so we can read the invoice number HL minted (ticket #76).
    const saleResult = await withCustomerSelfHeal(
      db,
      data.customerEntityId,
      cfg,
      token,
      hlCustomerId,
      async (cid) => {
        const result = await createSale(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.taxableAmount,
          description: 'Sale (Aromex)',
          revenueAccountId,
          taxLines,
          ...cogs,
          // `createdAt` is the sale's BUSINESS date (ticket #107) — today for a normal sale, the
          // real date when old books are being entered. Revenue, tax and COGS all land on it.
          date: saleDate,
          sourceId: saleSourceId(saleId, 'sale'),
          actorRef: data.createdBy ?? undefined,
        });

        // Apply this sale's money to the invoice `/sales` just created. Without the id HL still
        // moves the customer's AR, but the invoice's amountPaid stays 0 and its status stays
        // PENDING forever — so a walk-in who paid cash in full leaves a permanently unpaid
        // invoice and `GET /receivables` reports money already collected at the counter.
        const invoiceId = result.data?.invoice?.id;

        // Name the invoice on the payment, so a party's statement says what the money was FOR.
        // HL's default reads "Payment from <name>", which on a statement sitting under a sale of a
        // different amount leaves the reader guessing whether the two are even related — and a
        // sale's charge and its cash rarely match once a customer has credit.
        const invoiceNumber = result.data?.invoice?.invoiceNumber;
        const paymentDescription = invoiceNumber ? `Payment for ${invoiceNumber}` : undefined;

        if (cashAccountId) {
          await createPayment(cfg.hlBaseUrl, token, {
            customerId: cid,
            amount: payments.cash as string,
            paymentAccountId: cashAccountId,
            invoiceId,
            description: paymentDescription,
            date: saleDate,
            sourceId: saleSourceId(saleId, 'payment_cash'),
            actorRef: data.createdBy ?? undefined,
          });
        }
        if (isPositiveAmount(payments.card) && bankAccountId) {
          await createPayment(cfg.hlBaseUrl, token, {
            customerId: cid,
            amount: payments.card as string,
            paymentAccountId: bankAccountId,
            invoiceId,
            description: paymentDescription,
            date: saleDate,
            sourceId: saleSourceId(saleId, 'payment_card'),
            actorRef: data.createdBy ?? undefined,
          });
        }
        if (isPositiveAmount(payments.bank) && bankAccountId) {
          await createPayment(cfg.hlBaseUrl, token, {
            customerId: cid,
            amount: payments.bank as string,
            paymentAccountId: bankAccountId,
            invoiceId,
            description: paymentDescription,
            date: saleDate,
            sourceId: saleSourceId(saleId, 'payment_bank'),
            actorRef: data.createdBy ?? undefined,
          });
        }
        return result;
      },
    );

    // HL is posted. Record SYNCED + the invoice number HL minted, and mark the invoice PENDING
    // (unless a prior run already ISSUED it) so the reconcile sweep can pick it up if issuance
    // below is interrupted.
    const invoiceNumber = saleResult?.data?.invoice?.invoiceNumber ?? data.invoiceNumber ?? undefined;
    // Persist HL's ids so a later void can cancel/reverse without a lookup (HL has no
    // get-by-number/sourceId) — ticket #85. Older sales lack these; voidSaleCore falls back to an
    // idempotent createSale replay, which returns the same ids.
    const hlInvoiceId = saleResult?.data?.invoice?.id ?? data.hlInvoiceId ?? undefined;
    const hlSaleId = saleResult?.data?.transaction?.id ?? data.hlSaleId ?? undefined;
    await ref.set(
      {
        syncStatus: 'SYNCED',
        hlSyncedAt: FieldValue.serverTimestamp(),
        hlSyncError: FieldValue.delete(),
        ...(invoiceNumber ? { invoiceNumber } : {}),
        ...(hlInvoiceId ? { hlInvoiceId } : {}),
        ...(hlSaleId ? { hlSaleId } : {}),
        ...(data.invoiceStatus === 'ISSUED' ? {} : { invoiceStatus: 'PENDING' }),
      },
      { merge: true },
    );

    // Issue the PDF invoice. Self-contained: never throws, so a render failure leaves the sale
    // and the books intact (marks invoiceStatus=FAILED) and is retried by reconcile (ticket #76).
    await issueSaleInvoice(db, saleId, data, cfg, invoiceNumber, new Date());
  } catch (err) {
    await ref.set(
      { syncStatus: 'FAILED', hlSyncError: (err as Error)?.message ?? String(err) },
      { merge: true },
    );
    throw err;
  }
}

// ---------- money movement (ticket #90) ----------

export type MoneyAccountKind = 'PARTY' | 'CASH' | 'BANK';

export type MoneyAccountRefData = {
  kind: MoneyAccountKind;
  entityId?: string | null;
};

export type MoneyEntryData = {
  from: MoneyAccountRefData;
  to: MoneyAccountRefData;
  /** Decimal string, always positive — direction is carried by from/to, never by a sign. */
  amount: string;
  note?: string | null;
  /** Accounting date (may be backdated); distinct from createdAt. */
  entryDate?: Timestamp | null;
  createdBy?: string | null;
  syncStatus?: string;
  hlTransactionId?: string | null;
  /** Set on a reversing entry: the entry it mirrors. */
  reversesEntryId?: string | null;
};

/** Deterministic idempotency key. A CF retry replays the same key, so HL returns the original
 *  posting instead of moving the money twice — the property the legacy app had no equivalent of. */
export function moneyEntrySourceId(entryId: string): string {
  return `money_${entryId}`;
}

/**
 * `yyyy-MM-dd` in the **shop's** timezone — the accounting date HL stores.
 *
 * Not UTC: a 6pm sale in Vancouver is already tomorrow in UTC, so a UTC slice dated an evening's
 * takings to the next day — and at a month or quarter boundary, into the wrong tax period. Falls
 * back to UTC when no zone is configured, matching [formatIssueDate].
 */
export function isoDateInZone(d: Date, timeZone?: string | null): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: resolveZone(timeZone),
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(d);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** The shop's IANA zone, for dating ledger postings. One read; UTC when unset. */
async function shopTimeZone(db: Firestore): Promise<string | undefined> {
  const snap = await db.collection('companySettings').doc('profile').get();
  return (snap.data() as CompanyProfileData | undefined)?.timezone ?? undefined;
}

/** The accounting date for a doc, from its business-date timestamp. */
function isoDate(ts?: Timestamp | null, timeZone?: string | null): string {
  return isoDateInZone(ts ? ts.toDate() : new Date(), timeZone);
}

/**
 * The same validation the shared `RecordMoneyEntryUseCase` applies, re-run server-side. The client
 * checks so the cashier gets an inline message; the CF checks because a client can lie and this
 * writes to the books. Returns a reason string, or undefined when the entry is sound.
 */
export function moneyEntryRejection(data: MoneyEntryData): string | undefined {
  const validKind = (k?: string): boolean => k === 'PARTY' || k === 'CASH' || k === 'BANK';
  if (!validKind(data.from?.kind) || !validKind(data.to?.kind)) return 'unknown account kind';
  if (data.from.kind === 'PARTY' && !data.from.entityId) return 'from party has no entityId';
  if (data.to.kind === 'PARTY' && !data.to.entityId) return 'to party has no entityId';
  if (sameAccount(data.from, data.to)) return 'from and to are the same account';
  const amount = Number(data.amount);
  if (!Number.isFinite(amount) || amount <= 0) return 'amount must be a positive number';
  return undefined;
}

function sameAccount(a: MoneyAccountRefData, b: MoneyAccountRefData): boolean {
  if (a.kind !== b.kind) return false;
  return a.kind !== 'PARTY' || a.entityId === b.entityId;
}

/** The shop's own asset account for a CASH/BANK side, created on first use like the tax accounts. */
async function ownAccountId(
  hlBaseUrl: string,
  token: string,
  kind: 'CASH' | 'BANK',
): Promise<string> {
  return getOrCreateAccount(hlBaseUrl, token, kind === 'CASH' ? 'Cash' : 'Bank', 'ASSET');
}

/**
 * Resolve a party's **AR sub-account** id — what a raw journal entry posts against, as opposed to
 * the customer id the high-level endpoints take. `syncEntity` caches it as `hlAccountId`; resolving
 * the customer first covers a party whose HL sync hasn't landed yet (returns undefined → leave the
 * entry PENDING for reconcile, never a half-posted movement).
 */
async function resolvePartyHlAccountId(
  db: Firestore,
  partyEntityId: string,
  cfg: SyncConfig,
): Promise<string | undefined> {
  const customerId = await resolvePartyHlCustomerId(db, partyEntityId, cfg);
  if (!customerId) return undefined;
  const snap = await db.collection('entities').doc(partyEntityId).get();
  const hlAccountId = (snap.data() as { hlAccountId?: string | null } | undefined)?.hlAccountId;
  return hlAccountId ?? undefined;
}

/**
 * Post one money movement to HL (ticket #90).
 *
 * Routing — prefer a high-level endpoint over a raw journal wherever one fits, because HL treats
 * those as first-class (proper posting type, and `/payments` also participates in invoice state):
 *
 * | from → to        | call                  |
 * |------------------|-----------------------|
 * | party → cash/bank| `/payments`           |
 * | cash/bank → party| `/customer-payouts`   |
 * | party → party    | `/transactions` JOURNAL |
 * | cash ↔ bank      | `/transactions` JOURNAL |
 *
 * Every path is idempotent on `(appId, sourceId)`, so a redelivered trigger re-posts nothing. On a
 * party that HL doesn't know yet the entry is left PENDING **without rewriting the doc** — a rewrite
 * would re-fire this trigger in a hot loop (the #58 lesson); the reconcile sweep picks it up.
 */
export async function syncMoneyEntry(
  entryId: string,
  data: MoneyEntryData,
  cfg: SyncConfig,
): Promise<void> {
  const db = getFirestore();
  const ref = db.collection('moneyEntries').doc(entryId);

  const rejection = moneyEntryRejection(data);
  if (rejection) {
    // Malformed beyond retrying — mark it and stop, rather than failing forever in the sweep.
    await ref.set({ syncStatus: 'FAILED', hlSyncError: rejection }, { merge: true });
    return;
  }

  try {
    const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);
    const sourceId = moneyEntrySourceId(entryId);
    const actorRef = data.createdBy ?? undefined;
    const date = isoDate(data.entryDate, await shopTimeZone(db));

    // A correction is posted as the *inverse movement* (from/to swapped by `reverseEntry`) rather
    // than through HL's `/transactions/{id}/reverse`. Deliberate: reverse needs the original's
    // transaction id, and `/payments` and `/customer-payouts` don't document one in their response —
    // so relying on it would work for two of the four routes and quietly fail for the other two.
    // The inverse posts identically through every route and nets the books to exactly zero; the
    // description carries the intent so a statement reads as a correction, and the two entries are
    // linked in Firestore so the UI can pair them.
    const description = data.reversesEntryId
      ? `Reversal — ${data.note?.trim() || 'money movement'}`
      : data.note?.trim() || 'Money movement (Aromex)';

    let hlTransactionId: string | undefined;
    const { from, to } = data;

    if (from.kind === 'PARTY' && to.kind !== 'PARTY') {
      // They paid us.
      const customerId = await resolvePartyHlCustomerId(db, from.entityId as string, cfg);
      if (!customerId) return;
      const accountId = await ownAccountId(cfg.hlBaseUrl, token, to.kind);
      await withCustomerSelfHeal(db, from.entityId as string, cfg, token, customerId, async (cid) => {
        hlTransactionId = await createPayment(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.amount,
          paymentAccountId: accountId,
          // Deliberately unapplied: this settles the party's overall balance, not a named invoice
          // (brief #89). A sale's own payments DO carry invoiceId — see #88.
          description,
          date,
          sourceId,
          actorRef,
        });
      });
    } else if (from.kind !== 'PARTY' && to.kind === 'PARTY') {
      // We paid or lent them.
      const customerId = await resolvePartyHlCustomerId(db, to.entityId as string, cfg);
      if (!customerId) return;
      await withCustomerSelfHeal(db, to.entityId as string, cfg, token, customerId, async (cid) => {
        hlTransactionId = await createCustomerPayout(cfg.hlBaseUrl, token, {
          customerId: cid,
          amount: data.amount,
          method: from.kind === 'CASH' ? 'CASH' : 'BANK',
          description,
          date,
          sourceId,
          actorRef,
        });
      });
    } else {
      // Party → party (an assignment of receivable) or cash ↔ bank (a deposit/withdrawal).
      // Debit what receives, credit what gives — the same shape in both cases.
      const debitAccount = await accountIdFor(db, cfg, token, to);
      const creditAccount = await accountIdFor(db, cfg, token, from);
      if (!debitAccount || !creditAccount) return; // party not synced yet → leave for reconcile

      const entries: JournalEntryLine[] = [
        { accountId: debitAccount, type: 'DEBIT', amount: data.amount },
        { accountId: creditAccount, type: 'CREDIT', amount: data.amount },
      ];
      const res = await createJournalEntry(cfg.hlBaseUrl, token, {
        entries,
        description,
        date,
        sourceId,
        actorRef,
      });
      hlTransactionId = res.data?.id;
    }

    await ref.set(
      {
        syncStatus: 'SYNCED',
        hlSyncedAt: FieldValue.serverTimestamp(),
        ...(hlTransactionId ? { hlTransactionId } : {}),
        hlSyncError: FieldValue.delete(),
      },
      { merge: true },
    );
  } catch (err) {
    await ref.set(
      { syncStatus: 'FAILED', hlSyncError: (err as Error)?.message ?? String(err) },
      { merge: true },
    );
    throw err;
  }
}

/** The HL account id for either side of a journal entry. Undefined when a party isn't synced yet. */
async function accountIdFor(
  db: Firestore,
  cfg: SyncConfig,
  token: string,
  ref: MoneyAccountRefData,
): Promise<string | undefined> {
  if (ref.kind === 'PARTY') return resolvePartyHlAccountId(db, ref.entityId as string, cfg);
  return ownAccountId(cfg.hlBaseUrl, token, ref.kind);
}

// ---------- void a sale (ticket #85) ----------

/** Deterministic idempotency key per refund leg: "void_<docId>:refund_<method>". */
export function voidSourceId(saleId: string, kind: 'refund_cash' | 'refund_card' | 'refund_bank'): string {
  return `void_${saleId}:${kind}`;
}

/**
 * A void failure that must NOT be retried by the trigger (`retry:true` would loop forever): the
 * requester isn't an admin, no reason was given, the sale is gone, or a re-used IMEI index blocks
 * the restock. The doc is settled `voidStatus: FAILED` before this is thrown, so the callable maps
 * it to an error and the edge-trigger swallows it (no redelivery). Transient failures (HL/network)
 * throw a plain Error instead and DO redeliver — every step is idempotent, so a replay is safe.
 */
export class VoidPermanentError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'VoidPermanentError';
  }
}

/**
 * True for an HL failure that retrying can never fix — a 4xx validation/state rejection (e.g. a
 * refund `422 EXCESS_REFUND`), excluding the genuinely-retryable 408/429. A void that hits one of
 * these must be settled FAILED **without** rethrowing, or `retry:true` redelivers the same event
 * forever (the void has no reconcile backstop). 5xx/network stays transient and does redeliver.
 */
export function isPermanentHlError(err: unknown): boolean {
  return err instanceof HlHttpError && err.status >= 400 && err.status < 500 && err.status !== 408 && err.status !== 429;
}

/**
 * HL refund codes that mean **the money was already returned by the invoice cancel**, not that the
 * refund failed (measured 2026-07-31): cancelling a paid, invoice-applied sale clears the invoice's
 * paid balance itself, so the subsequent per-method `/refunds` has nothing left to return and 422s
 * with `NOTHING_TO_REFUND` / `ALREADY_REFUNDED` (or `EXCESS_REFUND` once the cap is exhausted). For a
 * void these are benign — the customer's money is already back — so we skip the leg and carry on,
 * rather than failing a void whose ledger is in fact fully reversed. The trail still records the
 * cancel's REVERSAL. (INVALID_STATE and every non-refund 4xx stay real failures.)
 */
const REFUND_ALREADY_SETTLED_CODES = ['NOTHING_TO_REFUND', 'ALREADY_REFUNDED', 'EXCESS_REFUND'];
function refundAlreadySettledByCancel(err: unknown): boolean {
  if (!(err instanceof HlHttpError) || err.status !== 422) return false;
  const detail = err.body ?? err.message ?? '';
  return REFUND_ALREADY_SETTLED_CODES.some((code) => detail.includes(code));
}

export type VoidResult = { status: 'VOIDED' };

/** Read `users/{uid}.role` and assert admin — the server-side gate the client can't bypass. */
export async function assertAdmin(
  db: Firestore,
  uid: string | undefined,
  // Defaults preserve the sale-void wording; the purchase reversal (#106) passes its own.
  subject = { noUser: 'void: no requesting user', notAdmin: 'void: only an admin can void a sale' },
): Promise<void> {
  if (!uid) throw new VoidPermanentError(subject.noUser);
  const snap = await db.collection('users').doc(uid).get();
  const role = (snap.data() as { role?: string } | undefined)?.role;
  if (role !== 'admin') {
    throw new VoidPermanentError(subject.notAdmin);
  }
}

/** The paid amounts to refund, each with the HL account it lands back in — inverts `syncSale`. */
function refundLegs(data: SaleData): Array<{ kind: 'refund_cash' | 'refund_card' | 'refund_bank'; amount: string; account: 'Cash' | 'Bank' }> {
  const p = data.payments ?? {};
  const legs: Array<{ kind: 'refund_cash' | 'refund_card' | 'refund_bank'; amount: string; account: 'Cash' | 'Bank' }> = [];
  if (isPositiveAmount(p.cash)) legs.push({ kind: 'refund_cash', amount: p.cash as string, account: 'Cash' });
  if (isPositiveAmount(p.card)) legs.push({ kind: 'refund_card', amount: p.card as string, account: 'Bank' }); // card → Bank
  if (isPositiveAmount(p.bank)) legs.push({ kind: 'refund_bank', amount: p.bank as string, account: 'Bank' });
  return legs;
}

/**
 * Pre-flight IMEI guard (ticket #85): before any HL reversal, verify no inventory line's IMEI has
 * been re-added to stock under a *different* serial. This moves the common re-used-IMEI detection
 * ahead of the irreversible HL cancel/refund, so that case fails CLEAN — the ledger untouched, the
 * message ("restore it manually before voiding") still accurate — instead of reversing money/tax/
 * COGS and only then discovering the restock can't complete (which would leave HL reversed but the
 * sale not VOIDED). The atomic guard in [restoreStockAndVoid] stays the race-safe authority; this is
 * a best-effort early-out read outside any transaction.
 */
async function assertImeisRestorable(db: Firestore, data: SaleData): Promise<void> {
  const inventoryLines = (data.lines ?? []).filter(
    (l): l is Extract<SaleLineData, { kind: 'INVENTORY' }> => l.kind === 'INVENTORY',
  );
  for (const line of inventoryLines) {
    if (!line.serialId) continue;
    const serialSnap = await db.collection('serials').doc(line.serialId).get();
    if (!serialSnap.exists) continue;
    const imei = (serialSnap.get('imei') as string | undefined)?.trim() || (line.imei ?? '').trim();
    if (!imei) continue;
    const indexSnap = await db.collection('imeiIndex').doc(imei).get();
    const takenBy = indexSnap.exists ? (indexSnap.get('serialId') as string | undefined) : undefined;
    if (takenBy && takenBy !== line.serialId) {
      throw new VoidPermanentError(
        `void: IMEI ${imei} has been re-added to stock under a different unit; ` +
          `restore it manually before voiding this sale.`,
      );
    }
  }
}

/**
 * Restore stock and flip the sale `VOIDED` in **one Firestore transaction** — all-or-nothing (the
 * #58 lesson). For every inventory line: re-`IN_STOCK` the serial, clear its `saleId`, and re-create
 * `imeiIndex/{imei}`. **IMEI guard:** the index was deleted when the unit sold, so if it now exists
 * pointing at a *different* serial (the handset was re-added since), fail rather than clobber it —
 * two serials must never claim one IMEI. [assertImeisRestorable] catches this before the HL reversal
 * in the common case; this transactional re-check is the race-safe authority (the handset could be
 * re-added in the tiny window after that pre-check), so its message notes the reversal already
 * posted. Idempotent: re-reads `status`, no-ops if already VOIDED.
 */
async function restoreStockAndVoid(
  db: Firestore,
  saleId: string,
  data: SaleData,
  now: Date,
  reversalPatch: Record<string, unknown>,
): Promise<void> {
  const saleRef = db.collection('sales').doc(saleId);
  const inventoryLines = (data.lines ?? []).filter(
    (l): l is Extract<SaleLineData, { kind: 'INVENTORY' }> => l.kind === 'INVENTORY',
  );

  await db.runTransaction(async (txn) => {
    // Re-read the sale inside the txn: a concurrent void may have already finished (idempotent).
    const saleSnap = await txn.get(saleRef);
    if ((saleSnap.data() as SaleData | undefined)?.status === 'VOIDED') return;

    // ── Reads first (all reads must precede writes) ──
    const restores: Array<{ serialRef: FirebaseFirestore.DocumentReference; imei: string; productId: string; indexRef: FirebaseFirestore.DocumentReference }> = [];
    for (const line of inventoryLines) {
      if (!line.serialId) continue; // a line with no serial can't be restocked; skip defensively
      const serialRef = db.collection('serials').doc(line.serialId);
      const serialSnap = await txn.get(serialRef);
      if (!serialSnap.exists) continue; // serial gone (e.g. archived-away) — nothing to restore
      const imei = (serialSnap.get('imei') as string | undefined)?.trim() || (line.imei ?? '').trim();
      const productId = (serialSnap.get('productId') as string | undefined) ?? line.productId ?? '';
      if (!imei) continue; // no imei → no index to guard/restore; still re-stocks below
      const indexRef = db.collection('imeiIndex').doc(imei);
      const indexSnap = await txn.get(indexRef);
      // IMEI guard: an index entry pointing at a DIFFERENT serial means the handset was re-added.
      const takenBy = indexSnap.exists ? (indexSnap.get('serialId') as string | undefined) : undefined;
      if (takenBy && takenBy !== line.serialId) {
        throw new VoidPermanentError(
          `void: IMEI ${imei} was re-added to stock under a different unit while the void was in ` +
            `progress; any HL reversal has already posted — restore the IMEI and retry to finish ` +
            `the void.`,
        );
      }
      restores.push({ serialRef, imei, productId, indexRef });
    }

    // ── Writes ──
    for (const line of inventoryLines) {
      if (!line.serialId) continue;
      const serialRef = db.collection('serials').doc(line.serialId);
      // Restock even a serial whose imei we couldn't key (restores.every may not include it):
      // its status/saleId still return to sellable. Index restore only for the guarded ones.
      txn.set(
        serialRef,
        { status: 'IN_STOCK', saleId: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() },
        { merge: true },
      );
    }
    for (const r of restores) {
      txn.set(r.indexRef, { imei: r.imei, serialId: r.serialRef.id, productId: r.productId });
    }

    txn.set(
      saleRef,
      {
        status: 'VOIDED',
        voidStatus: 'DONE',
        voidedAt: FieldValue.serverTimestamp(),
        voidError: FieldValue.delete(),
        // A voided sale owes nothing. The flag is denormalized at creation and drives Sales
        // History's "with balance" filter, so leaving it true listed cancelled sales as debts
        // that would never be collected.
        hasOutstandingBalance: false,
        updatedAt: FieldValue.serverTimestamp(),
        ...reversalPatch,
      },
      { merge: true },
    );
  });
}

/**
 * Void a sale (ticket #85) — the single idempotent worker both transports funnel into (the
 * `voidSale` callable for mobile, the `onSaleWrite` edge-trigger for Desktop), mirroring how
 * `retryInvoice`/`invoiceRetryRequestedAt` both reach `retryInvoiceCore`.
 *
 * A void is a **reversal, never a delete**. In order:
 *  1. Already `VOIDED` → no-op success (the whole path gets retried).
 *  2. Re-verify the requester is an admin **server-side** (`users/{uid}.role`) and that a reason was
 *     given — the client is never trusted, and Desktop's Admin SDK bypasses Firestore rules.
 *  3. If the sale reached HL (`syncStatus == SYNCED`): resolve HL's ids (persisted by `syncSale`, or
 *     recovered by an idempotent `syncSale` replay for a pre-#85 sale), **cancel the invoice** —
 *     which reverses revenue + AR + tax **and** COGS/Inventory in one call (measured; do NOT reverse
 *     COGS again) — guarded by a status read so the non-idempotent cancel is replay-safe; then
 *     **refund** any amount paid, split across the same accounts the payment used.
 *  4. Restore stock and flip `VOIDED` atomically ([restoreStockAndVoid]).
 *
 * @throws VoidPermanentError on a failure that must not be retried (not-admin, no reason, missing
 *   sale, re-used IMEI). The doc is settled `voidStatus: FAILED` first.
 * @throws Error on a transient failure (HL/network) — settled FAILED, then rethrown so `retry:true`
 *   redelivers; every step is idempotent, so the replay never double-reverses or double-refunds.
 */
export async function voidSaleCore(
  db: Firestore,
  cfg: SyncConfig,
  saleId: string,
  requestedByUid: string | undefined,
  reason: string,
  now: Date,
): Promise<VoidResult> {
  const ref = db.collection('sales').doc(saleId);
  const snap = await ref.get();
  if (!snap.exists) throw new VoidPermanentError('void: sale not found');
  let data = snap.data() as SaleData;

  // 1. Idempotent no-op — already voided.
  if (data.status === 'VOIDED') return { status: 'VOIDED' };

  try {
    // 2. Server-side gates (admin + reason). Stamp the trail so it survives even mid-flight.
    await assertAdmin(db, requestedByUid);
    const trimmedReason = reason.trim();
    if (!trimmedReason) throw new VoidPermanentError('void: a reason is required');
    await ref.set(
      {
        voidReason: trimmedReason,
        voidRequestedBy: requestedByUid,
        voidStatus: 'PENDING',
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );

    // 2b. Pre-flight IMEI guard — BEFORE any irreversible HL reversal. A line whose IMEI was
    //     re-added under a different serial fails here, while the ledger is still untouched, rather
    //     than after cancel/refund have already posted (see [assertImeisRestorable]).
    await assertImeisRestorable(db, data);

    const reversalPatch: Record<string, unknown> = {};

    // 3. HL reversal — only when the sale actually reached HL. A never-synced sale (edge case:
    //    syncStatus PENDING/FAILED) has nothing to cancel; restoring stock + VOIDED is enough, and
    //    the VOIDED guard in syncSale stops it being posted later.
    if (data.syncStatus === 'SYNCED') {
      const token = await getHlToken(cfg.gatewayBaseUrl, cfg.adminToken, cfg.projectId);

      // Resolve HL's ids. New sales carry them; a pre-#85 sale doesn't — recover them with an
      // idempotent syncSale replay (all legs keyed by sourceId → no double-post), then re-read.
      let hlInvoiceId = data.hlInvoiceId ?? undefined;
      let hlSaleId = data.hlSaleId ?? undefined;
      if (!hlInvoiceId && !hlSaleId) {
        await syncSale(saleId, data, cfg);
        data = (await ref.get()).data() as SaleData;
        hlInvoiceId = data.hlInvoiceId ?? undefined;
        hlSaleId = data.hlSaleId ?? undefined;
      }

      // Reverse the ledger exactly once (hlVoidTxnId, once recorded, short-circuits a replay).
      if (!data.hlVoidTxnId) {
        let reversalTxnId: string | undefined;
        if (hlInvoiceId) {
          // Cancel takes no sourceId (not idempotent): skip if the invoice is already CANCELLED
          // (a resume after HL succeeded but Firestore didn't). Cancel reverses COGS too.
          const inv = await getInvoice(cfg.hlBaseUrl, token, hlInvoiceId);
          if (!inv || inv.status !== 'CANCELLED') {
            reversalTxnId = (await cancelInvoice(cfg.hlBaseUrl, token, hlInvoiceId, trimmedReason)).reversalTxnId;
          }
        } else if (hlSaleId) {
          // No invoice to cancel (rare — HL mints one at sale time): reverse the SALE transaction.
          reversalTxnId = (await reverseTransaction(cfg.hlBaseUrl, token, hlSaleId, trimmedReason)).reversalTxnId;
        } else {
          throw new Error(`void: cannot resolve an HL invoice or transaction for sale ${saleId}`);
        }
        reversalPatch.hlVoidTxnId = reversalTxnId ?? '(reversed)';
        await ref.set({ hlVoidTxnId: reversalPatch.hlVoidTxnId }, { merge: true });
        data.hlVoidTxnId = reversalPatch.hlVoidTxnId as string;
      } else {
        reversalPatch.hlVoidTxnId = data.hlVoidTxnId;
      }

      // Refund the money paid, mirroring the original split (idempotent on sourceId). Only against
      // a cancellable invoice — a bare transaction-reverse has no invoice to refund against.
      if (hlInvoiceId) {
        const legs = refundLegs(data);
        if (legs.length > 0) {
          const cashAccountId = legs.some((l) => l.account === 'Cash')
            ? await getOrCreateAccount(cfg.hlBaseUrl, token, 'Cash', 'ASSET')
            : undefined;
          const bankAccountId = legs.some((l) => l.account === 'Bank')
            ? await getOrCreateAccount(cfg.hlBaseUrl, token, 'Bank', 'ASSET')
            : undefined;
          const refundIds: string[] = [];
          for (const leg of legs) {
            const paymentAccountId = (leg.account === 'Cash' ? cashAccountId : bankAccountId) as string;
            try {
              const id = await refundPayment(cfg.hlBaseUrl, token, {
                invoiceId: hlInvoiceId,
                amount: leg.amount,
                reason: trimmedReason,
                paymentAccountId,
                sourceId: voidSourceId(saleId, leg.kind),
                actorRef: data.voidRequestedBy ?? undefined,
              });
              if (id) refundIds.push(id);
            } catch (refundErr) {
              // The cancel already returned this money (no remaining paid balance) → not a failure.
              if (refundAlreadySettledByCancel(refundErr)) continue;
              throw refundErr;
            }
          }
          if (refundIds.length > 0) {
            reversalPatch.hlRefundIds = refundIds;
            // Persist the refund trail NOW, not only via the step-4 transaction: if that transaction
            // later fails (e.g. a raced IMEI) and the void is retried, the replayed refunds 422 as
            // already-settled and are skipped, so `refundIds` would come back empty and the ids would
            // be lost forever. Writing here keeps the trail even when the void doesn't finish (#85).
            await ref.set({ hlRefundIds: refundIds }, { merge: true });
          }
        }
      }
    }

    // 4. Restore stock + flip VOIDED atomically. Carries the HL trail into the same commit.
    await restoreStockAndVoid(db, saleId, data, now, reversalPatch);

    return { status: 'VOIDED' };
  } catch (err) {
    // Settle FAILED for the trail either way. Permanent errors — an explicit VoidPermanentError, or
    // an HL 4xx that can't succeed on replay — are rethrown as VoidPermanentError so the callable
    // reports them and the edge-trigger swallows them (no redelivery loop). Only genuinely transient
    // failures (HL 5xx / network) rethrow raw, so retry:true redelivers and the idempotent replay
    // finishes the void.
    await ref.set(
      { voidStatus: 'FAILED', voidError: (err as Error)?.message ?? String(err), updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    if (err instanceof VoidPermanentError) throw err;
    if (isPermanentHlError(err)) throw new VoidPermanentError((err as Error).message);
    throw err;
  }
}
