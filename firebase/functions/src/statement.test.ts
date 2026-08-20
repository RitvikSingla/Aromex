import { describe, expect, it, vi } from 'vitest';
import type { Firestore } from 'firebase-admin/firestore';
import type { SyncConfig } from './config.js';

// The engine is the only network touch — replace it, keep the real error classes.
vi.mock('./billEngine.js', async (importActual) => {
  const actual = await importActual<typeof import('./billEngine.js')>();
  return { ...actual, renderInvoice: vi.fn(async () => 'https://pdf.example/statement.pdf') };
});

import { renderInvoice } from './billEngine.js';
import {
  buildStatementPayload,
  renderStatementCore,
  StatementError,
  type StatementInput,
} from './statement.js';

const cfg: SyncConfig = {
  gatewayBaseUrl: 'x',
  hlBaseUrl: 'y',
  adminToken: 'z',
  projectId: 'p',
  billEngineUrl: 'https://engine.example/render',
} as SyncConfig;

const PROFILE = {
  legalName: 'Aromex Distribution Ltd',
  companyName: 'Aromex',
  businessAddress: '1 Main St',
  contactPhone: '555-1000',
  contactEmail: 'shop@aromex.test',
  taxNumber: 'GST123',
  logoUrl: 'https://logo',
  timezone: 'America/Vancouver',
};

const ENTITY = {
  name: 'Rajesh Kumar',
  address: '9 Market Rd',
  phones: ['555-2000'],
  email: 'rajesh@test',
  taxNumber: 'GST999',
};

function sampleStatement(overrides: Partial<StatementInput> = {}): StatementInput {
  return {
    periodFrom: '1 Jan 2026',
    periodTo: '31 Mar 2026',
    openingBalance: '1200.00',
    totalDebits: '400.00',
    totalCredits: '700.00',
    closingBalance: '900.00',
    agingBuckets: [
      { label: '0–30 days', amount: '0.00' },
      { label: '31–60 days', amount: '400.00' },
      { label: '61–90 days', amount: '500.00' },
      { label: '90+ days', amount: '0.00' },
    ],
    statementRows: [
      { date: '12 Feb 2026', description: 'Sale', debit: '400.00', credit: '', balance: '1600.00' },
      { date: '20 Feb 2026', description: 'Payment', note: 'paid by cheque', debit: '', credit: '700.00', balance: '900.00' },
    ],
    ...overrides,
  };
}

/** Minimal Firestore fake: doc get for the three collections the core reads. */
function fakeDb(seed: Record<string, Record<string, unknown> | undefined>): Firestore {
  return {
    collection: (name: string) => ({
      doc: (id: string) => ({
        get: async () => {
          const data = seed[`${name}/${id}`];
          return { exists: data !== undefined, data: () => data };
        },
      }),
    }),
  } as unknown as Firestore;
}

describe('buildStatementPayload', () => {
  it('wraps the aromex-statement envelope with seller + buyer', () => {
    const payload = buildStatementPayload(sampleStatement(), PROFILE, ENTITY, '1 Aug 2026, 2:15 PM PDT');
    expect(payload.appId).toBe('aromex-statement');
    const d = payload.data as Record<string, unknown>;
    expect(d.sellerName).toBe('Aromex Distribution Ltd');
    expect(d.sellerTaxLine).toBe('GST/HST No: GST123');
    expect(d.customer).toEqual({
      name: 'Rajesh Kumar',
      address: '9 Market Rd',
      phone: '555-2000',
      email: 'rajesh@test',
    });
    expect(d.customerTaxLine).toBe('GST/HST No: GST999');
    expect(d.issueDate).toBe('1 Aug 2026, 2:15 PM PDT');
    expect(d.closingBalance).toBe('900.00');
  });

  it('passes a row note through only when present, never an empty key', () => {
    const d = buildStatementPayload(sampleStatement(), PROFILE, ENTITY, 'now').data as Record<string, unknown>;
    const rows = d.statementRows as Array<Record<string, unknown>>;
    expect('note' in rows[0]).toBe(false); // no note on the sale row
    expect(rows[1].note).toBe('paid by cheque');
  });

  it('sends money and billed value as separate fields, so the money columns are money only', () => {
    const d = buildStatementPayload(
      sampleStatement({
        statementRows: [
          // A sale paid at the counter: one row, the cash in the money column, the sale's value
          // beside it — not a charge line plus a payment line that cancel out.
          { date: '31 Jul 2026', description: 'Sale (Aromex)', billed: '918.00', moneyIn: '918.00', moneyOut: '', balance: '0.00' },
          { date: '31 Jul 2026', description: 'Payout', billed: '', moneyIn: '', moneyOut: '250.00', balance: '-250.00' },
        ],
      }),
      PROFILE,
      ENTITY,
      'now',
    ).data as Record<string, unknown>;
    const rows = d.statementRows as Array<Record<string, unknown>>;

    expect(rows[0].moneyIn).toBe('918.00');
    expect(rows[0].billed).toBe('918.00');
    expect(rows[0].moneyOut).toBe('');
    expect(rows[1].moneyOut).toBe('250.00');
    expect(rows[1].billed).toBe('');
  });

  it('still prints correctly for a device on the pre-event build', () => {
    // Its `debit` was the charge and its `credit` the money received; dropping them would print a
    // statement with every money column blank rather than an obvious failure.
    const d = buildStatementPayload(sampleStatement(), PROFILE, ENTITY, 'now').data as Record<string, unknown>;
    const rows = d.statementRows as Array<Record<string, unknown>>;

    expect(rows[0].billed).toBe('400.00'); // was `debit`
    expect(rows[0].moneyIn).toBe('');
    expect(rows[1].moneyIn).toBe('700.00'); // was `credit`
    expect(rows[1].billed).toBe('');
  });

  it('omits the aging array entirely when the party owes nothing', () => {
    const d = buildStatementPayload(sampleStatement({ agingBuckets: [] }), PROFILE, ENTITY, 'now').data as Record<
      string,
      unknown
    >;
    expect('agingBuckets' in d).toBe(false);
  });

  it('omits absent letterhead keys rather than sending empty strings', () => {
    const d = buildStatementPayload(
      sampleStatement(),
      { ...PROFILE, taxNumber: null, logoUrl: null },
      { ...ENTITY, taxNumber: null },
      'now',
    ).data as Record<string, unknown>;
    expect('sellerTaxLine' in d).toBe(false);
    expect('customerTaxLine' in d).toBe(false);
    expect('logoUrl' in d).toBe(false);
  });

  it('omits periodFrom for an all-time statement', () => {
    const d = buildStatementPayload(sampleStatement({ periodFrom: null }), PROFILE, ENTITY, 'now').data as Record<
      string,
      unknown
    >;
    expect('periodFrom' in d).toBe(false);
    expect(d.periodTo).toBe('31 Mar 2026');
  });
});

describe('renderStatementCore', () => {
  const seed = {
    'users/u-view': { isActive: true, role: 'member', permissions: { profiles: 'view' } },
    'users/u-none': { isActive: true, role: 'member', permissions: { profiles: 'none' } },
    'users/u-inactive': { isActive: false, role: 'admin', permissions: { profiles: 'manage' } },
    'entities/e1': ENTITY,
    'companySettings/profile': PROFILE,
  };

  it('renders and returns the engine URL for a permitted caller', async () => {
    const res = await renderStatementCore(fakeDb(seed), cfg, 'u-view', { entityId: 'e1', statement: sampleStatement() }, new Date());
    expect(res.url).toBe('https://pdf.example/statement.pdf');
    expect(renderInvoice).toHaveBeenCalledOnce();
  });

  it('rejects a caller without profiles: view', async () => {
    await expect(
      renderStatementCore(fakeDb(seed), cfg, 'u-none', { entityId: 'e1', statement: sampleStatement() }, new Date()),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });

  it('rejects an inactive caller even if admin', async () => {
    await expect(
      renderStatementCore(fakeDb(seed), cfg, 'u-inactive', { entityId: 'e1', statement: sampleStatement() }, new Date()),
    ).rejects.toBeInstanceOf(StatementError);
  });

  it('404s a missing party', async () => {
    await expect(
      renderStatementCore(fakeDb(seed), cfg, 'u-view', { entityId: 'gone', statement: sampleStatement() }, new Date()),
    ).rejects.toMatchObject({ code: 'not-found' });
  });
});
