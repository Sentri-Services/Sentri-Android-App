package com.sentri.nativeapp.i18n

import com.sentri.nativeapp.network.TimestampAdapter
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** No rounding policy or business timezone is guessed. Both are explicit at the call site. */
class BusinessFormatter(private val language: SupportedLanguage) {
    fun number(value: BigDecimal, maximumFractionDigits: Int = value.scale().coerceAtLeast(0)): String {
        require(maximumFractionDigits in 0..340)
        return NumberFormat.getNumberInstance(language.locale).apply {
            this.maximumFractionDigits = maximumFractionDigits
            roundingMode = java.math.RoundingMode.HALF_UP
        }.format(value)
    }
    fun timestamp(value: String, zone: TimeZone): String {
        TimestampAdapter.validate(value)
        val normalized = value.replace(' ', 'T').replace(Regex("\\.\\d+"), "")
            .replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        val instant = requireNotNull(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT).apply { isLenient = false }.parse(normalized))
        return timestamp(instant.time, zone)
    }
    fun timestamp(epochMillis: Long, zone: TimeZone): String = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM, language.locale,
    ).apply { timeZone = zone }.format(Date(epochMillis))
    fun name(chinese: String?, english: String?, fallback: String): String =
        (if (language == SupportedLanguage.CHINESE) listOf(chinese, english) else listOf(english, chinese))
            .firstOrNull { !it.isNullOrBlank() } ?: fallback
}
