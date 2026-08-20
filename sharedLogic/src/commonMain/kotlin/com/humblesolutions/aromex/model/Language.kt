package com.humblesolutions.aromex.model

data class Language(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val subtitle: String,
) {
    companion object {
        val English = Language(
            code = "en",
            nativeName = "English",
            englishName = "English",
            subtitle = "Choose your language",
        )

        val SUPPORTED_LANGUAGES: List<Language> = listOf(English)

        fun fromCode(code: String?): Language =
            SUPPORTED_LANGUAGES.firstOrNull { it.code == code } ?: English
    }
}
