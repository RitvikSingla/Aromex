/**
 * Firestore Security Rules — company settings + the settings audit log (M10 · ticket #98).
 *
 * The tax rate decides what every future sale charges the customer and owes the government, and
 * the company details are what appears on a legal invoice. Two things must hold no matter what a
 * compromised mobile client sends:
 *
 *   1. Only an admin changes them, and never `hlCompanyId` or `currency` — re-pointing the company
 *      at another HL ledger, or re-labelling every stored amount as a different currency, is not a
 *      settings edit.
 *   2. The audit log is append-only. An editable log of who changed the tax rate is not one.
 *
 * Run: `npm run test:rules` (boots the Firestore emulator via `firebase emulators:exec`).
 *
 * These rules are the MOBILE backstop. Desktop uses the Admin SDK and bypasses them, so the shared
 * `UpdateTaxConfigUseCase` / `UpdateCompanyDetailsUseCase` admin gates are the real enforcement
 * there — see SettingsUseCasesTest.
 */
import { readFileSync } from "node:fs";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  collection,
  setDoc,
  updateDoc,
  writeBatch,
  type Firestore,
} from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, test } from "vitest";

const PROJECT_ID = "aromex-settings-rules-test";
const COMPANY_ID = "co_test_1"; // must match users' hlCompanyId claim + companySettings

let testEnv: RulesTestEnvironment;

/** The provisioned profile, as setup-project.ts writes it. */
function seededProfile() {
  return {
    hlCompanyId: COMPANY_ID,
    currency: "CAD",
    country: "CA",
    timezone: "America/Vancouver",
    companyName: "Acme Mobile",
    legalName: "Acme Mobile Ltd.",
    taxNumber: "123456789RT0001",
    businessAddress: "1 Main St",
    contactEmail: "hi@acme.test",
    contactPhone: "+1 604 555 0100",
    tax: { gstEnabled: true, gstRate: 0.05, pstEnabled: true, pstRate: 0.07, isHST: false },
  };
}

/** A well-formed audit entry, as the Settings screen writes it. */
function validChange(uid: string, over: Record<string, unknown> = {}) {
  return {
    changeId: "c1",
    field: "GST rate",
    oldValue: "5%",
    newValue: "6%",
    changedBy: uid,
    changedByName: "Maya",
    changedAt: Date.now(),
    ...over,
  };
}

/** Firestore handle for a signed-in user carrying the company + admin claims. */
function db(uid: string, opts: { admin?: boolean; company?: string } = {}): Firestore {
  return testEnv
    .authenticatedContext(uid, {
      hlCompanyId: opts.company ?? COMPANY_ID,
      admin: opts.admin ?? false,
    })
    .firestore() as unknown as Firestore;
}

const adminDb = () => db("u_admin", { admin: true });
const memberDb = () => db("u_member");
/** An admin whose token belongs to a DIFFERENT company's Firebase project. */
const foreignAdminDb = () => db("u_admin", { admin: true, company: "co_other" });

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8"),
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.firestore();
    await setDoc(doc(d, "companySettings/profile"), seededProfile());
    await setDoc(doc(d, "users/u_admin"), { isActive: true, role: "admin", permissions: {} });
    await setDoc(doc(d, "users/u_member"), { isActive: true, role: "member", permissions: {} });
  });
});

describe("companySettings/profile", () => {
  test("any signed-in user can read it (every screen needs currency + tax)", async () => {
    await assertSucceeds(getDoc(doc(memberDb(), "companySettings/profile")));
  });

  test("admin can change the tax rate", async () => {
    await assertSucceeds(
      updateDoc(doc(adminDb(), "companySettings/profile"), {
        tax: { gstEnabled: true, gstRate: "0.06", pstEnabled: true, pstRate: "0.07", isHST: false },
      }),
    );
  });

  test("admin can change the invoice identity fields", async () => {
    await assertSucceeds(
      updateDoc(doc(adminDb(), "companySettings/profile"), {
        companyName: "Acme Mobile 2",
        taxNumber: "987654321RT0001",
        businessAddress: "2 Main St",
      }),
    );
  });

  test("a non-admin member is DENIED any change", async () => {
    await assertFails(
      updateDoc(doc(memberDb(), "companySettings/profile"), { companyName: "Hijacked" }),
    );
  });

  test("admin is DENIED re-pointing hlCompanyId at another ledger", async () => {
    await assertFails(
      updateDoc(doc(adminDb(), "companySettings/profile"), { hlCompanyId: "co_other" }),
    );
  });

  test("admin is DENIED changing the currency (it would re-label, not convert)", async () => {
    await assertFails(updateDoc(doc(adminDb(), "companySettings/profile"), { currency: "USD" }));
  });

  test("changing hlCompanyId alongside a legitimate edit is still DENIED", async () => {
    await assertFails(
      updateDoc(doc(adminDb(), "companySettings/profile"), {
        companyName: "Acme Mobile 2",
        hlCompanyId: "co_other",
      }),
    );
  });

  test("an admin from another company's project is DENIED", async () => {
    await assertFails(
      updateDoc(doc(foreignAdminDb(), "companySettings/profile"), { companyName: "Hijacked" }),
    );
  });

  test("nobody may delete the profile", async () => {
    await assertFails(deleteDoc(doc(adminDb(), "companySettings/profile")));
  });
});

describe("companySettingsChanges (append-only audit)", () => {
  /** Seed one entry with rules disabled, for the update/delete/read cases. */
  async function seedChange() {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "companySettingsChanges/c1"), validChange("u_admin"));
    });
  }

  test("admin can append an entry naming themselves", async () => {
    await assertSucceeds(
      setDoc(doc(adminDb(), "companySettingsChanges/c1"), validChange("u_admin")),
    );
  });

  test("admin is DENIED logging a change under someone else's uid", async () => {
    await assertFails(
      setDoc(doc(adminDb(), "companySettingsChanges/c1"), validChange("u_member")),
    );
  });

  test("a non-admin member is DENIED appending", async () => {
    await assertFails(
      setDoc(doc(memberDb(), "companySettingsChanges/c1"), validChange("u_member")),
    );
  });

  test("admin can read the log", async () => {
    await seedChange();
    await assertSucceeds(getDocs(collection(adminDb(), "companySettingsChanges")));
  });

  test("a non-admin member is DENIED reading the log (it names who did what)", async () => {
    await seedChange();
    await assertFails(getDocs(collection(memberDb(), "companySettingsChanges")));
  });

  test("nobody may edit an entry — not even its own author", async () => {
    await seedChange();
    await assertFails(updateDoc(doc(adminDb(), "companySettingsChanges/c1"), { newValue: "5%" }));
  });

  test("nobody may delete an entry", async () => {
    await seedChange();
    await assertFails(deleteDoc(doc(adminDb(), "companySettingsChanges/c1")));
  });

  test("the profile write and its audit entries commit together", async () => {
    const d = adminDb();
    const batch = writeBatch(d);
    batch.set(
      doc(d, "companySettings/profile"),
      {
        tax: { gstEnabled: true, gstRate: "0.06", pstEnabled: true, pstRate: "0.07", isHST: false },
      },
      { merge: true },
    );
    batch.set(doc(d, "companySettingsChanges/c1"), validChange("u_admin"));
    await assertSucceeds(batch.commit());
  });

  test("a batch that forges changedBy takes the profile write down with it", async () => {
    const d = adminDb();
    const batch = writeBatch(d);
    batch.set(doc(d, "companySettings/profile"), { companyName: "Acme Mobile 2" }, { merge: true });
    batch.set(doc(d, "companySettingsChanges/c1"), validChange("u_member"));
    await assertFails(batch.commit());
  });
});
