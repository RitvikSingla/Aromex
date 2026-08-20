/**
 * Firestore Security Rules — Inventory collections (M4 · ticket #44).
 *
 * Proves the rules in firestore.rules gate products / serials / attributes / imeiIndex
 * on the caller's `inventory` permission, and — the crux of this ticket — that they
 * PERMIT the atomic client add-stock write for a `manage` user while DENYING view/none.
 *
 * Run: `npm run test:rules` (boots the Firestore emulator via `firebase emulators:exec`).
 *
 * These rules are the MOBILE backstop. Desktop uses the Admin SDK and bypasses them, so
 * shared AddStockUseCase + permission gates (T1) are the real enforcement there.
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
  runTransaction,
  setDoc,
  updateDoc,
  writeBatch,
  type Firestore,
} from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "vitest";

const PROJECT_ID = "aromex-rules-test";
const COMPANY_ID = "co_test_1"; // must match users' hlCompanyId claim + companySettings

// A concrete SKU from docs/SCHEMA.md's worked example.
const SKU = "aBrandApple_aModeliPhone15_aCap128_aColorPink_aCarrUnlocked";
const IMEI = "356938035699001";

let testEnv: RulesTestEnvironment;

/** A well-formed products/{SKU} doc. */
function validProduct() {
  return {
    productId: SKU,
    trackingMode: "SERIALIZED",
    attributes: { brand: { attributeId: "aBrandApple", name: "Apple" } },
    defaultSellingPrice: "699.00",
    isActive: true,
    createdBy: "u_manager",
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

/** A well-formed serials/{serialId} doc. */
function validSerial(serialId: string, imei: string) {
  return {
    serialId,
    productId: SKU,
    imei,
    cost: "560.00",
    condition: "NEW",
    status: "IN_STOCK",
    location: { attributeId: "aLocWH_A", name: "Warehouse A" },
    isActive: true,
    saleId: null,
    createdBy: "u_manager",
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

/** A well-formed imeiIndex/{imei} doc. */
function validIndex(imei: string, serialId: string) {
  return { imei, serialId, productId: SKU };
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

const manageDb = () => db("u_manager");
const viewDb = () => db("u_viewer");
const noneDb = () => db("u_none");

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
  // Seed the docs the rules READ (companySettings + users) with rules disabled.
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await setDoc(doc(adminDb, "companySettings/profile"), { hlCompanyId: COMPANY_ID });
    await setDoc(doc(adminDb, "users/u_manager"), {
      isActive: true,
      permissions: { inventory: "manage" },
    });
    await setDoc(doc(adminDb, "users/u_viewer"), {
      isActive: true,
      permissions: { inventory: "view" },
    });
    await setDoc(doc(adminDb, "users/u_none"), {
      isActive: true,
      permissions: { inventory: "none" },
    });
  });
});

/** Seed an in-stock unit (product + serial + index) with rules disabled. */
async function seedInStock(serialId: string, imei: string) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await setDoc(doc(adminDb, "products", SKU), validProduct());
    await setDoc(doc(adminDb, "serials", serialId), validSerial(serialId, imei));
    await setDoc(doc(adminDb, "imeiIndex", imei), validIndex(imei, serialId));
  });
}

describe("add-stock atomic write (the ticket's crux)", () => {
  test("manage user can create product + serial + imeiIndex in one batch", async () => {
    const d = manageDb();
    const batch = writeBatch(d);
    batch.set(doc(d, "products", SKU), validProduct());
    batch.set(doc(d, "serials", "s1"), validSerial("s1", IMEI));
    batch.set(doc(d, "imeiIndex", IMEI), validIndex(IMEI, "s1"));
    await assertSucceeds(batch.commit());
  });

  test("view user is DENIED the add-stock batch", async () => {
    const d = viewDb();
    const batch = writeBatch(d);
    batch.set(doc(d, "products", SKU), validProduct());
    batch.set(doc(d, "serials", "s1"), validSerial("s1", IMEI));
    batch.set(doc(d, "imeiIndex", IMEI), validIndex(IMEI, "s1"));
    await assertFails(batch.commit());
  });

  test("none user is DENIED the add-stock batch", async () => {
    const d = noneDb();
    const batch = writeBatch(d);
    batch.set(doc(d, "products", SKU), validProduct());
    batch.set(doc(d, "serials", "s1"), validSerial("s1", IMEI));
    batch.set(doc(d, "imeiIndex", IMEI), validIndex(IMEI, "s1"));
    await assertFails(batch.commit());
  });

  test("duplicate-IMEI abort path: transaction reads imeiIndex and aborts when present", async () => {
    // The client transaction (T3/T4) reads imeiIndex/{imei}; if present, it aborts.
    await seedInStock("s_existing", IMEI);
    const d = manageDb();
    await expect(
      runTransaction(d, async (tx) => {
        const existing = await tx.get(doc(d, "imeiIndex", IMEI));
        if (existing.exists()) throw new Error(`DuplicateImei:${IMEI}`);
        tx.set(doc(d, "serials", "s_new"), validSerial("s_new", IMEI));
        tx.set(doc(d, "imeiIndex", IMEI), validIndex(IMEI, "s_new"));
      }),
    ).rejects.toThrow(/DuplicateImei/);
  });

  test("re-add after release: a freed IMEI (no index entry) can be re-added by manage", async () => {
    // Simulate sold: serial exists but its imeiIndex was released.
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const adminDb = ctx.firestore();
      await setDoc(doc(adminDb, "products", SKU), validProduct());
      await setDoc(doc(adminDb, "serials", "s_sold"), {
        ...validSerial("s_sold", IMEI),
        status: "SOLD",
      });
      // no imeiIndex/{IMEI} — released on sale
    });
    const d = manageDb();
    const batch = writeBatch(d);
    batch.set(doc(d, "serials", "s_readd"), validSerial("s_readd", IMEI));
    batch.set(doc(d, "imeiIndex", IMEI), validIndex(IMEI, "s_readd"));
    await assertSucceeds(batch.commit());
  });
});

describe("products/{productId}", () => {
  test("view user can read a product", async () => {
    await seedInStock("s1", IMEI);
    await assertSucceeds(getDoc(doc(viewDb(), "products", SKU)));
  });

  test("create rejected when doc id != productId field (skuKey mismatch)", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "products", "wrong-id"), { ...validProduct(), productId: SKU }),
    );
  });

  test("create rejected for unknown trackingMode", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "products", SKU), { ...validProduct(), trackingMode: "PHONE" }),
    );
  });

  test("create rejected when defaultSellingPrice is not a string (money must be decimal string)", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "products", SKU), { ...validProduct(), defaultSellingPrice: 699 }),
    );
  });

  test("hard delete is denied even for manage (archive via isActive)", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(deleteDoc(doc(manageDb(), "products", SKU)));
  });
});

describe("serials/{serialId}", () => {
  test("create rejected for unknown status", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "serials", "s1"), { ...validSerial("s1", IMEI), status: "LOST" }),
    );
  });

  test("create rejected for unknown condition", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "serials", "s1"), { ...validSerial("s1", IMEI), condition: "REFURB" }),
    );
  });

  test("create rejected when cost is not a string", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "serials", "s1"), { ...validSerial("s1", IMEI), cost: 560 }),
    );
  });

  test("create rejected when imei is empty", async () => {
    const d = manageDb();
    await assertFails(
      setDoc(doc(d, "serials", "s1"), { ...validSerial("s1", IMEI), imei: "" }),
    );
  });

  test("hard delete is denied even for manage", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(deleteDoc(doc(manageDb(), "serials", "s1")));
  });
});

describe("attributes/{attributeId}", () => {
  const validAttr = { attributeId: "aColorPink", type: "color", name: "Pink", nameKey: "pink", parentId: null, isActive: true };

  test("manage can create a known-type attribute", async () => {
    await assertSucceeds(setDoc(doc(manageDb(), "attributes", "aColorPink"), validAttr));
  });

  test("create rejected for unknown type", async () => {
    await assertFails(
      setDoc(doc(manageDb(), "attributes", "aX"), { ...validAttr, type: "ram" }),
    );
  });

  test("view user is denied create", async () => {
    await assertFails(setDoc(doc(viewDb(), "attributes", "aColorPink"), validAttr));
  });
});

describe("imeiIndex/{imei}", () => {
  test("manage can create when doc id == imei field", async () => {
    await assertSucceeds(
      setDoc(doc(manageDb(), "imeiIndex", IMEI), validIndex(IMEI, "s1")),
    );
  });

  test("create rejected when doc id != imei field", async () => {
    await assertFails(
      setDoc(doc(manageDb(), "imeiIndex", "999"), validIndex(IMEI, "s1")),
    );
  });

  test("manage can DELETE an index entry (release on sold/archive)", async () => {
    await seedInStock("s1", IMEI);
    await assertSucceeds(deleteDoc(doc(manageDb(), "imeiIndex", IMEI)));
  });

  test("view user is denied delete", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(deleteDoc(doc(viewDb(), "imeiIndex", IMEI)));
  });
});

describe("in-place update guards (immutability — review hardening #44)", () => {
  test("product: manage can change defaultSellingPrice", async () => {
    await seedInStock("s1", IMEI);
    await assertSucceeds(
      updateDoc(doc(manageDb(), "products", SKU), { defaultSellingPrice: "750.00" }),
    );
  });

  test("product: manage can archive (isActive=false)", async () => {
    await seedInStock("s1", IMEI);
    await assertSucceeds(updateDoc(doc(manageDb(), "products", SKU), { isActive: false }));
  });

  test("product: editing the SKU-defining attributes map is denied", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(
      updateDoc(doc(manageDb(), "products", SKU), {
        attributes: { brand: { attributeId: "aBrandApple", name: "Samsung" } },
      }),
    );
  });

  test("product: changing productId is denied", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(updateDoc(doc(manageDb(), "products", SKU), { productId: "other" }));
  });

  test("serial: manage can transition status (IN_STOCK -> SOLD)", async () => {
    await seedInStock("s1", IMEI);
    await assertSucceeds(
      updateDoc(doc(manageDb(), "serials", "s1"), { status: "SOLD", saleId: "sale_1" }),
    );
  });

  test("serial: editing imei in place is denied (correct-by-void+re-add, not edit)", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(
      updateDoc(doc(manageDb(), "serials", "s1"), { imei: "356938035699999" }),
    );
  });

  test("serial: changing productId is denied", async () => {
    await seedInStock("s1", IMEI);
    await assertFails(updateDoc(doc(manageDb(), "serials", "s1"), { productId: "other" }));
  });
});

describe("cross-company isolation", () => {
  test("a manage user from another company is denied", async () => {
    const foreign = db("u_manager", { company: "co_other" });
    await assertFails(setDoc(doc(foreign, "products", SKU), validProduct()));
  });
});
