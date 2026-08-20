package com.humblesolutions.aromex.model

/**
 * A [ParsedPhone] whose SICKW name strings have been mapped to real managed-vocabulary
 * [AttributeRef]s (see [com.humblesolutions.aromex.usecase.ResolveParsedPhonesUseCase]).
 * Only the SKU-defining attributes SICKW provided are present in [attributes]; a blank
 * SICKW field (e.g. no colour) is simply absent, leaving that cell **must-fill** on the
 * review table. Location is never here — it is a shop-set per-unit field.
 */
data class ResolvedPhone(
    val imei: String,
    val attributes: Map<AttributeType, AttributeRef> = emptyMap(),
    val sellingPrice: String = "",
    val rawText: String = "",
)

/**
 * Output of [com.humblesolutions.aromex.usecase.ResolveParsedPhonesUseCase]: the resolved
 * [phones] plus the ids of any vocabulary values **created** during resolution (they were
 * not in the known snapshot) so the UI can tag those cells **"new"** — making a typo'd
 * value visible instead of silently minting vocabulary.
 */
data class ResolveResult(
    val phones: List<ResolvedPhone> = emptyList(),
    val newlyCreatedIds: Set<String> = emptySet(),
)
