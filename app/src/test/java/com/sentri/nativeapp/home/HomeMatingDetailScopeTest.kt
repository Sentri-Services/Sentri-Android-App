package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.production.MatingPig
import com.sentri.nativeapp.production.MatingTask
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPigLocation
import com.sentri.nativeapp.production.TaskPigReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeMatingDetailScopeTest {
    private val places = HomePlaces.from(Locations(listOf(
        FarmUnit("u7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"),
        FarmUnit("u8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production")), emptyList()))
    private val metadata = TaskMetadata("task", "farm", ProductionTaskKind.MATING,
        ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
        TaskBatch("batch", "34", null, null, emptyList()), null,
        listOf(TaskPigReference("p7", false, "E-7", null, "Z-A-7-P1", null)),
        emptyList(), emptyList(), emptyList())
    private val scoped = HomeTaskScopeProjector.project(listOf(metadata), places, 1).visible.single()

    private fun detail(batch: String = "batch", code: String = "Z-A-7-P1", extra: Boolean = false) =
        MatingTask("task", "farm", batch, "RUNNING", listOf(
            MatingPig("p7", "E-7", null, false, null, emptyList(),
                TaskPigLocation(code, if (code == "Z-A-7-P1") "u7" else "u8", null, code))) +
            if (extra) listOf(MatingPig("p8", "E-8", null, false, null, emptyList(),
                TaskPigLocation("Z-A-8-P1", "u8", null, "Z-A-8-P1"))) else emptyList())

    @Test fun matchingTaskCanBeShown() {
        assertNotNull(consistentMatingDetail(scoped, detail(), places, 1))
    }

    @Test fun changedBatchMemberOrUnitRequiresRefresh() {
        assertNull(consistentMatingDetail(scoped, detail(batch = "other"), places, 1))
        assertNull(consistentMatingDetail(scoped, detail(code = "Z-A-8-P1"), places, 1))
        assertNull(consistentMatingDetail(scoped, detail(extra = true), places, 1))
    }
}
