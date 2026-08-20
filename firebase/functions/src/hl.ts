/**
 * Thin HTTP client the Cloud Functions use to reach the gateway (for a token) and
 * Humble Ledger (for the actual writes). Mirrors the app's flow: get a short-lived
 * HL token from our gateway, then call HL directly. HL credentials never live here.
 */

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

/**
 * An HL HTTP error that carries the response [status], so callers can react to specific
 * codes (e.g. a 404 on a customer-scoped post → the cached `hlCustomerId` is stale and the
 * party should be re-resolved; see `syncWorker`). Extends Error, so existing `err.message`
 * capture keeps working unchanged.
 */
export class HlHttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** HL's response body (truncated) — carries the coded reason (e.g. EXCESS_REFUND) a bare
     *  status hides. Included in [message] so it lands in Firestore's voidError/hlSyncError. */
    readonly body?: string,
  ) {
    super(message);
    this.name = 'HlHttpError';
  }
}

/** Read an error response body best-effort (never throws), truncated so it can't bloat a log/doc. */
async function readErrorBody(res: Response): Promise<string> {
  try {
    return (await res.text()).slice(0, 500);
  } catch {
    return '';
  }
}

/** Broker a short-lived HL token from the gateway's internal (admin-authed) route. */
export async function getHlToken(
  gatewayBaseUrl: string,
  adminToken: string,
  projectId: string,
): Promise<string> {
  const res = await fetch(`${trimTrailingSlash(gatewayBaseUrl)}/internal/hl-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Admin-Token': adminToken },
    body: JSON.stringify({ projectId }),
  });
  if (!res.ok) throw new Error(`gateway /internal/hl-token → HTTP ${res.status}`);
  const json = (await res.json()) as { hlToken?: string };
  if (!json.hlToken) throw new Error('gateway /internal/hl-token → no hlToken in response');
  return json.hlToken;
}

export type CustomerInput = {
  name: string;
  email?: string;
  phone?: string;
  externalId: string;
};

export type CreateCustomerResult = {
  id: string;
  accountId: string;
  /** True when HL returned an already-existing customer (idempotent on externalId). */
  idempotent: boolean;
};

async function hlPost<T>(url: string, token: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const errBody = await readErrorBody(res);
    throw new HlHttpError(res.status, `HL POST ${url} → HTTP ${res.status} :: ${errBody}`, errBody);
  }
  return (await res.json()) as T;
}

async function hlGet<T>(url: string, token: string): Promise<T> {
  const res = await fetch(url, {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const errBody = await readErrorBody(res);
    throw new HlHttpError(res.status, `HL GET ${url} → HTTP ${res.status} :: ${errBody}`, errBody);
  }
  return (await res.json()) as T;
}

/** Idempotent on externalId — a retry returns the existing customer, never a duplicate. */
export async function createCustomer(
  hlBaseUrl: string,
  token: string,
  input: CustomerInput,
): Promise<CreateCustomerResult> {
  const json = await hlPost<{
    success: boolean;
    data?: { id: string; accountId: string };
    idempotent?: boolean;
    error?: string;
  }>(`${trimTrailingSlash(hlBaseUrl)}/api/v1/customers`, token, {
    name: input.name,
    email: input.email,
    phone: input.phone,
    externalId: input.externalId,
  });
  if (!json.success || !json.data) {
    throw new Error(`HL create customer failed: ${json.error ?? 'unknown'}`);
  }
  return { id: json.data.id, accountId: json.data.accountId, idempotent: Boolean(json.idempotent) };
}

/** Push profile edits (name/email/phone) to an existing HL customer. */
export async function updateCustomer(
  hlBaseUrl: string,
  token: string,
  hlCustomerId: string,
  fields: { name?: string; email?: string; phone?: string },
): Promise<void> {
  const res = await fetch(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/customers/${encodeURIComponent(hlCustomerId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(fields),
    },
  );
  if (!res.ok) throw new Error(`HL PATCH customer → HTTP ${res.status}`);
}

export type OpeningBalanceInput = {
  /** Decimal string, positive. Converted to a number only here, at the HL boundary. */
  amount: string;
  /** HL vocabulary: RECEIVABLE (they owe us) | PAYABLE (we owe them). */
  direction: 'RECEIVABLE' | 'PAYABLE';
  sourceId: string;
  actorRef?: string;
};

/** Post the opening-balance journal (idempotent on appId+sourceId). */
export async function postOpeningBalance(
  hlBaseUrl: string,
  token: string,
  hlCustomerId: string,
  input: OpeningBalanceInput,
): Promise<void> {
  const json = await hlPost<{ success: boolean; error?: string }>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/customers/${encodeURIComponent(hlCustomerId)}/opening-balance`,
    token,
    {
      amount: Number(input.amount),
      direction: input.direction,
      appId: 'aromex',
      sourceId: input.sourceId,
      actorRef: input.actorRef,
    },
  );
  if (!json.success) throw new Error(`HL opening-balance failed: ${json.error ?? 'unknown'}`);
}

// ---------- Inventory purchase posting (ticket #58) ----------

type HlAccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'INCOME' | 'EXPENSE';

type HlAccount = { id: string; name: string; type: HlAccountType };

/**
 * Find-or-create a company account by (name, type), returning its HL id. HL accounts are
 * NOT idempotent by name — POST returns 409 on a duplicate — so we list first, create if
 * absent, then re-list to resolve the id (authoritative, independent of the POST body
 * shape). A 409 from a concurrent create is treated as "already exists" and re-resolved.
 *
 * Used to book inventory purchases against the company's `Inventory` (ASSET) account.
 */
export async function getOrCreateAccount(
  hlBaseUrl: string,
  token: string,
  name: string,
  type: HlAccountType,
  isTaxAccount = false,
): Promise<string> {
  const base = trimTrailingSlash(hlBaseUrl);
  // Filter by type only and match the exact name in code — independent of HL's `search` semantics.
  const listUrl = `${base}/api/v1/accounts?type=${type}&limit=200`;

  const find = async (): Promise<string | undefined> => {
    const json = await hlGet<{ success: boolean; data?: HlAccount[] }>(listUrl, token);
    return json.data?.find((a) => a.name === name && a.type === type)?.id;
  };

  const existing = await find();
  if (existing) return existing;

  // Safety-net creation (the clean path is per-company provisioning). Tax accounts are
  // flagged isTaxAccount:true to match the seeded GST Payable (ticket #61).
  const res = await fetch(`${base}/api/v1/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(isTaxAccount ? { name, type, isTaxAccount: true } : { name, type }),
  });
  if (!res.ok && res.status !== 409) {
    throw new Error(`HL create account "${name}" → HTTP ${res.status}`);
  }

  const resolved = await find();
  if (!resolved) throw new Error(`HL account "${name}" not found after create`);
  return resolved;
}

/** HL's shape for a posted transaction — `{ data: { transaction: { id } } }`. */
export type PostedTransaction = { data?: { transaction?: { id?: string } } };

export type CustomerPurchaseInput = {
  /** Accounting date `yyyy-MM-dd`; omit for today. Set when a batch is backdated (ticket #107). */
  date?: string;
  customerId: string;
  /** Decimal string; converted to a number only here, at the HL boundary. */
  amount: string;
  /** The company's Inventory (ASSET) account — books the purchase as an asset, not an expense. */
  expenseAccountId: string;
  description: string;
  sourceId: string;
  actorRef?: string;
};

/** Post an inventory purchase (idempotent on appId+sourceId). Books against expenseAccountId. */
export async function createCustomerPurchase(
  hlBaseUrl: string,
  token: string,
  input: CustomerPurchaseInput,
): Promise<string | undefined> {
  const res = await hlPost<PostedTransaction>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/customer-purchases`,
    token,
    {
      customerId: input.customerId,
      amount: Number(input.amount),
      expenseAccountId: input.expenseAccountId,
      description: input.description,
      ...(input.date ? { date: input.date } : {}),
      appId: 'aromex',
      sourceId: input.sourceId,
      actorRef: input.actorRef,
    },
  );
  // The posted transaction's id, so the purchase can be reversed later by id. An idempotent
  // replay returns the ORIGINAL transaction, which is what makes recovering a missing id safe.
  return res.data?.transaction?.id;
}

export type CustomerPayoutInput = {
  customerId: string;
  /** Decimal string; converted to a number only here, at the HL boundary. */
  amount: string;
  method: 'CASH' | 'BANK';
  /** Shown on the party's statement. Omit to accept HL's default wording. */
  description?: string;
  /** Accounting date `yyyy-MM-dd`; omit for today. */
  date?: string;
  sourceId: string;
  actorRef?: string;
};

/**
 * Pay a party (idempotent on appId+sourceId). CASH or BANK — HL has no card payout.
 * Returns the posted transaction's id (see [createPayment] for why).
 */
export async function createCustomerPayout(
  hlBaseUrl: string,
  token: string,
  input: CustomerPayoutInput,
): Promise<string | undefined> {
  const res = await hlPost<PostedTransaction>(`${trimTrailingSlash(hlBaseUrl)}/api/v1/customer-payouts`, token, {
    customerId: input.customerId,
    amount: Number(input.amount),
    method: input.method,
    ...(input.description ? { description: input.description } : {}),
    ...(input.date ? { date: input.date } : {}),
    appId: 'aromex',
    sourceId: input.sourceId,
    actorRef: input.actorRef,
  });
  return res.data?.transaction?.id;
}

// ---------- Sale posting (ticket #61) ----------

export type SaleTaxLineInput = {
  /** Decimal string. */
  amount: string;
  accountId: string;
};

export type CreateSaleInput = {
  customerId: string;
  /** Pre-tax (tax-exclusive) revenue, decimal string. */
  amount: string;
  description: string;
  revenueAccountId: string;
  taxLines: SaleTaxLineInput[];
  /** Cost-of-goods trio — supplied together, or all omitted when the sale has no inventory lines. */
  cogsAmount?: string;
  inventoryAccountId?: string;
  cogsAccountId?: string;
  sourceId: string;
  actorRef?: string;
  /** Accounting date `yyyy-MM-dd`; omit for today. Set when a sale is backdated (ticket #107). */
  date?: string;
};

/**
 * HL's `/api/v1/sales` response. HL mints the invoice at sale time and returns it here —
 * `data.invoice.invoiceNumber` (e.g. `"INV-000042"`) is the number the bill-engine PDF must
 * carry (ticket #76). Idempotent on appId+sourceId, so a replay returns the SAME invoice.
 */
export type CreateSaleResult = {
  success?: boolean;
  data?: {
    invoice?: { id?: string; invoiceNumber?: string };
    /** The SALE posting HL created. Its `id` is what a void reverses when no invoice can be
     *  cancelled (ticket #85). Captured here so a void need not store it separately: replaying
     *  `createSale` with the same sourceId is idempotent and returns this same id. */
    transaction?: { id?: string };
  };
};

/**
 * Post a sale to HL (idempotent on appId+sourceId): recognises pre-tax revenue + the
 * customer's AR, adds 0–2 tax legs, and — when the trio is present — relieves inventory
 * and books COGS. Decimal strings are converted to numbers only here, at the HL boundary.
 *
 * Returns HL's parsed response so the caller can read both the invoice number HL created
 * (`data.invoice.invoiceNumber`) — the number the invoice PDF must use (ticket #76) — and the
 * invoice **id**, which the payments posted for this sale must reference so HL can mark the
 * invoice settled (see [CreatePaymentInput.invoiceId]).
 */
export async function createSale(
  hlBaseUrl: string,
  token: string,
  input: CreateSaleInput,
): Promise<CreateSaleResult> {
  const body: Record<string, unknown> = {
    customerId: input.customerId,
    amount: Number(input.amount),
    description: input.description,
    revenueAccountId: input.revenueAccountId,
    // Omitted rather than sent empty: HL defaults an absent date to today.
    ...(input.date ? { date: input.date } : {}),
    appId: 'aromex',
    sourceId: input.sourceId,
    actorRef: input.actorRef,
  };
  if (input.taxLines.length > 0) {
    body.taxLines = input.taxLines.map((t) => ({
      amount: Number(t.amount),
      accountId: t.accountId,
    }));
  }
  // COGS is all-or-none (HL 422 COGS_FIELDS_INCOMPLETE otherwise); omit entirely when 0.
  if (input.cogsAmount && input.inventoryAccountId && input.cogsAccountId) {
    body.cogsAmount = Number(input.cogsAmount);
    body.inventoryAccountId = input.inventoryAccountId;
    body.cogsAccountId = input.cogsAccountId;
  }
  return hlPost<CreateSaleResult>(`${trimTrailingSlash(hlBaseUrl)}/api/v1/sales`, token, body);
}

export type CreatePaymentInput = {
  customerId: string;
  /** Decimal string. */
  amount: string;
  /** The HL asset account the money lands in (Cash / Bank). Card is routed to Bank (ticket #61). */
  paymentAccountId: string;
  /**
   * The HL invoice this money settles. **Always pass it for a sale's own payments.**
   *
   * Without it HL still moves the customer's AR balance, but the invoice's `amountPaid` never
   * leaves 0 and its status never leaves PENDING — so a walk-in who paid cash in full leaves a
   * permanently "unpaid" invoice, and `GET /receivables` reports money that was collected at the
   * counter. Omit it only for a payment that genuinely isn't against one invoice (e.g. a customer
   * paying down an overall balance).
   */
  invoiceId?: string;
  /** Shown on the party's statement. Omit to accept HL's default wording. */
  description?: string;
  /** Accounting date `yyyy-MM-dd`; omit for today. */
  date?: string;
  sourceId: string;
  actorRef?: string;
};

/** What HL echoes back for the money endpoints — the posted transaction's id. */
/**
 * Record a customer payment (idempotent on appId+sourceId). HL's `method` enum is only
 * CASH|BANK, so the ledger account is driven explicitly via `paymentAccountId`. When
 * [CreatePaymentInput.invoiceId] is supplied HL also applies the money to that invoice,
 * recomputing its `amountPaid` and status (PENDING → PARTIAL → PAID).
 *
 * Returns the posted transaction's id so a money entry can be traced to its ledger row later —
 * which is what lets a party's statement offer Reverse on the rows the app actually owns.
 */
export async function createPayment(
  hlBaseUrl: string,
  token: string,
  input: CreatePaymentInput,
): Promise<string | undefined> {
  const res = await hlPost<PostedTransaction>(`${trimTrailingSlash(hlBaseUrl)}/api/v1/payments`, token, {
    customerId: input.customerId,
    amount: Number(input.amount),
    paymentAccountId: input.paymentAccountId,
    // Omitted rather than sent as undefined/null: HL treats an absent key as "unapplied".
    ...(input.invoiceId ? { invoiceId: input.invoiceId } : {}),
    ...(input.description ? { description: input.description } : {}),
    ...(input.date ? { date: input.date } : {}),
    appId: 'aromex',
    sourceId: input.sourceId,
    actorRef: input.actorRef,
  });
  return res.data?.transaction?.id;
}

// ---------- raw journal entries (ticket #90) ----------

export type JournalEntryLine = {
  accountId: string;
  type: 'DEBIT' | 'CREDIT';
  /** Decimal string; converted to a number only here, at the HL boundary. */
  amount: string;
};

export type CreateJournalEntryInput = {
  entries: JournalEntryLine[];
  description: string;
  /** Accounting date, `yyyy-MM-dd`. May be backdated — recording yesterday's payment is ordinary. */
  date: string;
  sourceId: string;
  actorRef?: string;
};

export type CreateJournalEntryResult = {
  success?: boolean;
  data?: { id?: string };
};

/**
 * Post a raw double-entry transaction (idempotent on appId+sourceId), used for the money movements
 * that no high-level endpoint covers: party → party (one party settling another's balance, which is
 * an assignment of receivable between two AR sub-accounts) and cash ↔ bank.
 *
 * Prefer `createPayment` / `createCustomerPayout` where they fit — they carry proper posting types
 * and HL does more with them. `JOURNAL` is HL's own label for a manual entry.
 *
 * HL enforces that debits equal credits within a paisa and rejects the whole transaction otherwise,
 * so an unbalanced entry can never reach the books.
 */
export async function createJournalEntry(
  hlBaseUrl: string,
  token: string,
  input: CreateJournalEntryInput,
): Promise<CreateJournalEntryResult> {
  return hlPost<CreateJournalEntryResult>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/transactions`,
    token,
    {
      appId: 'aromex',
      sourceId: input.sourceId,
      postingType: 'JOURNAL',
      description: input.description,
      date: input.date,
      entries: input.entries.map((e) => ({
        accountId: e.accountId,
        type: e.type,
        amount: Number(e.amount),
      })),
      actorRef: input.actorRef,
    },
  );
}


// ---------- Void a sale (ticket #85) ----------

/** The subset of an HL invoice a void needs to read: its id and current status. */
export type HlInvoice = { id: string; status: string };

/**
 * Fetch one HL invoice by id. Used before [cancelInvoice] so a void that resumes after HL was
 * already cancelled (Firestore didn't finish) can *skip* re-cancelling: `cancel` takes no
 * appId/sourceId, so unlike every other HL call it is NOT idempotent — cancelling an already-
 * CANCELLED invoice would error. This read is what makes the cancel leg replay-safe (ticket #85).
 * Returns undefined on a 404 (the invoice id no longer resolves).
 */
export async function getInvoice(
  hlBaseUrl: string,
  token: string,
  invoiceId: string,
): Promise<HlInvoice | undefined> {
  try {
    const json = await hlGet<{ success?: boolean; data?: HlInvoice }>(
      `${trimTrailingSlash(hlBaseUrl)}/api/v1/invoices/${encodeURIComponent(invoiceId)}`,
      token,
    );
    return json.data;
  } catch (err) {
    if (err instanceof HlHttpError && err.status === 404) return undefined;
    throw err;
  }
}

/** The REVERSAL transaction id a cancel/reverse produced (best-effort; the trail, not a key). */
export type ReverseResult = { reversalTxnId?: string };

/**
 * Cancel an HL invoice (ticket #85): posts the full mirror of the sale — revenue, AR, tax **and
 * the COGS/Inventory pair** — in one call, sets the invoice `CANCELLED`, and records a
 * CANCELLATION credit-note. (Measured live 2026-07-31: cancel reverses COGS too — do NOT reverse
 * it yourself.) Takes only `{ reason }`; see [getInvoice] for why the caller guards on status
 * rather than relying on idempotency.
 */
export async function cancelInvoice(
  hlBaseUrl: string,
  token: string,
  invoiceId: string,
  reason: string,
): Promise<ReverseResult> {
  const json = await hlPost<{ success?: boolean; data?: { id?: string; transaction?: { id?: string } } }>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/invoices/${encodeURIComponent(invoiceId)}/cancel`,
    token,
    { reason },
  );
  return { reversalTxnId: json.data?.transaction?.id };
}

/**
 * Reverse a single HL transaction by id (ticket #85) — the fallback when a synced sale has no
 * cancellable invoice (only its SALE posting). Posts that transaction's mirror. Takes only
 * `{ reason }` (minLength 1). Not the common path: HL mints an invoice at sale time, so a SYNCED
 * sale almost always cancels via [cancelInvoice] instead.
 */
export async function reverseTransaction(
  hlBaseUrl: string,
  token: string,
  transactionId: string,
  reason: string,
): Promise<ReverseResult> {
  const json = await hlPost<{ success?: boolean; data?: { id?: string } }>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/transactions/${encodeURIComponent(transactionId)}/reverse`,
    token,
    { reason },
  );
  return { reversalTxnId: json.data?.id };
}

export type RefundInput = {
  invoiceId: string;
  /** Decimal string; converted to a number only here, at the HL boundary. */
  amount: string;
  reason: string;
  /** The HL asset account the money leaves (Cash / Bank) — mirrors the original payment split. */
  paymentAccountId: string;
  sourceId: string;
  actorRef?: string;
};

/**
 * Refund money against a cancelled invoice (ticket #85): journals `DR AR / CR Cash|Bank`, returning
 * the customer's money so they end at a zero balance rather than holding a credit. Idempotent on
 * (appId, sourceId), so a replay never double-refunds. HL caps the refundable amount at
 * `amountPaid − totalRefunded`. Returns the refund id (recorded on the sale for the trail).
 */
export async function refundPayment(
  hlBaseUrl: string,
  token: string,
  input: RefundInput,
): Promise<string | undefined> {
  const json = await hlPost<{ success?: boolean; data?: { id?: string } }>(
    `${trimTrailingSlash(hlBaseUrl)}/api/v1/refunds`,
    token,
    {
      invoiceId: input.invoiceId,
      amount: Number(input.amount),
      reason: input.reason,
      paymentAccountId: input.paymentAccountId,
      appId: 'aromex',
      sourceId: input.sourceId,
      actorRef: input.actorRef,
    },
  );
  return json.data?.id;
}
