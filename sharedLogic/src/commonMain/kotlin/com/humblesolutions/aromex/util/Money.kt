package com.humblesolutions.aromex.util

/**
 * Money helpers that operate on **decimal strings** only.
 *
 * Per CLAUDE.md, money is never a Double/Float in shared or UI code. These helpers
 * inspect sign and validity by string inspection — they never parse to a floating
 * point number, so there is no rounding drift.
 */
object Money {
    private val SIGNED_DECIMAL = Regex("^[+-]?\\d+(\\.\\d+)?$")
    private val UNSIGNED_DECIMAL = Regex("^\\d+(\\.\\d+)?$")

    /**
     * Sign of a decimal money string: `1` positive, `-1` negative, `0` zero.
     * An unparseable string is treated as `0` (safe default → SETTLED for balances).
     */
    fun signOf(value: String): Int {
        val t = value.trim()
        if (!SIGNED_DECIMAL.matches(t)) return 0
        val negative = t.startsWith("-")
        val digitsOnly = t.trimStart('+', '-').replace(".", "")
        val allZero = digitsOnly.all { it == '0' }
        return when {
            allZero -> 0
            negative -> -1
            else -> 1
        }
    }

    /** True when [value] is a valid decimal that equals zero (e.g. "0", "0.00", "-0.0"). */
    fun isZero(value: String): Boolean = signOf(value) == 0

    /**
     * True when [value] is a well-formed **unsigned** decimal — zero included.
     *
     * Distinct from [isZero], which answers *"does this equal zero"* and says yes to anything it
     * can't parse. A validator asking *"is this a number at all"* must not accept `"abc"` as a
     * well-behaved zero.
     */
    fun isValidUnsignedDecimal(value: String): Boolean = UNSIGNED_DECIMAL.matches(value.trim())

    /** True when [value] is a valid, **unsigned** decimal strictly greater than zero. */
    fun isValidPositiveDecimal(value: String): Boolean {
        val t = value.trim()
        if (!isValidUnsignedDecimal(t)) return false
        return signOf(t) > 0
    }

    /** The absolute value of a decimal money string (drops a leading sign). */
    fun abs(value: String): String = value.trim().trimStart('+', '-')

    /**
     * Flips the sign of a decimal money string, normalizing zero to `"0"` (never `"-0"`). Used for
     * the statement's signed ledger arithmetic (ticket #109): HL's running balance can be negative
     * when a party is in credit, so deltas between rows are genuinely signed.
     */
    fun negate(value: String): String {
        if (isZero(value)) return "0"
        val t = value.trim()
        return if (t.startsWith("-")) t.substring(1) else "-$t"
    }

    /**
     * `a + b` for two **signed** decimal strings. Delegates to the unsigned [add]/[subtract]/
     * [compare] on the magnitudes, choosing the result sign from the operands — so no floating
     * point is ever involved (CLAUDE.md forbids it for money).
     */
    fun signedAdd(a: String, b: String): String {
        val sa = signOf(a)
        val sb = signOf(b)
        if (sa == 0) return normalizeSigned(b)
        if (sb == 0) return normalizeSigned(a)
        val au = abs(a)
        val bu = abs(b)
        if (sa == sb) {
            val mag = add(au, bu)
            return if (sa < 0) negate(mag) else mag
        }
        // Opposite signs → subtract the smaller magnitude from the larger, keep the larger's sign.
        val cmp = compare(au, bu)
        if (cmp == 0) return "0"
        return if (cmp > 0) {
            val mag = subtract(au, bu)
            if (sa < 0) negate(mag) else mag
        } else {
            val mag = subtract(bu, au)
            if (sb < 0) negate(mag) else mag
        }
    }

    /** `a − b` for two **signed** decimal strings. */
    fun signedSubtract(a: String, b: String): String = signedAdd(a, negate(b))

    /**
     * Normalizes a (possibly signed) decimal to exactly two places, half-up — e.g. `"475"` →
     * `"475.00"`, `"-12.5"` → `"-12.50"`. Used to render statement money consistently (ticket
     * #109); pure string arithmetic, no float.
     */
    fun round2(value: String): String {
        if (isZero(value)) return "0.00"
        val mag = multiplyRate(abs(value), "1")
        return if (signOf(value) < 0) "-$mag" else mag
    }

    /** Re-runs a signed string through [negate] twice to normalize (`"-0"`/`"+5"` → `"0"`/`"5"`). */
    private fun normalizeSigned(value: String): String {
        if (isZero(value)) return "0"
        val t = value.trim()
        return if (t.startsWith("+")) t.substring(1) else t
    }

    /**
     * Sum of non-negative decimal money strings, as a normalized decimal string
     * (e.g. `["700", "700", "1000.50"] → "2400.50"`). Pure string arithmetic — never
     * parses to a float, so there is no rounding drift. An entry that is not a valid
     * unsigned decimal counts as `0` (validate with [isValidPositiveDecimal] first).
     */
    fun sum(values: List<String>): String =
        values.fold("0") { acc, v -> add(acc, v) }

    /** `a + b` for two non-negative decimal strings; invalid input is treated as `0`. */
    fun add(a: String, b: String): String {
        val (ai, af) = split(a)
        val (bi, bf) = split(b)
        val fracLen = maxOf(af.length, bf.length)
        var carry = 0
        var fracDigits = ""
        if (fracLen > 0) {
            val fracSum = addDigits(af.padEnd(fracLen, '0'), bf.padEnd(fracLen, '0'))
            if (fracSum.length > fracLen) {
                carry = 1
                fracDigits = fracSum.substring(1)
            } else {
                fracDigits = fracSum.padStart(fracLen, '0')
            }
        }
        val intSum = addDigits(ai, bi, carry).trimStart('0').ifEmpty { "0" }
        return if (fracLen == 0) intSum else "$intSum.$fracDigits"
    }

    /**
     * Compares two non-negative decimal strings numerically: negative when `a < b`,
     * `0` when equal, positive when `a > b`. Pure string comparison — no float.
     */
    fun compare(a: String, b: String): Int {
        val (ai0, af) = split(a)
        val (bi0, bf) = split(b)
        val ai = ai0.trimStart('0').ifEmpty { "0" }
        val bi = bi0.trimStart('0').ifEmpty { "0" }
        if (ai.length != bi.length) return ai.length.compareTo(bi.length)
        ai.compareTo(bi).let { if (it != 0) return it }
        val n = maxOf(af.length, bf.length)
        return af.padEnd(n, '0').compareTo(bf.padEnd(n, '0'))
    }

    /** True when non-negative decimal [a] is less than or equal to [b]. */
    fun lessThanOrEqual(a: String, b: String): Boolean = compare(a, b) <= 0

    /**
     * `a − b` for two non-negative decimal strings, as a normalized decimal string. The
     * result is **clamped at zero** when `a < b` (callers validate `a ≥ b`, e.g.
     * `unitPrice ≥ lineDiscount`). Pure string subtraction — never parses to a float.
     */
    fun subtract(a: String, b: String): String {
        if (lessThanOrEqual(a, b)) return "0"
        val (ai, af) = split(a)
        val (bi, bf) = split(b)
        val fracLen = maxOf(af.length, bf.length)
        val aDigits = ai + af.padEnd(fracLen, '0')
        val bDigits = bi + bf.padEnd(fracLen, '0')
        return formatFromDigits(subDigits(aDigits, bDigits), fracLen)
    }

    /**
     * `amount × rate`, rounded **half-up to 2 decimal places** — used to compute a tax leg
     * from a tax-exclusive base and a rate string (e.g. `multiplyRate("704.00", "0.05") →
     * "35.20"`). Both inputs are non-negative decimal strings; pure string arithmetic, no
     * float, so there is no rounding drift.
     */
    fun multiplyRate(amount: String, rate: String): String {
        val (ai, af) = split(amount)
        val (ri, rf) = split(rate)
        val product = mulDigits((ai + af).stripLeadingZeros(), (ri + rf).stripLeadingZeros())
        return roundHalfUp(product, af.length + rf.length, 2)
    }

    /**
     * `amount ÷ divisor`, rounded **half-up to [scale] decimal places** — the inverse of
     * [multiplyRate], added for tax-inclusive pricing (ticket #106) where the tax-exclusive base
     * is recovered as `grandTotal ÷ (1 + Σ rates)`. Both inputs are non-negative decimal strings;
     * pure string long division, **never** parses to a float (the one thing `CLAUDE.md` forbids
     * outright for money), so there is no rounding drift.
     *
     * @throws IllegalArgumentException when [divisor] is zero — an undefined operation and always a
     *   caller bug (the tax divisor is `1 + Σ rates ≥ 1`, never zero).
     */
    fun divide(amount: String, divisor: String, scale: Int = 2): String {
        require(!isZero(divisor)) { "Cannot divide by zero" }
        val (ai, af) = split(amount)
        val (di, df) = split(divisor)
        val aDigits = (ai + af).stripLeadingZeros() // A, where amount = A / 10^af.length
        val dDigits = (di + df).stripLeadingZeros() // D, where divisor = D / 10^df.length
        // amount ÷ divisor = (A · 10^df) / (D · 10^af). Scale the numerator by 10^(scale+1) so the
        // integer quotient carries (scale+1) implied fraction digits — the extra guard digit drives
        // the half-up round.
        val numerator = aDigits + "0".repeat(df.length + scale + 1)
        val denominator = dDigits + "0".repeat(af.length)
        val scaled = divDigits(numerator, denominator)
        return roundHalfUp(scaled, scale + 1, scale)
    }

    /** Compares two unsigned integer digit strings numerically. */
    private fun cmpDigits(a0: String, b0: String): Int {
        val a = a0.stripLeadingZeros()
        val b = b0.stripLeadingZeros()
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }

    /**
     * Schoolbook long division of unsigned integer digit strings, returning the **floored** integer
     * quotient. Assumes [divisor] is non-zero (guarded by [divide]).
     */
    private fun divDigits(dividend: String, divisor: String): String {
        val dvd = dividend.stripLeadingZeros()
        val q = StringBuilder()
        var rem = "0"
        for (ch in dvd) {
            rem = (rem + ch).stripLeadingZeros() // rem·10 + next digit
            var x = 9
            while (x > 0 && cmpDigits(mulDigits(divisor, x.toString()), rem) > 0) x--
            if (x > 0) rem = subDigits(rem, mulDigits(divisor, x.toString()))
            q.append('0' + x)
        }
        return q.toString().stripLeadingZeros()
    }

    /** Splits a valid unsigned decimal into (integer digits, fraction digits); invalid → ("0", ""). */
    private fun split(value: String): Pair<String, String> {
        val t = value.trim()
        if (!UNSIGNED_DECIMAL.matches(t)) return "0" to ""
        val dot = t.indexOf('.')
        return if (dot < 0) t to "" else t.substring(0, dot) to t.substring(dot + 1)
    }

    /** Schoolbook addition of two right-aligned digit strings (+ optional carry-in). */
    private fun addDigits(a: String, b: String, carryIn: Int = 0): String {
        val sb = StringBuilder()
        var i = a.length - 1
        var j = b.length - 1
        var carry = carryIn
        while (i >= 0 || j >= 0 || carry > 0) {
            val da = if (i >= 0) a[i] - '0' else 0
            val db = if (j >= 0) b[j] - '0' else 0
            val s = da + db + carry
            sb.append('0' + (s % 10))
            carry = s / 10
            i--
            j--
        }
        return if (sb.isEmpty()) "0" else sb.reverse().toString()
    }

    /** Drops leading zeros from a digit string, keeping at least `"0"`. */
    private fun String.stripLeadingZeros(): String = trimStart('0').ifEmpty { "0" }

    /** Schoolbook subtraction of two unsigned integer digit strings; assumes `a ≥ b`. */
    private fun subDigits(a0: String, b0: String): String {
        val n = maxOf(a0.length, b0.length)
        val a = a0.padStart(n, '0')
        val b = b0.padStart(n, '0')
        val sb = StringBuilder()
        var borrow = 0
        for (i in n - 1 downTo 0) {
            var d = (a[i] - '0') - (b[i] - '0') - borrow
            if (d < 0) {
                d += 10
                borrow = 1
            } else {
                borrow = 0
            }
            sb.append('0' + d)
        }
        return sb.reverse().toString().stripLeadingZeros()
    }

    /** Schoolbook multiplication of two unsigned integer digit strings. */
    private fun mulDigits(a: String, b: String): String {
        if (a == "0" || b == "0") return "0"
        val res = IntArray(a.length + b.length)
        for (i in a.length - 1 downTo 0) {
            for (j in b.length - 1 downTo 0) {
                val sum = (a[i] - '0') * (b[j] - '0') + res[i + j + 1]
                res[i + j + 1] = sum % 10
                res[i + j] += sum / 10
            }
        }
        val sb = StringBuilder()
        for (d in res) if (!(sb.isEmpty() && d == 0)) sb.append('0' + d)
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    /**
     * Formats an unsigned integer digit string [digits] that carries [fracLen] implied
     * fractional digits into a normalized decimal string (e.g. `("3520", 2) → "35.20"`).
     */
    private fun formatFromDigits(digits: String, fracLen: Int): String {
        if (fracLen == 0) return digits.stripLeadingZeros()
        val padded = digits.padStart(fracLen + 1, '0')
        val intPart = padded.substring(0, padded.length - fracLen).stripLeadingZeros()
        val fracPart = padded.substring(padded.length - fracLen)
        return "$intPart.$fracPart"
    }

    /**
     * Rounds an unsigned integer digit string [digits] (carrying [fracLen] implied
     * fractional digits) to [scale] decimal places, **half-up**, returning a normalized
     * decimal string.
     */
    private fun roundHalfUp(digits: String, fracLen: Int, scale: Int): String {
        if (fracLen <= scale) {
            return formatFromDigits(digits + "0".repeat(scale - fracLen), scale)
        }
        val drop = fracLen - scale
        val padded = digits.padStart(drop + 1, '0')
        var kept = padded.substring(0, padded.length - drop)
        val roundDigit = padded[padded.length - drop]
        if (roundDigit >= '5') kept = addDigits(kept, "1")
        return formatFromDigits(kept, scale)
    }
}
