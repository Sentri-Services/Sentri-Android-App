package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.production.EstrusPig
import com.sentri.nativeapp.production.EstrusTask
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPigLocation
import com.sentri.nativeapp.production.TaskPigReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEstrusDetailScopeTest {
    private val places = HomePlaces.from(Locations(listOf(
        FarmUnit("u7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"),
        FarmUnit("u8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing",
            "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production")), emptyList()))
    private val metadata = TaskMetadata("task", "farm", ProductionTaskKind.ESTRUS,
        ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
        TaskBatch("batch", "34", null, null, emptyList()), null,
        listOf(TaskPigReference("p7", false, "E-7", null, "Z-A-7-P1", null),
            TaskPigReference("p8", false, "E-8", null, "Z-A-8-P1", null)),
        emptyList(), emptyList(), emptyList())
    private val catalog = HomeTaskScopeProjector.project(listOf(metadata), places, 1).visible.single()
    private val detail = EstrusTask("task", "farm", "batch", "RUNNING", listOf(
        EstrusPig("p7", "E-7", null, false, null, null,
            location = TaskPigLocation("Z-A-7-P1", "u7", "Z-A-7", "Z-A-7-P1")),
        EstrusPig("p8", "E-8", null, false, null, null,
            location = TaskPigLocation("Z-A-8-P1", "u8", "Z-A-8", "Z-A-8-P1"))))

    @Test fun currentMembershipKeepsScopeButChangedPigOrUnitRequiresHomeRefresh() {
        assertNotNull(consistentEstrusDetail(catalog, detail, places, 1))
        assertNull(consistentEstrusDetail(catalog, detail.copy(batchId = "other"), places, 1))
        assertNull(consistentEstrusDetail(catalog, detail.copy(pigs = detail.pigs.dropLast(1)), places, 1))
        val moved = detail.pigs[1].copy(location = TaskPigLocation("Z-A-7-P2", "u7", "Z-A-7", "Z-A-7-P2"))
        assertNull(consistentEstrusDetail(catalog, detail.copy(pigs = listOf(detail.pigs[0], moved)), places, 1))
    }
}
