package com.sentri.nativeapp.network

import java.util.TimeZone

/** Reject unknown IDs instead of letting TimeZone silently fall back to GMT. */
object FarmTimezone {
    private val available by lazy { TimeZone.getAvailableIDs().toSet() }
    fun validated(value: String?): String? = value?.takeIf { it in available }
    fun zone(value: String?): TimeZone? = validated(value)?.let(TimeZone::getTimeZone)
}
