package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTransferScopeTest {
    private val places = HomePlaces.from(Locations(listOf(
        FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
        FarmUnit("unit-9", "farm", "九单元", "Unit 9", "Z-B-9", "AnimalHousing",
            "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone")), emptyList()))

    private fun task(id: String, status: String = "IN_PROGRESS", plannedAt: String? = null,
        from: List<String> = listOf("Z-A-7"), to: List<String> = listOf("Z-B-9")) =
        HomeTransferTask(id, "farm", status, "PRODUCTION_SOW", plannedAt, "31", from, to)

    @Test fun `one cross-section transfer keeps one identity and direction in each unit`() {
        val scope = HomeTransferScopeProjector.project(listOf(task("move-31")), places, 1_000)
        assertEquals(listOf("move-31"), scope.inSection("section-a").map { it.task.id })
        assertEquals(listOf("move-31"), scope.inSection("section-b").map { it.task.id })
        assertEquals(HomeTransferDirection.OUT, scope.inUnit("unit-7").single().direction("unit-7"))
        assertEquals(HomeTransferDirection.IN, scope.inUnit("unit-9").single().direction("unit-9"))
    }

    @Test fun `planned future or undated and unknown unit never become partial current work`() {
        val scope = HomeTransferScopeProjector.project(listOf(
            task("future", "PLANNED", "2026-10-23T00:00:00+08:00"),
            task("undated", "PLANNED"),
            task("unknown", from = listOf("Z-A-7", "Z-X-8")),
            task("current", "PLANNED", "2026-10-20T00:00:00+08:00")),
            places, 1_792_656_000_000)
        assertEquals(listOf("current"), scope.visible.map { it.task.id })
        assertEquals(listOf("future", "undated"), scope.notYetStarted)
        assertEquals(listOf("unknown"), scope.withoutReliableScope)
    }
}
