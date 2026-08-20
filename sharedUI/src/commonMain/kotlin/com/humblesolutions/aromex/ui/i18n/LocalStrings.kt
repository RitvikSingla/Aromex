package com.humblesolutions.aromex.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.humblesolutions.aromex.i18n.LocalizationRegistry

class StringProvider(val code: String) {
    fun get(key: String): String = LocalizationRegistry.get(code, key)
    fun format(key: String, args: List<Any?>): String =
        LocalizationRegistry.format(code, key, args)
    fun plural(baseKey: String, count: Int, args: List<Any?>): String =
        LocalizationRegistry.plural(code, baseKey, count, args)
}

val LocalStrings = staticCompositionLocalOf<StringProvider> {
    error("LocalStrings not provided. Wrap your UI in CompositionLocalProvider(LocalStrings provides ...).")
}

@Composable
@ReadOnlyComposable
fun strings(key: String): String = LocalStrings.current.get(key)

@Composable
@ReadOnlyComposable
fun strings(key: String, vararg args: Any?): String =
    LocalStrings.current.format(key, args.toList())

@Composable
@ReadOnlyComposable
fun plural(baseKey: String, count: Int, vararg args: Any?): String =
    LocalStrings.current.plural(baseKey, count, args.toList())
