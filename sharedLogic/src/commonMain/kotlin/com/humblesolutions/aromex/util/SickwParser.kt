package com.humblesolutions.aromex.util

import com.humblesolutions.aromex.model.ParsedPhone
import com.humblesolutions.aromex.model.SickwParseResult
import com.humblesolutions.aromex.model.UnreadableBlock

/**
 * Pure, tolerant parser for **SICKW** IMEI-lookup text (the paste-based bulk-intake flow,
 * ticket #53). Turns one-or-many pasted SICKW result blocks into [ParsedPhone]s plus any
 * [UnreadableBlock]s it could not read — **nothing is silently dropped**.
 *
 * ## Design rules (why it looks like this)
 * - **Type-based, never positional.** SICKW's `Model Description` is a comma list whose
 *   order varies by service (`IPHONE 14,ROW,256GB,PURPLE`), so we classify each token by
 *   *what it looks like* (capacity regex, colour word, `IPHONE …` model, region code to
 *   ignore) — never "the 3rd comma is capacity".
 * - **iPhone-only in v1.** Brand is inferred `Apple`; a block whose model isn't an iPhone
 *   (or has no model / no IMEI) is returned as [UnreadableBlock], not a phone.
 * - **Pure.** No platform imports, no vocabulary access. Output is normalised **name
 *   strings** in our vocab casing (`PURPLE`→`Purple`, `IPHONE 14`→`iPhone 14`); mapping
 *   those to stored attributes (match-or-create) is
 *   [com.humblesolutions.aromex.usecase.ResolveParsedPhonesUseCase]'s job.
 *
 * @return a [SickwParseResult] pinning the fixture in `SickwParserTest`.
 */
fun parseSickw(text: String): SickwParseResult {
    if (text.isBlank()) return SickwParseResult()

    val phones = mutableListOf<ParsedPhone>()
    val unreadable = mutableListOf<UnreadableBlock>()

    for (block in splitIntoBlocks(text)) {
        val raw = block.trim()
        if (raw.isEmpty()) continue
        when (val outcome = parseBlock(block)) {
            is BlockOutcome.Ok -> phones.add(outcome.phone.copy(rawText = raw))
            is BlockOutcome.Bad -> unreadable.add(UnreadableBlock(raw, outcome.reason))
        }
    }
    return SickwParseResult(phones = phones, unreadable = unreadable)
}

// ── Block splitting ──────────────────────────────────────────────────────────────

/**
 * Splits a multi-phone paste into per-phone blocks. A new block starts whenever an
 * **identity anchor** repeats — a second `Model Description` **or** a second primary
 * `IMEI:` — so both "model-led" and "IMEI-led" SICKW layouts split correctly regardless
 * of comma position or field order.
 */
private fun splitIntoBlocks(text: String): List<String> {
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    var hasModel = false
    var hasImei = false

    fun flush() {
        if (current.isNotBlank()) blocks.add(current.toString())
        current.clear()
        hasModel = false
        hasImei = false
    }

    for (line in text.lines()) {
        val key = keyOf(line)
        val isModel = key == "model description" || key == "model"
        val isPrimaryImei = key == "imei"
        if ((isModel && hasModel) || (isPrimaryImei && hasImei)) flush()
        if (isModel) hasModel = true
        if (isPrimaryImei) hasImei = true
        current.append(line).append('\n')
    }
    flush()
    return blocks
}

// ── Single-block parsing ─────────────────────────────────────────────────────────

private sealed interface BlockOutcome {
    data class Ok(val phone: ParsedPhone) : BlockOutcome
    data class Bad(val reason: UnreadableBlock.Reason) : BlockOutcome
}

private fun parseBlock(block: String): BlockOutcome {
    val fields = mutableMapOf<String, String>()
    for (line in block.lines()) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val key = line.substring(0, idx).trim().lowercase()
        val value = line.substring(idx + 1).trim()
        if (key.isNotEmpty() && key !in fields) fields[key] = value
    }

    val modelDesc = fields["model description"] ?: fields["model"]
    if (modelDesc.isNullOrBlank()) return BlockOutcome.Bad(UnreadableBlock.Reason.NO_MODEL)

    val tokens = modelDesc.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val modelToken = tokens.firstOrNull { it.contains("iphone", ignoreCase = true) }
        ?: return BlockOutcome.Bad(UnreadableBlock.Reason.NOT_IPHONE)

    val imei = extractImei(fields["imei"])
        ?: return BlockOutcome.Bad(UnreadableBlock.Reason.NO_IMEI)

    val capacity = tokens.firstOrNull { isCapacity(it) }?.let(::normalizeCapacity).orEmpty()
    val color = tokens.firstOrNull { it.lowercase() in COLORS }?.let(::toTitleCase).orEmpty()

    return BlockOutcome.Ok(
        ParsedPhone(
            imei = imei,
            brand = "Apple",
            model = normalizeModel(modelToken),
            capacity = capacity,
            color = color,
            carrier = deriveCarrier(fields),
        )
    )
}

// ── Field helpers ────────────────────────────────────────────────────────────────

/** Lowercased key of a `key: value` line, or empty string if the line has no key. */
private fun keyOf(line: String): String {
    val idx = line.indexOf(':')
    if (idx <= 0) return ""
    return line.substring(0, idx).trim().lowercase()
}

/** First 14–16 digit run in the primary IMEI value, or null (→ NO_IMEI). Ignores IMEI2/MEID. */
private fun extractImei(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return IMEI_DIGITS.find(value)?.value
}

private fun isCapacity(token: String): Boolean = CAPACITY.matches(token.trim())

/** `"256 gb"` / `"256GB"` → `"256GB"` (digits + uppercase unit, no space). */
private fun normalizeCapacity(token: String): String {
    val m = CAPACITY.matchEntire(token.trim()) ?: return token.trim()
    val digits = m.groupValues[1]
    val unit = m.groupValues[2].uppercase()
    return "$digits$unit"
}

/**
 * Carrier per the ticket: an unlocked phone → `"Unlocked"`; a locked one → its carrier
 * name. `Sim-Lock Status` governs (its `Unlocked` beats a `Locked Carrier` code line, as
 * in the fixture where `Locked Carrier: 10 - Unlock` coexists with `Sim-Lock Status:
 * Unlocked`).
 */
private fun deriveCarrier(fields: Map<String, String>): String {
    val simLock = fields["sim-lock status"] ?: fields["sim lock status"]
    if (simLock != null && simLock.contains("unlock", ignoreCase = true)) return "Unlocked"

    val locked = fields["locked carrier"]
    if (!locked.isNullOrBlank() && !locked.contains("unlock", ignoreCase = true)) {
        return cleanCarrierName(locked)
    }
    // Locked but no usable carrier name, or no lock info at all → leave blank for the user.
    return ""
}

/** Strips a leading SICKW code prefix like `"10 - AT&T"` → `"AT&T"`. */
private fun cleanCarrierName(raw: String): String {
    val stripped = CARRIER_CODE_PREFIX.replace(raw.trim(), "")
    return AttributeName.normalize(stripped)
}

// ── Normalisation to our vocab casing ────────────────────────────────────────────

/** `"IPHONE 14 PRO MAX"` → `"iPhone 14 Pro Max"`, `"IPHONE SE"` → `"iPhone SE"`. */
private fun normalizeModel(token: String): String =
    AttributeName.normalize(token).split(' ').joinToString(" ") { word ->
        when (word.lowercase()) {
            "iphone" -> "iPhone"
            "se" -> "SE"
            "max" -> "Max"
            "pro" -> "Pro"
            "plus" -> "Plus"
            "mini" -> "Mini"
            else -> if (word.any { it.isLetter() }) capitalizeWord(word) else word
        }
    }

/** `"SPACE BLACK"` → `"Space Black"`, `"PURPLE"` → `"Purple"`. */
private fun toTitleCase(token: String): String =
    AttributeName.normalize(token).split(' ').joinToString(" ") { capitalizeWord(it) }

private fun capitalizeWord(word: String): String {
    if (word.isEmpty()) return word
    return word.substring(0, 1).uppercase() + word.substring(1).lowercase()
}

// ── Constants ────────────────────────────────────────────────────────────────────

private val IMEI_DIGITS = Regex("""\d{14,16}""")
private val CAPACITY = Regex("""(\d+)\s?([GT]B)""", RegexOption.IGNORE_CASE)
private val CARRIER_CODE_PREFIX = Regex("""^\s*\d+\s*-\s*""")

/**
 * Known iPhone colour words (lowercased) — colour is matched by set membership, not
 * position, so region codes (`ROW`, `LL`, `ZP`) are never mistaken for a colour. A
 * colour SICKW emits that isn't here yields a blank colour (the user fills it in the
 * review table); add words here as new iPhone finishes appear.
 */
private val COLORS: Set<String> = setOf(
    "black", "jet black", "matte black", "space black", "space gray", "space grey",
    "white", "silver", "gold", "rose gold", "graphite", "red", "product red",
    "blue", "sierra blue", "pacific blue", "midnight", "midnight blue",
    "green", "alpine green", "midnight green", "yellow", "purple", "deep purple",
    "pink", "coral", "orange", "starlight", "natural", "natural titanium",
    "blue titanium", "white titanium", "black titanium", "desert titanium",
    "titanium", "teal", "ultramarine", "desert",
)
