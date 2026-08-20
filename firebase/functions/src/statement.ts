import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';
import { renderInvoice } from './billEngine.js';
import {
  blank,
  composeContact,
  formatIssueDateTime,
  type CompanyProfileData,
} from './syncWorker.js';

/**
 * The `renderStatement` engine bridge (ticket #109).
 *
 * A party's statement PDF is rendered by the Humble Bill Engine exactly like an invoice, against
 * the `billApps/aromex-statement` template. The engine is unauthenticated and may only be reached
 * from a Cloud Function, so this is where the device's assembled statement becomes a PDF.
 *
 * Division of labour: the **shared Kotlin use case** does the data work (paging every row, the
 * opening-balance call, the FIFO aging) and hands us the finished figures. This module does only
 * what must be server-side and authoritative:
 *   1. re-check the caller holds `profiles: view` (never trust the client's hidden button),
 *   2. read the seller letterhead (`companySettings/profile`) and buyer (`entities/{id}`),
 *   3. stamp the issue instant in the shop's timezone,
 *   4. wrap it all in the engine envelope and POST it.
 */

/** One aging bucket as it arrives from the client. */
export type StatementBucketInput = { label: string; amount: string };

/**
 * One statement row as it arrives from the client (notes already applied per the toggle).
 *
 * A row is a **business event**, not a ledger leg: a sale and the cash taken for it arrive as one
 * row, with `moneyIn`/`moneyOut` carrying what changed hands and `billed` what the sale was worth.
 * `debit`/`credit` are the pre-event field names, still accepted so a device on the previous build
 * keeps printing correctly.
 */
export type StatementRowInput = {
  date: string;
  description: string;
  note?: string | null;
  billed?: string;
  /** Posting type — the renderer names the billed figure from it (SALE, PURCHASE, PAYMENT…). */
  kind?: string;
  moneyIn?: string;
  moneyOut?: string;
  /** @deprecated pre-event clients; read as `billed`. */
  debit?: string;
  /** @deprecated pre-event clients; read as `moneyIn`. */
  credit?: string;
  balance: string;
};

/** The assembled statement the device sends; every money field is a plain decimal string. */
export type StatementInput = {
  periodFrom?: string | null;
  periodTo: string;
  openingBalance: string;
  totalDebits: string;
  totalCredits: string;
  closingBalance: string;
  agingBuckets: StatementBucketInput[];
  statementRows: StatementRowInput[];
};

/** The full callable request. */
export type RenderStatementRequest = {
  entityId: string;
  statement: StatementInput;
};

/** A party's contact block, read from `entities/{id}`. */
type EntityProfile = {
  name?: string | null;
  address?: string | null;
  phones?: string[] | null;
  email?: string | null;
  taxNumber?: string | null;
};

/** A permanent failure the callable maps to a readable HttpsError (mirrors `voidSale`). */
export class StatementError extends Error {
  constructor(
    readonly code: 'permission-denied' | 'not-found' | 'invalid-argument',
    message: string,
  ) {
    super(message);
    this.name = 'StatementError';
  }
}

/**
 * Assemble the `{ appId: 'aromex-statement', data }` envelope. Pure — no I/O — so the payload shape
 * (envelope, omitted-vs-empty keys, the aging array dropped when the party owes nothing) is unit
 * tested without Firestore or the engine.
 */
export function buildStatementPayload(
  input: StatementInput,
  profile: CompanyProfileData,
  entity: EntityProfile,
  issueDate: string,
): { appId: string; data: Record<string, unknown> } {
  const customer = buildCustomer(entity);
  const data: Record<string, unknown> = {
    // ── Seller letterhead — same source/shape as the invoice ──
    sellerName: blank(profile.legalName) ?? blank(profile.companyName),
    sellerAddress: blank(profile.businessAddress),
    sellerContact: composeContact(profile.contactPhone, profile.contactEmail),
    sellerPhone: blank(profile.contactPhone),
    sellerTaxLine: blank(profile.taxNumber) ? `GST/HST No: ${blank(profile.taxNumber)}` : undefined,
    logoUrl: blank(profile.logoUrl),

    // ── The party ──
    customer,
    customerTaxLine: blank(entity.taxNumber) ? `GST/HST No: ${blank(entity.taxNumber)}` : undefined,

    // ── Period + issue instant (issue is tz-formatted here; dates are date-only from the client) ──
    periodFrom: blank(input.periodFrom),
    periodTo: input.periodTo,
    issueDate,

    // ── Summary (decimal strings; the engine adds the currency symbol) ──
    openingBalance: input.openingBalance,
    totalDebits: input.totalDebits,
    totalCredits: input.totalCredits,
    closingBalance: input.closingBalance,

    // ── Aging — omit the whole array when the party owes nothing ──
    agingBuckets: input.agingBuckets.length
      ? input.agingBuckets.map((b) => ({ label: b.label, amount: b.amount }))
      : undefined,

    // ── Rows, oldest first; `note` only when present ──
    statementRows: input.statementRows.map((r) => {
      const row: Record<string, unknown> = {
        date: r.date,
        description: r.description,
        // Fall back to the pre-event names so an un-updated device still prints: its `debit` was
        // the charge (now `billed`) and its `credit` the money received (now `moneyIn`).
        billed: r.billed ?? r.debit ?? '',
        kind: r.kind ?? '',
        moneyIn: r.moneyIn ?? r.credit ?? '',
        moneyOut: r.moneyOut ?? '',
        balance: r.balance,
      };
      const note = blank(r.note);
      if (note) row.note = note;
      return row;
    }),
  };

  // Omit an absent key entirely rather than sending "" (a stray label on the PDF otherwise).
  for (const k of Object.keys(data)) if (data[k] === undefined) delete data[k];
  return { appId: 'aromex-statement', data };
}

function buildCustomer(entity: EntityProfile): Record<string, unknown> | undefined {
  const name = blank(entity.name);
  const address = blank(entity.address);
  const phone = blank(entity.phones?.[0]);
  const email = blank(entity.email);
  if (!name && !address && !phone && !email) return undefined;
  const customer: Record<string, unknown> = {};
  if (name) customer.name = name;
  if (address) customer.address = address;
  if (phone) customer.phone = phone;
  if (email) customer.email = email;
  return customer;
}

/**
 * Re-check `profiles: view` from `users/{uid}` — the real gate, never the client's UI. Admins pass;
 * otherwise the per-feature scope must be `view` or `manage`. Mirrors `firestore.rules`'
 * `hasPermission('profiles', 'view')`.
 */
async function assertCanViewProfiles(db: Firestore, uid: string): Promise<void> {
  const snap = await db.collection('users').doc(uid).get();
  const user = snap.data() as
    | { isActive?: boolean; role?: string; permissions?: Record<string, string> }
    | undefined;
  if (!user || user.isActive !== true) {
    throw new StatementError('permission-denied', 'Your account is not active.');
  }
  const level = user.permissions?.profiles;
  if (user.role !== 'admin' && level !== 'view' && level !== 'manage') {
    throw new StatementError('permission-denied', 'You do not have permission to view parties.');
  }
}

/**
 * The idempotent core behind the callable: permission gate → read letterhead → render → URL.
 * Throws [StatementError] for permission/validation failures (the callable maps them to
 * HttpsError); anything from the engine bubbles up as a generic failure.
 */
export async function renderStatementCore(
  db: Firestore,
  cfg: SyncConfig,
  uid: string,
  request: RenderStatementRequest,
  now: Date,
): Promise<{ url: string }> {
  const entityId = (request.entityId ?? '').trim();
  if (!entityId) throw new StatementError('invalid-argument', 'entityId is required.');
  if (!request.statement) throw new StatementError('invalid-argument', 'statement is required.');

  await assertCanViewProfiles(db, uid);

  const entitySnap = await db.collection('entities').doc(entityId).get();
  if (!entitySnap.exists) throw new StatementError('not-found', 'That party no longer exists.');
  const entity = entitySnap.data() as EntityProfile;

  const profile = ((await db.collection('companySettings').doc('profile').get()).data() ??
    {}) as CompanyProfileData;

  const issueDate = formatIssueDateTime(now, profile.timezone);
  const payload = buildStatementPayload(request.statement, profile, entity, issueDate);
  const url = await renderInvoice(cfg.billEngineUrl, payload);
  return { url };
}
