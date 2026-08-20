/**
 * TypeScript types matching firebase/SCHEMA.md.
 *
 * Source of truth is SCHEMA.md — keep these in lockstep.
 */

import type { Timestamp } from 'firebase-admin/firestore';

export type PermissionLevel = 'manage' | 'view' | 'none';

export type Permissions = {
  sales: PermissionLevel;
  purchases: PermissionLevel;
  inventory: PermissionLevel;
  transactions: PermissionLevel;
  profiles: PermissionLevel;
  balances: PermissionLevel;
  reports: PermissionLevel;
  statistics: PermissionLevel;
  histories: PermissionLevel;
  ledgers: PermissionLevel;
  settings: PermissionLevel;
  userMgmt: boolean;
};

export type UserRole = 'admin' | 'member';

export type UserDoc = {
  email: string;
  displayName: string;
  role: UserRole;
  permissions: Permissions;
  isActive: boolean;
  createdBy: string;
  createdAt: Timestamp | FirebaseFirestore.FieldValue;
  updatedAt: Timestamp | FirebaseFirestore.FieldValue;
  lastLoginAt: Timestamp | null;
};

export type TaxConfig = {
  gstEnabled: boolean;
  gstRate: number;
  pstEnabled: boolean;
  pstRate: number;
  isHST: boolean;
};

export type CompanySettingsDoc = {
  companyName: string;
  legalName: string;
  logoUrl: string | null;
  country: string;
  currency: string;
  tax: TaxConfig;
  hlCompanyId: string;
  businessAddress: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  /**
   * The shop's GST/HST registration number (ticket #76) — legally required on a Canadian tax
   * invoice. Null when the shop isn't tax-registered; the invoice omits the whole tax line.
   */
  taxNumber: string | null;
  /**
   * IANA time zone the shop trades in, e.g. "America/Vancouver" (ticket #80). Invoice dates are
   * formatted in this zone so an evening sale keeps its own calendar day — and therefore its own
   * tax period — on the PDF. Provisioning requires it: with no value the Cloud Function falls
   * back to UTC, which silently mis-dates every invoice issued after late afternoon.
   */
  timezone: string;
  createdAt: Timestamp | FirebaseFirestore.FieldValue;
  updatedAt: Timestamp | FirebaseFirestore.FieldValue;
};

export type CustomClaims = {
  admin: boolean;
  hlCompanyId: string;
};

export type HlSyncStatus = 'PENDING' | 'SYNCED' | 'FAILED';
export type EntityRole = 'CUSTOMER' | 'SUPPLIER' | 'MIDDLEMAN';

/**
 * A party (customer/supplier/middleman) in `entities/{id}`. Roles are non-binding
 * labels, stored UPPERCASE. Balance is NOT stored here — read live from HL. The HL
 * sync fields + syncStatus=SYNCED are owned by the onEntityWrite Cloud Function.
 */
export type EntityDoc = {
  name: string;
  phones: string[];
  email: string | null;
  address: string | null;
  roles: EntityRole[];
  notes: string | null;
  isWalkIn: boolean;
  isActive: boolean;
  hlCustomerId: string | null;
  hlAccountId: string | null;
  syncStatus: HlSyncStatus;
  createdBy: string;
  createdAt: Timestamp | FirebaseFirestore.FieldValue;
  updatedAt: Timestamp | FirebaseFirestore.FieldValue;
};

/** The reserved, un-deletable "Walk-in Customer" doc id (anonymous paid-in-full sales). */
export const WALK_IN_ENTITY_ID = 'walk-in';

export const FULL_ADMIN_PERMISSIONS: Permissions = {
  sales: 'manage',
  purchases: 'manage',
  inventory: 'manage',
  transactions: 'manage',
  profiles: 'manage',
  balances: 'manage',
  reports: 'manage',
  statistics: 'manage',
  histories: 'manage',
  ledgers: 'manage',
  settings: 'manage',
  userMgmt: true,
};

export const NO_ACCESS_PERMISSIONS: Permissions = {
  sales: 'none',
  purchases: 'none',
  inventory: 'none',
  transactions: 'none',
  profiles: 'none',
  balances: 'none',
  reports: 'none',
  statistics: 'none',
  histories: 'none',
  ledgers: 'none',
  settings: 'none',
  userMgmt: false,
};
