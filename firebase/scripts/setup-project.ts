/**
 * Bootstrap a per-client Firebase project for Aromex.
 *
 * What it does:
 *   1. Deploys firestore.rules (via the Firebase CLI).
 *   2. Creates (or looks up) the first admin user in Firebase Auth.
 *   3. Sets custom claims { admin: true, hlCompanyId } on that user.
 *   4. Writes users/{uid} with role="admin" + FULL_ADMIN_PERMISSIONS.
 *   5. Writes companySettings/profile with the supplied settings.
 *   6. Creates the reserved Walk-in Customer entity (if missing).
 *
 * Idempotent: re-running with the same flags is safe — the script upserts
 * everything (looks up the user by email if one already exists, then
 * updates).
 *
 * Run via the npm script so we get firebase-tools from devDependencies:
 *   npm run setup -- --projectId ... --serviceAccountKey ... [other flags]
 */

import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

import { cert, initializeApp } from 'firebase-admin/app';
import { getAuth, type UserRecord } from 'firebase-admin/auth';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

import {
  FULL_ADMIN_PERMISSIONS,
  WALK_IN_ENTITY_ID,
  type CompanySettingsDoc,
  type CustomClaims,
  type EntityDoc,
  type UserDoc,
} from './types.js';

// ---------- args ----------

const { values } = parseArgs({
  options: {
    projectId: { type: 'string' },
    serviceAccountKey: { type: 'string' },

    ownerEmail: { type: 'string' },
    ownerPassword: { type: 'string' },
    ownerDisplayName: { type: 'string' },

    companyName: { type: 'string' },
    legalName: { type: 'string' },
    hlCompanyId: { type: 'string' },
    country: { type: 'string' },
    currency: { type: 'string' },
    timezone: { type: 'string' }, // IANA zone the shop trades in — dates invoices (ticket #80)

    // tax flags. All optional; default = no tax.
    gstRate: { type: 'string' },
    pstRate: { type: 'string' },
    isHST: { type: 'boolean' },

    contactEmail: { type: 'string' },
    contactPhone: { type: 'string' },
    businessAddress: { type: 'string' },
    taxNumber: { type: 'string' }, // GST/HST registration no. — for invoice letterheads (ticket #76)

    skipRulesDeploy: { type: 'boolean' },
  },
});

function required<T extends string>(name: T, v: string | undefined): string {
  if (!v) {
    console.error(`Missing required --${name}`);
    process.exit(2);
  }
  return v;
}

/**
 * Validate an IANA zone the same way the sync worker does, so a typo fails here — loudly, once —
 * instead of degrading to UTC on every invoice the shop ever issues.
 */
function requiredZone<T extends string>(name: T, v: string | undefined): string {
  const zone = required(name, v);
  try {
    new Intl.DateTimeFormat('en-CA', { timeZone: zone });
  } catch {
    console.error(
      `--${name} '${zone}' is not a valid IANA time zone (expected e.g. 'America/Vancouver').`,
    );
    process.exit(2);
  }
  return zone;
}

const projectId = required('projectId', values.projectId);
const serviceAccountKey = resolvePath(
  required('serviceAccountKey', values.serviceAccountKey),
);
const ownerEmail = required('ownerEmail', values.ownerEmail);
const ownerPassword = required('ownerPassword', values.ownerPassword);
const ownerDisplayName = required('ownerDisplayName', values.ownerDisplayName);
const companyName = required('companyName', values.companyName);
const hlCompanyId = required('hlCompanyId', values.hlCompanyId);
const country = required('country', values.country);
const currency = required('currency', values.currency);
const timezone = requiredZone('timezone', values.timezone);

const legalName = values.legalName ?? companyName;
const contactEmail = values.contactEmail ?? null;
const contactPhone = values.contactPhone ?? null;
const businessAddress = values.businessAddress ?? null;
const taxNumber = values.taxNumber ?? null;

const gstRate = values.gstRate ? Number(values.gstRate) : 0;
const pstRate = values.pstRate ? Number(values.pstRate) : 0;
const isHST = values.isHST === true;

if (!existsSync(serviceAccountKey)) {
  console.error(`Service-account key not found: ${serviceAccountKey}`);
  process.exit(2);
}

// ---------- init admin SDK ----------

const saJson = JSON.parse(readFileSync(serviceAccountKey, 'utf8'));
if (saJson.project_id !== projectId) {
  console.error(
    `Service-account key is for project '${saJson.project_id}', not '${projectId}'.`,
  );
  process.exit(2);
}

const app = initializeApp(
  { credential: cert(saJson), projectId },
  'aromex-setup',
);
const auth = getAuth(app);
const db = getFirestore(app);

// ---------- step 1: deploy rules ----------

const here = dirname(fileURLToPath(import.meta.url));
const firebaseDir = resolvePath(here, '..');

if (values.skipRulesDeploy) {
  console.log('[1/6] firestore.rules deploy — SKIPPED (--skipRulesDeploy)');
} else {
  console.log(`[1/6] Deploying firestore.rules to ${projectId} ...`);
  try {
    execSync(
      `npx firebase deploy --only firestore:rules --project ${projectId} --non-interactive`,
      {
        cwd: firebaseDir,
        stdio: 'inherit',
        env: { ...process.env, GOOGLE_APPLICATION_CREDENTIALS: serviceAccountKey },
      },
    );
  } catch (err) {
    console.error('Rules deploy failed.', err);
    process.exit(1);
  }
}

// ---------- step 2: ensure auth user ----------

console.log(`[2/6] Ensuring auth user ${ownerEmail} ...`);
let user: UserRecord;
try {
  user = await auth.getUserByEmail(ownerEmail);
  console.log(`     found existing user uid=${user.uid}`);
  await auth.updateUser(user.uid, {
    password: ownerPassword,
    displayName: ownerDisplayName,
    disabled: false,
  });
} catch (err) {
  if ((err as { code?: string }).code !== 'auth/user-not-found') throw err;
  user = await auth.createUser({
    email: ownerEmail,
    password: ownerPassword,
    displayName: ownerDisplayName,
    emailVerified: false,
    disabled: false,
  });
  console.log(`     created new user uid=${user.uid}`);
}

// ---------- step 3: set custom claims ----------

const claims: CustomClaims = { admin: true, hlCompanyId };
console.log(`[3/6] Setting custom claims ${JSON.stringify(claims)} ...`);
await auth.setCustomUserClaims(user.uid, claims);

// ---------- step 4: write users/{uid} ----------

console.log(`[4/6] Writing users/${user.uid} ...`);
const userDoc: Omit<UserDoc, 'createdAt' | 'updatedAt' | 'lastLoginAt'> & {
  createdAt: FirebaseFirestore.FieldValue;
  updatedAt: FirebaseFirestore.FieldValue;
  lastLoginAt: null;
} = {
  email: ownerEmail,
  displayName: ownerDisplayName,
  role: 'admin',
  permissions: FULL_ADMIN_PERMISSIONS,
  isActive: true,
  createdBy: 'setup-script',
  createdAt: FieldValue.serverTimestamp(),
  updatedAt: FieldValue.serverTimestamp(),
  lastLoginAt: null,
};
// merge:true so re-runs don't blow away createdAt
await db.collection('users').doc(user.uid).set(userDoc, { merge: true });

// ---------- step 5: write companySettings/profile ----------

console.log('[5/6] Writing companySettings/profile ...');
const settingsDoc: Omit<CompanySettingsDoc, 'createdAt' | 'updatedAt'> & {
  createdAt: FirebaseFirestore.FieldValue;
  updatedAt: FirebaseFirestore.FieldValue;
} = {
  companyName,
  legalName,
  logoUrl: null,
  country,
  currency,
  tax: {
    gstEnabled: gstRate > 0,
    gstRate,
    pstEnabled: pstRate > 0,
    pstRate,
    isHST,
  },
  hlCompanyId,
  businessAddress,
  contactEmail,
  contactPhone,
  taxNumber,
  timezone,
  createdAt: FieldValue.serverTimestamp(),
  updatedAt: FieldValue.serverTimestamp(),
};
await db
  .collection('companySettings')
  .doc('profile')
  .set(settingsDoc, { merge: true });

// ---------- step 6: reserved Walk-in Customer entity ----------
//
// Anonymous paid-in-full sales post against this single reserved party. Created
// server-side (never from a client) as PENDING; the onEntityWrite Cloud Function
// creates its HL customer and flips it to SYNCED. Only created if missing, so a
// re-run never resets its sync state or duplicates it.

console.log(`[6/6] Ensuring reserved Walk-in entity (entities/${WALK_IN_ENTITY_ID}) ...`);
const walkInRef = db.collection('entities').doc(WALK_IN_ENTITY_ID);
const walkInSnap = await walkInRef.get();
if (walkInSnap.exists) {
  console.log('     walk-in already exists — leaving as-is');
} else {
  const walkInDoc: Omit<EntityDoc, 'createdAt' | 'updatedAt'> & {
    createdAt: FirebaseFirestore.FieldValue;
    updatedAt: FirebaseFirestore.FieldValue;
  } = {
    name: 'Walk-in Customer',
    phones: [],
    email: null,
    address: null,
    roles: ['CUSTOMER'],
    notes: null,
    isWalkIn: true,
    isActive: true,
    hlCustomerId: null,
    hlAccountId: null,
    syncStatus: 'PENDING',
    createdBy: 'setup-script',
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  };
  await walkInRef.set(walkInDoc);
  console.log('     created reserved walk-in entity (PENDING → HL sync via Cloud Function)');
}

// ---------- done ----------

console.log('\n----------------------------------------');
console.log('Setup complete.');
console.log(`  Project:        ${projectId}`);
console.log(`  Admin uid:      ${user.uid}`);
console.log(`  Admin email:    ${ownerEmail}`);
console.log(`  Admin password: ${ownerPassword}`);
console.log(`  hlCompanyId:    ${hlCompanyId}`);
console.log('----------------------------------------');
console.log('Email the credentials to the owner. They should change the password on first login.');

process.exit(0);
