> **Desktop** · Inventory browse + Add-Inventory + SICKW paste · Owner-approved 2026-08-01
> Every decision below is **settled** — nothing here is still under discussion.

## 📖 Story / Why

The Inventory screen is where people spend the day. A pass of real use turned up one genuine bug and
eight rough edges. None is hard; together they decide whether the screen feels finished.

Each item was checked against the code, so the diagnosis ships with the ticket. Where the fix has a
trap in it, the trap is named — those are the parts worth reading before writing anything.

---

# Part 1 — The bug

## 1. 🐛 Opening a brand dumps every unit at once

`InventoryScreen.kt:845-853`:

```kotlin
var collapsedBrands by remember { mutableStateOf(setOf<String>()) }
var collapsedModels by remember { mutableStateOf(setOf<String>()) }   // ← never seeded

LaunchedEffect(state.browseGroups.isNotEmpty()) {
    if (state.browseGroups.isNotEmpty() && !hasInitializedCollapse) {
        collapsedBrands = state.browseGroups.map { it.brandName }.toSet()   // brands seeded
        hasInitializedCollapse = true                                        // models are not
    }
}
```

Brands are seeded collapsed; models never are, so "collapsed" is meaningless for a model. Open one
brand and its entire subtree is already open.

**Required approach — invert the state to `expanded`, don't just seed the second set.**

Seeding `collapsedModels` alongside `collapsedBrands` fixes today's symptom and leaves the bug
waiting: any model arriving *after* first load — a filter change, a location switch, live data — is
absent from the collapsed set and therefore expanded. The seeding effect is also gated on
`hasInitializedCollapse`, so it never runs twice.

Track **`expandedBrands` / `expandedModels`** instead. Anything unseen is closed by definition, the
`LaunchedEffect` and the `hasInitializedCollapse` flag both disappear, and "expand all" / "collapse
all" become setting the set to everything or clearing it.

Keep expansion keyed so two models of the same name under **different brands** are distinct —
`"$brandName/$modelName"`, not `modelName`.

---

# Part 2 — Field rules

## 2. ✅ Only brand, model, location, IMEI and **cost** are compulsory

| Field | Now | After |
|---|---|---|
| Brand, Model, Location, IMEI | required | **required** |
| **Cost** | required | **required — decided, see below** |
| Capacity, Colour, Carrier | required | **optional**, blank allowed |
| Selling price | required | **optional** — set at sale time |
| Condition | defaulted | unchanged |

**Why cost stays.** It is not a descriptive field. It is booked to Humble Ledger as the inventory
asset and becomes the **cost of goods sold** when the unit sells. Blank or zero means:

- inventory booked at zero — the asset is understated;
- **COGS of zero on sale, so the whole sale price is reported as profit.** Bought at 900, sold at
  1,000 → reports 1,000 profit instead of 100;
- percent-of-cost commission (#97) computes zero;
- the purchase posted against the supplier is zero, so their balance is wrong.

Everything else is genuinely descriptive and may be blank.

### ⚠️ The trap in making SKU attributes optional

Capacity, Colour and Carrier are **SKU-defining**
(`AttributeType.SKU_DEFINING = BRAND, MODEL, CAPACITY, COLOR, CARRIER`), and `SkuKey.build`
currently rejects a blank:

```kotlin
require(id.isNotBlank()) { "Missing SKU attribute: ${type.wire}" }
```

Drop that `require` — but **keep the empty segment in the joined key**. `SEPARATOR = "_"` and
attribute ids are alphanumeric, so `brandId_modelId__colorId_carrierId` (blank capacity) stays
unambiguous.

**Do not "tidy" the key by filtering blanks out.** `joinToString` over only the populated
attributes collides: a unit with capacity-only and a unit with colour-only would both produce
`brandId_modelId_X`, silently merging two different SKUs into one product. The empty segment is what
holds the position.

Consequence to accept: every unit with an unknown capacity groups into **one** SKU. Splitting them
later means editing units, and since the SKU key *is* the product document id, that re-keys the
product. Fine for "we don't know yet" — out of scope here.

Blank attributes must render as an **empty cell** everywhere they appear — browse table, sales
picker, invoice — never `null`, `—`, or a crash.

## 3. 🔢 IMEI — replace the length rule, don't just delete it

`Imei.isValid` enforces 14–16 digits:

```kotlin
private val DIGITS_14_16 = Regex("^\\d{14,16}$")
```

The limit goes: the app is meant to carry more than phones, and real serials aren't 15 digits.

**But it cannot become "accept anything."** The value is used as a **Firestore document id**
(`imeiIndex/{imei}`) — that is what makes the in-stock uniqueness guard race-safe. A pasted serial
containing `/` breaks the write with an error pointing nowhere near the cause.

Replace the length rule with document-id safety. Reject:

- blank after trimming;
- any `/`;
- exactly `.` or `..`;
- anything matching `__.*__` (Firestore reserves it);
- longer than 64 characters (Firestore's real limit is 1500 bytes; nothing legitimate is near either).

Keep the duplicate-within-batch check unchanged. Trim before both validating and storing, so the
stored value and the doc id can never diverge.

---

# Part 3 — SICKW paste screen

## 4. 📋 The paste box grows without limit

`InventoryScreen.kt:3023`:

```kotlin
modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
```

A minimum with **no maximum**. A large paste stretches the box until the page scrolls and the
buttons are pushed off-screen — the opposite of the box being too small.

**Fix:** `heightIn(min = 220.dp, max = 320.dp)` with the text scrolling **inside**. The controls stay
put no matter how much is pasted.

## 5. 🔤 "Parse" is jargon

| Key | Now | Change to |
|---|---|---|
| `inventory_paste_parse` | "Parse & add" | **"Read & add"** |
| `inventory_parse_summary` | "Parsed {0} phones · {1} couldn't be read" | **"Found {0} phones · {1} couldn't be read"** |
| `inventory_parse_summary_one` | (same shape) | match |
| `inventory_parse_none` | | no "parse" in the text |

**Change the displayed values only — leave the string keys alone**, or every call site churns for
nothing.

## 6. 🗑️ Remove "Paste more"

Delete the control (`InventoryScreen.kt:3061`) and the `inventory_paste_more` string. Pasting again
should simply work.

---

# Part 4 — UI passes

## 7. ➖ No divider between brands

With several brands expanded the groups run together. Add a divider between **top-level brand
groups** only — not between models or units. The point is separating the groups the eye scans.

## 8. 🔘 The bottom Confirm button

`InventoryScreen.kt:1776` — a full-width bar that matches nothing else in the app. Bring it in line:
`PrimaryButton`, standard radius and height, aligned right within its bar rather than stretched edge
to edge.

## 9. 🎛️ The location pills row

Visual pass on the top bar: consistent pill height and radius, even spacing, an unmistakable selected
state, and graceful handling when there are more locations than fit — scroll or wrap, never squash.
Reuse the pill treatment from the Money screen's toolbar rather than inventing a third style, and
remember `clip()` must precede `clickable()` or the hover highlight overhangs the rounded corners.

---

## ✅ Scope

- `sharedLogic`: `Imei` rewritten around doc-id safety; `SkuKey.build` accepts blanks **keeping empty
  segments**; `InventoryValidation` relaxed for the newly-optional fields (**cost still required**);
  `NewProduct.defaultSellingPrice` optional.
- `desktopApp`: expansion state inverted to `expanded`; brand dividers; Confirm restyle; location
  pills pass; paste-box max height; wording; Paste-more removed.
- i18n: reworded values, keys unchanged; `inventory_paste_more` deleted.
- Tests below.

## 🧪 Tests

- `Imei`: accepts 14/15/16-digit and a 20-character alphanumeric serial; rejects blank, `/`-bearing,
  `.`, `..`, `__x__`, and over-length. Trimming applied before validation.
- `SkuKey`: blank capacity yields a different key from a populated one; **capacity-only and
  colour-only yield different keys** (the collision the empty segment prevents); all-blank optional
  attributes still deterministic; blank brand or model still throws.
- `InventoryValidation`: a unit with blank capacity/colour/carrier and no selling price passes;
  **missing or zero cost still fails**.

## 🎯 Acceptance Criteria

1. A brand opens to **collapsed** models; a model opens to its units. Nothing expands that wasn't
   clicked — **including models that first appear after a filter or location change**.
2. Two models of the same name under different brands expand independently.
3. Expanded brand groups are visually separated.
4. A unit with only brand, model, location, IMEI and cost saves; capacity, colour, carrier and
   selling price may be blank and render as empty cells in the browse table and sales picker.
5. **A missing or zero cost is rejected**, with a message that says why it's needed.
6. Capacity-only and colour-only units produce **different** SKUs (paste both keys in the PR).
7. A 20-character serial is accepted; one containing `/` is rejected with a readable message, not a
   Firestore error.
8. Pasting 200 lines leaves the buttons on screen; the text scrolls inside the box.
9. No user-visible string contains "parse"; "Paste more" is gone.
10. Confirm button and location pills match the theme in light **and** dark, with hover highlights
    following their rounded corners.
11. `:desktopApp:test` and `:sharedLogic:jvmTest` green.

## 🚫 Out of scope

- Backfilling cost onto units already added.
- Editing a unit's SKU-defining attributes after the fact (re-keys the product — its own ticket).
- Android and iOS — Desktop only.

## 📚 References

- `desktopApp/.../ui/inventory/InventoryScreen.kt` — `:845` expansion state, `:1776` Confirm,
  `:795` `LocationChip`, `:3023` paste box, `:3050`/`:3061` wording
- `sharedLogic/.../util/Imei.kt` · `.../util/SkuKey.kt` · `.../usecase/InventoryValidation.kt`
- `sharedLogic/.../i18n/EnglishStrings.kt:283-288`
- `desktopApp/.../ui/money/MoneyFields.kt` — pill/field treatment to reuse

## 🤖 Kickoff prompt

> Read this ticket. Nine Inventory fixes, all decisions settled. The real bug is #1: `collapsedModels`
> is never seeded, so every model is permanently expanded — invert the state to **expanded** keys
> rather than seeding the second set, or models appearing after a filter change stay broken. Then
> make capacity/colour/carrier/selling-price optional (**cost stays required — it becomes COGS**),
> keeping **empty segments** in the SKU key or two different SKUs silently merge. Swap the IMEI
> length rule for document-id safety, cap the paste box, and do the three UI passes.
