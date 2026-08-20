package com.humblesolutions.aromex.util

/**
 * Formats money for display from **decimal strings only** — never a Double/Float
 * (per CLAUDE.md). Turns a company's ISO-4217 [currency] **code** (e.g. "USD",
 * "PHP") into the right symbol and prefixes it onto the amount.
 *
 * This is the single place symbols are decided — UI code must never hardcode a
 * "$" / "₱": the symbol is always derived from `session.currency`.
 *
 * The amount is passed through verbatim (already a formatted decimal string from
 * HL, e.g. "1240.00"); we only add a symbol and, optionally, a sign. For an
 * unknown currency code we fall back to the code itself as the prefix (e.g.
 * "CHF 10.00") so nothing is ever silently mis-symbolised.
 */
object MoneyFormat {

    /** Known ISO-4217 code → symbol. Extend as new company currencies ship. */
    private val SYMBOLS: Map<String, String> = mapOf(
        "USD" to "$",
        "CAD" to "$",
        "AUD" to "$",
        "PHP" to "₱",
        "INR" to "₹",
        "GBP" to "£",
        "EUR" to "€",
        "AED" to "د.إ",
        "PKR" to "₨",
    )

    /** The display symbol for a currency code, or the code itself if unknown. */
    fun symbolOf(currency: String): String =
        SYMBOLS[currency.trim().uppercase()] ?: currency.trim().uppercase()

    /**
     * Symbol + amount, e.g. `("1240.00", "USD") → "$1240.00"`. For an unknown
     * code the prefix keeps a trailing space (`"CHF 10.00"`) for readability;
     * a real symbol is glued on directly (`"$10.00"`).
     */
    fun format(amount: String, currency: String): String {
        val code = currency.trim().uppercase()
        val symbol = SYMBOLS[code]
        val value = amount.trim()
        return if (symbol != null) "$symbol$value" else "$code $value"
    }

    /**
     * Signed variant for net balances. Takes an **absolute** amount string plus
     * an explicit [positive]/[negative] intent and prefixes `+`/`-` before the
     * symbol (`"+$1240.00"`, `"-$560.00"`). A settled/zero balance shows no sign.
     */
    fun formatSigned(absoluteAmount: String, currency: String, positive: Boolean, negative: Boolean): String {
        val body = format(absoluteAmount, currency)
        return when {
            positive -> "+$body"
            negative -> "-$body"
            else -> body
        }
    }
}
