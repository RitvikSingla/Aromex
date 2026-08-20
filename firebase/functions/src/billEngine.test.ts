import { afterEach, describe, expect, it, vi } from 'vitest';
import { BillEngineError, renderInvoice } from './billEngine.js';
import { buildInvoicePayload, formatIssueDate, type SaleData } from './syncWorker.js';

// ---------- renderInvoice (network mocked) ----------

function mockFetchOnce(status: number, body: unknown) {
  const fetchMock = vi.fn(async () => ({ status, json: async () => body }) as unknown as Response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('renderInvoice', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('returns the url on HTTP 200 with a url', async () => {
    const fetchMock = mockFetchOnce(200, { message: 'ok', url: 'https://s3/aromex-INV-1.pdf' });
    const url = await renderInvoice('https://engine', { appId: 'aromex' });
    expect(url).toBe('https://s3/aromex-INV-1.pdf');
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('treats HTTP 200 without a url as a failure', async () => {
    mockFetchOnce(200, { message: 'ok but no url' });
    await expect(renderInvoice('https://engine', {})).rejects.toBeInstanceOf(BillEngineError);
  });

  it('throws with the engine details on a non-200', async () => {
    mockFetchOnce(500, { error: 'boom', details: { reason: 'template missing' } });
    const err = await renderInvoice('https://engine', {}).catch((e) => e);
    expect(err).toBeInstanceOf(BillEngineError);
    expect((err as BillEngineError).details).toEqual({ reason: 'template missing' });
  });

  it('wraps a network/timeout error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new Error('aborted');
      }),
    );
    await expect(renderInvoice('https://engine', {})).rejects.toBeInstanceOf(BillEngineError);
  });
});

// ---------- buildInvoicePayload (pure — the TS mirror of the Kotlin spec) ----------

const profile = {
  companyName: 'Pukhraj Mobiles',
  legalName: 'Pukhraj Mobiles Ltd.',
  logoUrl: 'https://cdn/logo.png',
  taxNumber: '123456789 RT0001',
  businessAddress: '123 Main St, Surrey, BC',
  contactEmail: 'a@b.com',
  contactPhone: '+1 (604) 555-0100',
};

const baseSale: SaleData = {
  customerEntityId: 'e1',
  taxableAmount: '900.00',
  lines: [
    { kind: 'INVENTORY', imei: '353340195540565', label: 'Apple iPhone 14 · 256GB · Purple', netPrice: '900.00' },
  ],
  subtotal: '900.00',
  saleDiscount: '0.00',
  grandTotal: '945.00',
  amountPaid: '945.00',
  balanceRemaining: '0.00',
  taxLines: [{ name: 'GST', rate: '0.05', amount: '45.00' }],
};

/** JSON round-trip so undefined keys are dropped, exactly as they are on the wire. */
function onWire(p: Record<string, unknown>): { appId: string; data: Record<string, unknown> } {
  return JSON.parse(JSON.stringify(p));
}

describe('buildInvoicePayload', () => {
  it('sends appId=aromex and glyph-only currency USD', () => {
    const { appId, data } = onWire(buildInvoicePayload(baseSale, profile, 'INV-000042', '29 Jul 2026'));
    expect(appId).toBe('aromex');
    expect(data.currency).toBe('USD'); // glyph-only, NOT a currency claim
    expect(data.invoiceNumber).toBe('INV-000042');
    expect(data.issueDate).toBe('29 Jul 2026');
  });

  it('puts the IMEI in hsn with qty 1 and a numeric rate', () => {
    const { data } = onWire(buildInvoicePayload(baseSale, profile, 'INV-1', '29 Jul 2026'));
    const items = data.lineItems as Array<Record<string, unknown>>;
    expect(items[0]).toEqual({
      name: 'Apple iPhone 14 · 256GB · Purple',
      hsn: '353340195540565',
      qty: 1,
      rate: 900,
    });
  });

  it('omits hsn on a custom line', () => {
    const sale = { ...baseSale, lines: [{ kind: 'CUSTOM', name: 'Case', netPrice: '15.00' } as const] };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    const items = data.lineItems as Array<Record<string, unknown>>;
    expect(items[0]).toEqual({ name: 'Case', qty: 1, rate: 15 });
    expect('hsn' in items[0]).toBe(false);
  });

  it('formats CAD totals: grouped thousands, 2 dp, negatives for paid', () => {
    const sale: SaleData = {
      ...baseSale,
      subtotal: '2530',
      grandTotal: '2800',
      amountPaid: '1800',
      balanceRemaining: '1000',
    };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.subtotalDisp).toBe('$2,530.00');
    expect(data.totalDisp).toBe('$2,800.00');
    expect(data.amountPaidDisp).toBe('-$1,800.00');
    expect(data.balanceDisp).toBe('$1,000.00');
    expect(data.subtotal).toBe(2530); // raw is a JSON number
  });

  it('builds GST+PST tax rows with trimmed percent labels', () => {
    const sale: SaleData = {
      ...baseSale,
      taxLines: [
        { name: 'GST', rate: '0.05', amount: '45.00' },
        { name: 'PST', rate: '0.07', amount: '63.00' },
      ],
    };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.tax1Label).toBe('GST 5%');
    expect(data.tax1Disp).toBe('$45.00');
    expect(data.tax2Label).toBe('PST 7%');
    expect(data.tax2Disp).toBe('$63.00');
  });

  it('builds a single HST row with a fractional percent', () => {
    const sale: SaleData = { ...baseSale, taxLines: [{ name: 'HST', rate: '0.075', amount: '67.50' }] };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.tax1Label).toBe('HST 7.5%');
    expect('tax2Label' in data).toBe(false);
  });

  it('builds a whole-number HST percent with no trailing ".0" (13%, not 13.0%)', () => {
    const sale: SaleData = { ...baseSale, taxLines: [{ name: 'HST', rate: '0.13', amount: '117.00' }] };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.tax1Label).toBe('HST 13%');
  });

  it('rounds CAD *Disp half-up to 2 dp (matches Kotlin Money, never truncates)', () => {
    // A >2-dp amount must round half-up like the shared Kotlin spec, not truncate.
    const sale: SaleData = { ...baseSale, subtotal: '2530.005', grandTotal: '2530.004' };
    const { data } = onWire(buildInvoicePayload(sale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.subtotalDisp).toBe('$2,530.01'); // .005 → up
    expect(data.totalDisp).toBe('$2,530.00'); // .004 → down
  });

  it('omits the discount line when there is no sale discount', () => {
    const { data } = onWire(buildInvoicePayload(baseSale, profile, 'INV-1', '29 Jul 2026'));
    expect('discountLabel' in data).toBe(false);
    expect('discountDisp' in data).toBe(false);
  });

  it('renders the seller letterhead, preferring legalName', () => {
    const { data } = onWire(buildInvoicePayload(baseSale, profile, 'INV-1', '29 Jul 2026'));
    expect(data.sellerName).toBe('Pukhraj Mobiles Ltd.');
    expect(data.sellerContact).toBe('+1 (604) 555-0100  ·  a@b.com');
    expect(data.sellerTaxLine).toBe('GST/HST No: 123456789 RT0001');
    expect(data.logoUrl).toBe('https://cdn/logo.png');
  });

  it('omits the seller tax line when there is no tax number, without a dangling label', () => {
    const { data } = onWire(buildInvoicePayload(baseSale, { ...profile, taxNumber: null }, 'INV-1', 'd'));
    expect('sellerTaxLine' in data).toBe(false);
  });

  it('omits absent optionals entirely (never blank strings)', () => {
    const bare = { companyName: 'Shop' };
    const { data } = onWire(buildInvoicePayload(baseSale, bare, 'INV-1', 'd'));
    expect(data.sellerName).toBe('Shop');
    for (const k of ['sellerAddress', 'sellerContact', 'sellerPhone', 'logoUrl', 'sellerTaxLine']) {
      expect(k in data).toBe(false);
    }
  });

  it('includes a named buyer block and customer tax line', () => {
    const sale = { ...baseSale, buyerTaxNumber: '987654321 RT0002' };
    const { data } = onWire(
      buildInvoicePayload(sale, profile, 'INV-1', 'd', { name: 'Rajesh', phone: '+1 555' }),
    );
    expect(data.customer).toEqual({ name: 'Rajesh', phone: '+1 555' });
    expect(data.customerTaxLine).toBe('GST/HST No: 987654321 RT0002');
  });

  it('omits the customer tax line when the buyer has no tax number, without a dangling label', () => {
    const { data } = onWire(
      buildInvoicePayload(baseSale, profile, 'INV-1', 'd', { name: 'Rajesh' }),
    );
    expect('customerTaxLine' in data).toBe(false);
  });

  it('omits the customer block for an anonymous walk-in', () => {
    const { data } = onWire(buildInvoicePayload({ ...baseSale, isWalkIn: true }, profile, 'INV-1', 'd'));
    expect('customer' in data).toBe(false);
    expect('customerTaxLine' in data).toBe(false);
  });
});

describe('formatIssueDate', () => {
  it('formats as "D Mon YYYY"', () => {
    expect(formatIssueDate(new Date(Date.UTC(2026, 6, 29)))).toBe('29 Jul 2026');
    expect(formatIssueDate(new Date(Date.UTC(2026, 0, 5)))).toBe('5 Jan 2026');
  });
});
