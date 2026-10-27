package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.vaccination.VaccinationPig
import com.sentri.nativeapp.vaccination.VaccinationTargetDose
import com.sentri.nativeapp.vaccination.VaccinationTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

class HomeVaccinationScopeTest {
    private val first = FarmUnit("u1", "farm", "一单元", "Unit 1", "Z-A-1", "AnimalHousing",
        "s1", "A", "一分区", "Section 1", "Z", "生产区", "Production")
    private val second = FarmUnit("u2", "farm", "二单元", "Unit 2", "Z-B-2", "AnimalHousing",
        "s2", "B", "二分区", "Section 2", "Z", "生产区", "Production")
    private val places = HomePlaces.from(com.sentri.nativeapp.pigs.Locations(listOf(first, second), emptyList()))

    private fun task(id: String, status: String = "IN_PROGRESS", time: String? = null,
        locations: List<String?> = listOf("Z-A-1-P1", "Z-B-2-P1")): VaccinationTask {
        val targets = locations.mapIndexed { index, location ->
            val pigId = "pig-$index"
            VaccinationTargetDose("farm", id, pigId, "vaccine", "brand", 1, "PENDING", time,
                null, null, null, VaccinationPig(pigId, "farm", null, null, null, null, null,
                    "ACTIVE", location, null, null, null))
        }
        return VaccinationTask(id, "farm", "vaccine", "brand", status, time, null, null,
            null, null, null, null, "IM", null, null, "mL", 1, BigDecimal.ONE,
            0, 0, 0, locations.size, "PLAN", null, null, null, false, null, null, null, targets)
    }

    @Test fun crossSectionTaskRemainsOneInstanceAndCountsTargetsInEachUnit() {
        val result = HomeVaccinationScopeProjector.project(listOf(task("v1")), places, 1)
        assertEquals(listOf("v1"), result.inSection("s1").map { it.task.id })
        assertEquals(listOf("v1"), result.inSection("s2").map { it.task.id })
        assertEquals(1, result.visible.size)
        assertEquals(2, result.visible.single().targetCount(null))
        assertEquals(1, result.visible.single().targetCount("u1"))
        assertEquals(1, result.visible.single().targetCount("u2"))
    }

    @Test fun missingOneTargetLocationHidesTheWholeTaskAndFuturePendingIsSeparate() {
        val future = task("future", "PENDING", "2030-01-01T00:00:00Z")
        val unscoped = task("unscoped", locations = listOf("Z-A-1-P1", null))
        val result = HomeVaccinationScopeProjector.project(listOf(future, unscoped), places, 1)
        assertTrue(result.visible.isEmpty())
        assertEquals(listOf("future"), result.notYetStarted)
        assertEquals(listOf("unscoped"), result.withoutReliableScope)
    }

    @Test fun refreshedDetailRejectsChangedTargetsOrUnitsButAllowsCompletion() {
        val original = task("v1")
        val scoped = HomeVaccinationScopeProjector.project(listOf(original), places, 1).visible.single()
        assertNotNull(consistentVaccinationDetail(scoped, original.copy(status = "COMPLETED"), places, 1))
        assertNull(consistentVaccinationDetail(scoped,
            task("v1", locations = listOf("Z-A-1-P1", "Z-A-1-P2")), places, 1))
        assertNull(consistentVaccinationDetail(scoped,
            task("v1", locations = listOf("Z-A-1-P1")), places, 1))
    }
}
