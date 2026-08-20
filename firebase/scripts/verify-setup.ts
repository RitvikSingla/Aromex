/**
 * Read back what setup-project.ts wrote, plus exercise a few negative
 * rule paths via the REST API (uses a real Firebase ID token).
 *
 * Run after setup-project.ts; needs the same SA key and the project's Web
 * API key.
 *
 *   npx tsx scripts/verify-setup.ts \
 *     --projectId aromex-june-2026 \
 *     --serviceAccountKey ./secrets/aromex-june-2026-sa.json \
 *     --apiKey <web api key> \
 *     --ownerEmail owner@aromex.test \
 *     --ownerPassword <password>
 */

import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';
import { parseArgs } from 'node:util';

import { cert, initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

const { values } = parseArgs({
  options: {
    projectId: { type: 'string' },
    serviceAccountKey: { type: 'string' },
    apiKey: { type: 'string' },
    ownerEmail: { type: 'string' },
    ownerPassword: { type: 'string' },
  },
});

function need(name: string, v: string | undefined): string {
  if (!v) {
    console.error(`Missing --${name}`);
    process.exit(2);
  }
  return v;
}
const projectId = need('projectId', values.projectId);
const saKey = resolvePath(need('serviceAccountKey', values.serviceAccountKey));
const apiKey = need('apiKey', values.apiKey);
const ownerEmail = need('ownerEmail', values.ownerEmail);
const ownerPassword = need('ownerPassword', values.ownerPassword);

const sa = JSON.parse(readFileSync(saKey, 'utf8'));
const app = initializeApp({ credential: cert(sa), projectId }, 'aromex-verify');
const db = getFirestore(app);

console.log('[A] Admin SDK: read companySettings/profile + users/<owner>');
const profile = (await db.collection('companySettings').doc('profile').get()).data();
console.log('   profile:', JSON.stringify(profile, null, 2));

const usersSnap = await db.collection('users').limit(5).get();
console.log(`   ${usersSnap.size} user(s) in users/:`);
usersSnap.docs.forEach((d) =>
  console.log(`     ${d.id} — role=${d.data().role} isActive=${d.data().isActive} permKeys=${Object.keys(d.data().permissions ?? {}).length}`),
);

console.log('\n[B] Sign in as the owner via Firebase Auth REST');
const signin = await fetch(
  `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
  {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: ownerEmail, password: ownerPassword, returnSecureToken: true }),
  },
).then((r) => r.json() as Promise<{ idToken?: string; localId?: string; error?: { message: string } }>);
if (!signin.idToken) {
  console.error('   sign-in failed:', signin);
  process.exit(1);
}
const idToken = signin.idToken;
const uid = signin.localId!;
console.log(`   signed in as uid=${uid}`);

// Decode claims (header.payload.signature; payload is base64url JSON)
const payload = JSON.parse(Buffer.from(idToken.split('.')[1]!, 'base64url').toString('utf8'));
console.log(`   token claims: admin=${payload.admin} hlCompanyId=${payload.hlCompanyId}`);

// Firestore REST base for this project
const fsBase = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
async function restGet(path: string, token: string) {
  const r = await fetch(`${fsBase}/${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return { status: r.status, body: await r.text() };
}

console.log('\n[C] Positive — owner reads their own users/{uid}');
const c = await restGet(`users/${uid}`, idToken);
console.log(`   ${c.status} ${c.status === 200 ? '(OK)' : '(FAIL)'}: ${c.body.slice(0, 120)}`);

console.log('\n[D] Positive — owner reads companySettings/profile');
const d = await restGet('companySettings/profile', idToken);
console.log(`   ${d.status} ${d.status === 200 ? '(OK)' : '(FAIL)'}: ${d.body.slice(0, 120)}`);

console.log('\n[E] Negative — unauthenticated read of users/{uid} (no token)');
const e = await fetch(`${fsBase}/users/${uid}`);
console.log(`   ${e.status} ${e.status === 403 || e.status === 401 ? '(OK — denied)' : '(FAIL — leak!)'}: ${(await e.text()).slice(0, 120)}`);

console.log('\n[F] Negative — owner tries to read a non-existent OTHER user');
const f = await restGet('users/__definitely_not_a_real_uid__', idToken);
// Admin can list, so this 404s for not found rather than 403. Either is fine.
console.log(`   ${f.status} ${f.status === 404 || f.status === 403 ? '(OK)' : '(FAIL)'}: ${f.body.slice(0, 120)}`);

console.log('\n[G] Negative — try to read a collection that has no rules (catch-all deny)');
const g = await restGet('sales/whatever', idToken);
console.log(`   ${g.status} ${g.status === 403 || g.status === 404 ? '(OK — denied/missing)' : '(FAIL — leak!)'}: ${g.body.slice(0, 120)}`);

console.log('\nDone.');
process.exit(0);
