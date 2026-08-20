package com.humblesolutions.aromex.i18n

object LocalizationRegistry {
    private val tables: Map<String, Map<String, String>> = mapOf(
        "en" to EnglishStrings.map,
    )

    fun get(code: String, key: String): String =
        tables[code]?.get(key)
            ?: tables["en"]?.get(key)
            ?: key

    fun format(code: String, key: String, args: List<Any?>): String {
        var result = get(code, key)
        args.forEachIndexed { index, arg ->
            result = result.replace("{$index}", arg?.toString() ?: "")
        }
        return result
    }

    fun plural(code: String, baseKey: String, count: Int, args: List<Any?>): String {
        val suffix = if (count == 1) "_one" else "_other"
        return format(code, baseKey + suffix, listOf(count) + args)
    }
}
