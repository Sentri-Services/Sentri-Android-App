package com.sentri.nativeapp.home

import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPlan
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeUpcomingScheduleTest {
    @Test fun farmCalendarPlacesOnlyExplicitUnstartedTasksInTomorrowThroughSixMoreDays() {
        val now = Instant.parse("2026-10-25T15:30:00Z").toEpochMilli()
        val work = listOf(
            item("tomorrow", ProductionTaskStatus.SCHEDULED, "2026-10-25T16:10:00Z"),
            item("last", ProductionTaskStatus.SCHEDULED, "2026-11-01T15:00:00Z"),
            item("today", ProductionTaskStatus.SCHEDULED, "2026-10-25T15:40:00Z"),
            item("beyond", ProductionTaskStatus.SCHEDULED, "2026-11-01T16:00:00Z"),
            item("running", ProductionTaskStatus.RUNNING, "2026-10-25T16:10:00Z"),
        )
        val result = HomeUpcomingSchedule.build(work, "Asia/Shanghai", now)!!
        assertEquals("2026-10-26", result.days.first().dateKey)
        assertEquals("2026-11-01", result.days.last().dateKey)
        assertEquals(listOf("production:tomorrow"), result.days.first().starts.map { it.key })
        assertEquals(listOf("production:last"), result.days.last().starts.map { it.key })
        assertEquals(2, result.totalStarts)
    }

    @Test fun missingOrInvalidFarmTimezoneNeverCreatesDeviceLocalDates() {
        val task = item("future", ProductionTaskStatus.SCHEDULED, "2026-10-26T00:00:00Z")
        assertNull(HomeUpcomingSchedule.build(listOf(task), null, 0))
        assertNull(HomeUpcomingSchedule.build(listOf(task), "invalid-zone", 0))
    }

    private fun item(id: String, status: ProductionTaskStatus, at: String): HomeUnitWorkItem =
        HomeUnitWorkItem.Production(HomeScopedTask(TaskMetadata(id, "farm",
            ProductionTaskKind.PREGNANCY, status, null, null, null, null, null, null,
            TaskBatch("batch-$id", id, null, null, emptyList()), TaskPlan(at, 1),
            emptyList(), emptyList(), emptyList(), emptyList()), setOf("unit"), setOf("section")))
}
