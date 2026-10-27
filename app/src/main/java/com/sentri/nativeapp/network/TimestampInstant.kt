package com.sentri.nativeapp.network

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Compare offset timestamps at arbitrary fractional precision while retaining their original text. */
object TimestampInstant {
    private val pattern = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})$")
    fun seconds(value: String): BigDecimal {
        TimestampAdapter.validate(value)
        val match = requireNotNull(pattern.matchEntire(value))
        val base = "${match.groupValues[1]}T${match.groupValues[2]}"
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
        }
        val whole = BigDecimal.valueOf(requireNotNull(formatter.parse(base)).time).movePointLeft(3)
        val fraction = match.groupValues[3].let { if (it.isEmpty()) BigDecimal.ZERO else BigDecimal("0.$it") }
        val zone = match.groupValues[4]
        if (zone == "Z") return whole + fraction
        val digits = zone.substring(1).replace(":", "")
        val offset = BigDecimal(digits.take(2).toInt() * 3600 + digits.takeLast(2).toInt() * 60)
        return whole + fraction + if (zone[0] == '+') -offset else offset
    }
}
