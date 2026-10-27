package com.sentri.nativeapp.home

import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.network.FarmTimezone
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class HomeUpcomingDay(val dateKey: String, val dateAnchorMillis: Long,
    val starts: List<HomeUnitWorkItem>)
internal data class HomeUpcomingSchedule(val timezone: String, val days: List<HomeUpcomingDay>) {
    val totalStarts: Int get() = days.sumOf { it.starts.size }

    companion object {
        /** Only future, unstarted instances with explicit start times enter the unit's fixed seven days. */
        fun build(work: List<HomeUnitWorkItem>, timezone: String?, nowMillis: Long): HomeUpcomingSchedule? {
            val zone = FarmTimezone.zone(timezone) ?: return null
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
            val cursor = Calendar.getInstance(zone).apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_MONTH, 1)
            }
            val days = (0..6).map {
                val day = HomeUpcomingDay(date.format(Date(cursor.timeInMillis)), cursor.timeInMillis,
                    emptyList())
                cursor.add(Calendar.DAY_OF_MONTH, 1)
                day
            }
            val keys = days.map(HomeUpcomingDay::dateKey).toSet()
            val starts = work.asSequence().filter(HomeUnitWorkItem::scheduled).mapNotNull { item ->
                val millis = item.startAt?.let { value -> runCatching {
                    TimestampInstant.seconds(value).movePointRight(3).toLong()
                }.getOrNull() } ?: return@mapNotNull null
                if (millis <= nowMillis) return@mapNotNull null
                item to date.format(Date(millis))
            }.filter { it.second in keys }.groupBy({ it.second }, { it.first })
            return HomeUpcomingSchedule(zone.id,
                days.map { it.copy(starts = starts[it.dateKey].orEmpty()) })
        }
    }
}
