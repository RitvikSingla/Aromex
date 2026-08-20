package com.humblesolutions.aromex.util

/**
 * A country entry for the phone-number dial-code picker (ticket #37 follow-up).
 * [iso] is the ISO-3166 alpha-2 code (used to derive the flag emoji per platform),
 * [name] the English display name, [dialCode] the E.164 calling code incl. "+".
 *
 * Shared so Android and iOS render the *same* searchable list with identical
 * ordering and defaults — the picker UI is native per platform, the data is not.
 */
data class Country(
    val iso: String,
    val name: String,
    val dialCode: String,
)

object Countries {
    /** Default selection per the ticket: **Canada**. */
    const val DEFAULT_ISO: String = "CA"

    val all: List<Country> = listOf(
        Country("CA", "Canada", "+1"),
        Country("US", "United States", "+1"),
        Country("GB", "United Kingdom", "+44"),
        Country("AU", "Australia", "+61"),
        Country("PH", "Philippines", "+63"),
        Country("IN", "India", "+91"),
        Country("PK", "Pakistan", "+92"),
        Country("BD", "Bangladesh", "+880"),
        Country("AE", "United Arab Emirates", "+971"),
        Country("SA", "Saudi Arabia", "+966"),
        Country("SG", "Singapore", "+65"),
        Country("MY", "Malaysia", "+60"),
        Country("ID", "Indonesia", "+62"),
        Country("NZ", "New Zealand", "+64"),
        Country("IE", "Ireland", "+353"),
        Country("ZA", "South Africa", "+27"),
        Country("NG", "Nigeria", "+234"),
        Country("KE", "Kenya", "+254"),
        Country("EG", "Egypt", "+20"),
        Country("FR", "France", "+33"),
        Country("DE", "Germany", "+49"),
        Country("ES", "Spain", "+34"),
        Country("IT", "Italy", "+39"),
        Country("NL", "Netherlands", "+31"),
        Country("BE", "Belgium", "+32"),
        Country("SE", "Sweden", "+46"),
        Country("NO", "Norway", "+47"),
        Country("DK", "Denmark", "+45"),
        Country("FI", "Finland", "+358"),
        Country("PL", "Poland", "+48"),
        Country("PT", "Portugal", "+351"),
        Country("CH", "Switzerland", "+41"),
        Country("AT", "Austria", "+43"),
        Country("GR", "Greece", "+30"),
        Country("TR", "Türkiye", "+90"),
        Country("RU", "Russia", "+7"),
        Country("CN", "China", "+86"),
        Country("JP", "Japan", "+81"),
        Country("KR", "South Korea", "+82"),
        Country("HK", "Hong Kong", "+852"),
        Country("TH", "Thailand", "+66"),
        Country("VN", "Vietnam", "+84"),
        Country("LK", "Sri Lanka", "+94"),
        Country("NP", "Nepal", "+977"),
        Country("MX", "Mexico", "+52"),
        Country("BR", "Brazil", "+55"),
        Country("AR", "Argentina", "+54"),
        Country("CL", "Chile", "+56"),
        Country("CO", "Colombia", "+57"),
        Country("QA", "Qatar", "+974"),
        Country("KW", "Kuwait", "+965"),
        Country("BH", "Bahrain", "+973"),
        Country("OM", "Oman", "+968"),
        Country("IL", "Israel", "+972"),
    )

    val default: Country = all.first { it.iso == DEFAULT_ISO }

    fun byIso(iso: String?): Country =
        all.firstOrNull { it.iso.equals(iso, ignoreCase = true) } ?: default

    /**
     * Best-effort match of a stored phone string to a country by its dial-code
     * prefix (longest match wins so "+1" doesn't shadow "+971"). Falls back to
     * the [default] when nothing matches.
     */
    fun byDialPrefix(phone: String?): Country {
        val trimmed = phone?.trim().orEmpty()
        if (!trimmed.startsWith("+")) return default
        return all
            .filter { trimmed.startsWith(it.dialCode) }
            .maxByOrNull { it.dialCode.length }
            ?: default
    }
}
