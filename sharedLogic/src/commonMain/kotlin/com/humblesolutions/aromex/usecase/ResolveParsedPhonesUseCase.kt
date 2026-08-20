package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.ParsedPhone
import com.humblesolutions.aromex.model.ResolveResult
import com.humblesolutions.aromex.model.ResolvedPhone
import com.humblesolutions.aromex.repository.AttributeRepository
import com.humblesolutions.aromex.util.AttributeName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Maps the pure [ParsedPhone] name strings from
 * [com.humblesolutions.aromex.util.parseSickw] onto real managed-vocabulary
 * [AttributeRef]s, **find-or-create, case-insensitive** — the bridge between the pure
 * parser and stored inventory vocabulary (ticket #53).
 *
 * Attributes are resolved **in parallel**:
 * - Step 1: BRAND, CAPACITY, COLOR, CARRIER (all independent) → parallel
 * - Step 2: MODEL (needs resolved brand IDs from step 1) → parallel
 * - Step 3: Pure local map → build [ResolvedPhone] list
 *
 * All mutable state is merged after each [awaitAll] on the main coroutine, so no
 * shared mutable access happens across concurrent workers.
 *
 * Requires MANAGE on inventory (it can mint vocabulary).
 */
class ResolveParsedPhonesUseCase(
    private val attributeRepository: AttributeRepository,
) {
    suspend fun execute(
        session: com.humblesolutions.aromex.model.UserSession,
        parsed: List<ParsedPhone>,
        known: List<AttributeValue>,
    ): ResolveResult {
        requireInventoryManage(session)
        if (parsed.isEmpty()) return ResolveResult(phones = emptyList(), newlyCreatedIds = emptySet())

        val activeKnown = known.filter { it.isActive }
        val newlyCreated = mutableSetOf<String>()
        // cacheKey("brand", null, "apple") → "brand::apple" — populated after each step.
        val resolvedIds = mutableMapOf<String, String>()

        fun cacheKey(type: AttributeType, name: String, parentId: String?) =
            "${type.wire}:${parentId.orEmpty()}:${AttributeName.matchKey(name)}"

        fun matchInKnown(type: AttributeType, name: String, parentId: String?): AttributeValue? {
            val matchKey = AttributeName.matchKey(name)
            return activeKnown.firstOrNull {
                it.type == type && AttributeName.matchKey(it.name) == matchKey &&
                    (type != AttributeType.MODEL || it.parentId == parentId)
            }
        }

        // Returns (cacheKey, resolvedId) — no shared mutable state written inside.
        suspend fun resolveUnique(type: AttributeType, name: String, parentId: String?): Pair<String, String> {
            val key = cacheKey(type, name, parentId)
            val existing = matchInKnown(type, name, parentId)
            val id = existing?.attributeId ?: attributeRepository.addAttribute(type, name, parentId)
            return key to id
        }

        // Deduplicated task list for one attribute type.
        fun tasksFor(
            type: AttributeType,
            rawOf: (ParsedPhone) -> String,
            parentIdOf: (ParsedPhone) -> String?,
        ): List<Triple<AttributeType, String, String?>> =
            parsed.mapNotNull { phone ->
                val name = AttributeName.normalize(rawOf(phone))
                if (name.isBlank()) null else Triple(type, name, parentIdOf(phone))
            }.distinctBy { (t, n, p) -> cacheKey(t, n, p) }

        // Step 1: BRAND, CAPACITY, COLOR, CARRIER — no interdependencies, all in parallel.
        val step1Tasks = listOf(
            tasksFor(AttributeType.BRAND,    { it.brand },    { null }),
            tasksFor(AttributeType.CAPACITY, { it.capacity }, { null }),
            tasksFor(AttributeType.COLOR,    { it.color },    { null }),
            tasksFor(AttributeType.CARRIER,  { it.carrier },  { null }),
        ).flatten()

        coroutineScope {
            step1Tasks.map { (type, name, parentId) ->
                async { resolveUnique(type, name, parentId) }
            }.awaitAll()
        }.forEach { (key, id) ->
            resolvedIds[key] = id
            if (activeKnown.none { it.attributeId == id }) newlyCreated += id
        }

        // Step 2: MODEL — needs brand IDs from step 1, then all models in parallel.
        val step2Tasks = tasksFor(
            type = AttributeType.MODEL,
            rawOf = { it.model },
            parentIdOf = { phone ->
                val brandName = AttributeName.normalize(phone.brand)
                if (brandName.isBlank()) null
                else resolvedIds[cacheKey(AttributeType.BRAND, brandName, null)]
            },
        ).filter { (_, _, parentId) -> parentId != null }

        coroutineScope {
            step2Tasks.map { (type, name, parentId) ->
                async { resolveUnique(type, name, parentId) }
            }.awaitAll()
        }.forEach { (key, id) ->
            resolvedIds[key] = id
            if (activeKnown.none { it.attributeId == id }) newlyCreated += id
        }

        // Step 3: Pure local mapping — no suspension, no shared-state risk.
        fun lookupRef(type: AttributeType, rawName: String, parentId: String?): AttributeRef? {
            val name = AttributeName.normalize(rawName)
            if (name.isBlank()) return null
            val id = resolvedIds[cacheKey(type, name, parentId)] ?: return null
            val displayName = matchInKnown(type, name, parentId)?.name ?: name
            return AttributeRef(id, displayName)
        }

        val resolved = parsed.map { phone ->
            val attrs = linkedMapOf<AttributeType, AttributeRef>()
            val brandName = AttributeName.normalize(phone.brand)
            val brandId = if (brandName.isBlank()) null
                          else resolvedIds[cacheKey(AttributeType.BRAND, brandName, null)]

            lookupRef(AttributeType.BRAND, phone.brand, null)?.let { attrs[AttributeType.BRAND] = it }
            lookupRef(AttributeType.MODEL, phone.model, brandId)?.let { attrs[AttributeType.MODEL] = it }
            lookupRef(AttributeType.CAPACITY, phone.capacity, null)?.let { attrs[AttributeType.CAPACITY] = it }
            lookupRef(AttributeType.COLOR, phone.color, null)?.let { attrs[AttributeType.COLOR] = it }
            lookupRef(AttributeType.CARRIER, phone.carrier, null)?.let { attrs[AttributeType.CARRIER] = it }

            ResolvedPhone(imei = phone.imei, attributes = attrs, rawText = phone.rawText)
        }

        return ResolveResult(phones = resolved, newlyCreatedIds = newlyCreated)
    }
}
