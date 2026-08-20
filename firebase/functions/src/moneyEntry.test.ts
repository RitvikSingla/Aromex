import { describe, expect, it } from 'vitest';
import {
  moneyEntryRejection,
  moneyEntrySourceId,
  type MoneyEntryData,
} from './syncWorker.js';

const party = (entityId: string) => ({ kind: 'PARTY' as const, entityId });
const cash = { kind: 'CASH' as const };
const bank = { kind: 'BANK' as const };

function entry(over: Partial<MoneyEntryData> = {}): MoneyEntryData {
  return {
    from: party('ent-rajesh'),
    to: cash,
    amount: '500.00',
    ...over,
  };
}

describe('moneyEntrySourceId', () => {
  /**
   * The whole reason a redelivered trigger can't move the money twice. The legacy app had no
   * equivalent: it read a balance outside a transaction and wrote back an absolute value, so a
   * replay (or a concurrent entry) silently lost one side.
   */
  it('is deterministic for an entry id', () => {
    expect(moneyEntrySourceId('e1')).toBe('money_e1');
    expect(moneyEntrySourceId('e1')).toBe(moneyEntrySourceId('e1'));
  });

  it('differs between entries', () => {
    expect(moneyEntrySourceId('e1')).not.toBe(moneyEntrySourceId('e2'));
  });

  /** Distinct namespace from sales/purchases, so ids can never collide across collections. */
  it('is namespaced away from sale and purchase keys', () => {
    expect(moneyEntrySourceId('x')).toBe('money_x');
    expect(moneyEntrySourceId('x')).not.toBe('sale_x:sale');
    expect(moneyEntrySourceId('x')).not.toBe('purchase_x:purchase');
  });
});

describe('moneyEntryRejection — server-side re-validation', () => {
  /** The client validates for a good inline message; the CF validates because a client can lie
   *  and this writes to the books. Both must agree. */

  it('accepts every direction the screen offers', () => {
    expect(moneyEntryRejection(entry({ from: party('a'), to: cash }))).toBeUndefined();
    expect(moneyEntryRejection(entry({ from: bank, to: party('a') }))).toBeUndefined();
    expect(moneyEntryRejection(entry({ from: party('a'), to: party('b') }))).toBeUndefined();
    expect(moneyEntryRejection(entry({ from: cash, to: bank }))).toBeUndefined();
  });

  it('rejects moving money to the same account', () => {
    expect(moneyEntryRejection(entry({ from: cash, to: cash }))).toMatch(/same account/);
    expect(moneyEntryRejection(entry({ from: bank, to: bank }))).toMatch(/same account/);
    expect(moneyEntryRejection(entry({ from: party('a'), to: party('a') }))).toMatch(/same account/);
  });

  /** Two different parties are NOT the same account, even though both are kind PARTY. */
  it('treats two different parties as different accounts', () => {
    expect(moneyEntryRejection(entry({ from: party('a'), to: party('b') }))).toBeUndefined();
  });

  it('rejects a non-positive or unparseable amount', () => {
    for (const amount of ['0', '0.00', '-5', 'abc', '', 'NaN']) {
      expect(moneyEntryRejection(entry({ amount })), `amount '${amount}'`).toMatch(/positive/);
    }
  });

  it('rejects a PARTY side with no entity id', () => {
    expect(moneyEntryRejection(entry({ from: { kind: 'PARTY' } }))).toMatch(/from party/);
    expect(moneyEntryRejection(entry({ from: cash, to: { kind: 'PARTY' } }))).toMatch(/to party/);
  });

  it('rejects an unknown account kind rather than guessing', () => {
    expect(
      moneyEntryRejection(entry({ from: { kind: 'WALLET' as unknown as 'CASH' } })),
    ).toMatch(/unknown account kind/);
  });

  /**
   * Direction is carried by from/to, never by the sign of the amount — a negative amount would be
   * ambiguous about which side it applied to, so it's rejected rather than silently normalised.
   */
  it('rejects a negative amount instead of flipping the direction', () => {
    expect(moneyEntryRejection(entry({ amount: '-500.00' }))).toMatch(/positive/);
  });
});
