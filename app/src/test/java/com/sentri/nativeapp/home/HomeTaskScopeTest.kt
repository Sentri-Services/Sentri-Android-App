package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPigReference
import com.sentri.nativeapp.production.TaskPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTaskScopeTest {
    private val units = listOf(
        FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing", "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
        FarmUnit("unit-8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing", "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
        FarmUnit("unit-9", "farm", "九单元", "Unit 9", "Z-B-9", "AnimalHousing", "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone"),
    )
    private val places = HomePlaces.from(Locations(units, emptyList()))
    private fun task(id: String, vararg locationCodes: String?, status: ProductionTaskStatus = ProductionTaskStatus.RUNNING,
        startedAt: String? = null) = TaskMetadata(id, "farm", ProductionTaskKind.PREGNANCY, status,
        null, null, null, null, null, null, TaskBatch("batch-$id", id, null, null, emptyList()),
        startedAt?.let { TaskPlan(it, 3) }, locationCodes.mapIndexed { index, code -> TaskPigReference("$id-$index", false, null, null, code, null) },
        emptyList(), emptyList(), emptyList())

    @Test fun `complete current locations keep one cross unit task in each matching scope`() {
        val scope = HomeTaskScopeProjector.project(listOf(task("t", "Z-A-7-B2", "Z-A-8-C1")), places, 1_000)
        assertEquals(listOf("t"), scope.inSection("section-a").map { it.task.id })
        assertEquals(listOf("t"), scope.inUnit("unit-7").map { it.task.id })
        assertEquals(setOf("unit-7", "unit-8"), scope.visible.single().unitIds)
        assertEquals(mapOf("t-0" to "unit-7", "t-1" to "unit-8"), scope.visible.single().pigUnitIds)
    }

    @Test fun `missing or unmatched member hides whole task instead of understating its count`() {
        val scope = HomeTaskScopeProjector.project(listOf(task("missing", "Z-A-7-B2", null),
            task("moved", "Z-A-7-B2", "Z-C-3-A1"), task("empty")), places, 1_000)
        assertEquals(emptyList<HomeScopedTask>(), scope.visible)
        assertEquals(listOf("missing", "moved", "empty"), scope.withoutReliableScope)
    }

    @Test fun `future scheduled task is withheld until its start timestamp`() {
        val scope = HomeTaskScopeProjector.project(listOf(task("later", "Z-B-9-P1", status = ProductionTaskStatus.SCHEDULED,
            startedAt = "2026-10-21T00:00:00+08:00")), places, 1_792_486_800_000)
        assertEquals(listOf("later"), scope.notYetStarted)
        assertEquals(emptyList<HomeScopedTask>(), scope.visible)
    }
}
