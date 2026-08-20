package com.humblesolutions.aromex.model

/**
 * The outcome of parsing a block of SICKW lookup text (see
 * [com.humblesolutions.aromex.util.SickwParser]). Deliberately **never silently drops**
 * a block: everything the parser could turn into a phone is in [phones]; everything it
 * could not read (non-iPhone, no recognisable model, …) is preserved verbatim in
 * [unreadable] so the UI can show it to fix or drop.
 */
data class SickwParseResult(
    val phones: List<ParsedPhone> = emptyList(),
    val unreadable: List<UnreadableBlock> = emptyList(),
)

/**
 * One phone the parser recognised from a SICKW block. Fields are **normalised display
 * name strings** (our vocab casing — "Apple", "iPhone 14", "256GB", "Purple",
 * "Unlocked"), NOT [AttributeRef]s: the parser is pure and knows nothing of the stored
 * vocabulary. Turning these names into real [AttributeRef]s (match-or-create) is the job
 * of [com.humblesolutions.aromex.usecase.ResolveParsedPhonesUseCase].
 *
 * [brand] is always "Apple" in v1 (iPhone-only). [carrier] is "Unlocked" for an
 * unlocked phone or the locked carrier's name. Any of [capacity]/[color]/[carrier] may
 * be blank when the SICKW block did not carry that field; [model] and [imei] are always
 * present for a phone that landed here (a block missing either is [UnreadableBlock]).
 */
data class ParsedPhone(
    val imei: String,
    val brand: String,
    val model: String,
    val capacity: String = "",
    val color: String = "",
    val carrier: String = "",
    /** The raw SICKW block this phone was parsed from, kept for display/debugging. */
    val rawText: String = "",
)

/**
 * A SICKW block the parser could not turn into a phone — kept verbatim so the UI can
 * surface it ("couldn't read") rather than dropping it. [reason] is a short machine
 * tag ([Reason]) the UI maps to a localized message.
 */
data class UnreadableBlock(
    val rawText: String,
    val reason: Reason,
) {
    enum class Reason {
        /** No `Model Description` / model token could be found in the block. */
        NO_MODEL,

        /** A model was found but it is not an iPhone (Android/other — manual entry). */
        NOT_IPHONE,

        /** No valid `IMEI:` line in the block. */
        NO_IMEI,
    }
}
