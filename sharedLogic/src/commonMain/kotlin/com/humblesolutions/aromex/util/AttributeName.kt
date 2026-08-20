package com.humblesolutions.aromex.util

/**
 * Canonical handling of managed-vocabulary names, defined **once in shared code** so
 * every platform folds them identically — the guarantee under the add-new-inline picker
 * that keeps SKU grouping reliable (Brief #41 `[hard]` consistency steer).
 *
 * - [normalize] is the **display** form: trimmed, internal whitespace collapsed, original
 *   case preserved. This is what gets stored + shown ("iPhone 15", "Apple").
 * - [matchKey] is the **dedupe** key: the normalized name, case-folded. `"Apple"`,
 *   `"apple"`, and `" APPLE "` all share one key, so add-new can't create a case-variant
 *   duplicate even if the UI picker fails to surface the existing value.
 */
object AttributeName {
    private val WHITESPACE = Regex("\\s+")

    /** Display form — trimmed, internal whitespace collapsed to single spaces, case kept. */
    fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /** Case-insensitive dedupe key — [normalize], whitespace stripped, then lowercased (locale-invariant).
     *  Strips all spaces so "128GB" and "128 GB" share one key and can't fragment SKUs. */
    fun matchKey(raw: String): String = normalize(raw).replace(" ", "").lowercase()
}
