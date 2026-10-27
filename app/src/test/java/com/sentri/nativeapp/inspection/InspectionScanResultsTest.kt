package com.sentri.nativeapp.inspection

import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.PigProfile
import com.sentri.nativeapp.pigs.PigProfileLocation
import org.junit.Assert.*
import org.junit.Test

class InspectionScanResultsTest {
    private fun pig(id: String, farm: String = "farm", unit: String = "unit") = PigProfile(
        id = id, farmId = farm, earTag = id, earNotch = null, extendedEarTag = null,
        role = "PRODUCTION_SOW", sex = "FEMALE", lifecycle = "ACTIVE", origin = "SELFBRED",
        locationCode = null, birthday = null, entryDate = null, remark = null, weight = null,
        parity = null, breedType = null, breederCode = null, teatCount = null,
        firstEstrusAt = null, lastEstrusAt = null, lastFarrowingAt = null, lastLactationAt = null,
        lastMatingAt = null, productionStatus = null, currentBatchId = null,
        breederProductionStatus = null, batch = null, semen = null,
        location = PigProfileLocation("zone", unit, null, null, null, null, null, 0,
            null, null, null, null, null), health = null, deathImages = null)

    @Test fun repeatScanMovesTheSamePigToTheTopWithoutAddingAnotherRow() {
        val first = InspectionScanResults.add(emptyList(), pig("A"), DataSource.NETWORK, 1)
        val second = InspectionScanResults.add(first, pig("B"), DataSource.CACHE, 2)
        val repeated = InspectionScanResults.add(second, pig("A"), DataSource.CACHE, 3)
        assertEquals(listOf("A", "B"), repeated.map { it.pig.id })
        assertEquals(listOf(3L, 2L), repeated.map { it.sequence })
        assertEquals(DataSource.CACHE, repeated.first().source)
    }

    @Test fun penGroupsKeepCurrentUnitFirstAndMoveTheRepeatedPigAndItsPenToTheFront() {
        fun inPen(id: String, unit: String, pen: String?) = pig(id, unit = unit).let {
            it.copy(location = it.location?.copy(penLocation = pen))
        }
        var scans = emptyList<ScannedPig>()
        listOf(inPen("A", "unit", "P1"), inPen("B", "unit", "P2"),
            inPen("C", "unit", "P1"), inPen("D", "other", "P1"),
            inPen("E", "other", "")).forEachIndexed { index, pig ->
            scans = InspectionScanResults.add(scans, pig, DataSource.NETWORK, index.toLong())
        }
        scans = InspectionScanResults.add(scans, inPen("A", "unit", "P1"), DataSource.CACHE, 6)
        val groups = InspectionScanResults.grouped(scans, "unit")
        assertEquals(listOf("unit", "other"), groups.map { it.unitId })
        assertEquals(listOf("P1", "P2"), groups.first().pens.map { it.penCode })
        assertEquals(listOf("A", "C"), groups.first().pens.first().entries.map { it.pig.id })
        assertNull(groups.last().pens.first().penCode)
        assertEquals(listOf("E", "D"), groups.last().pens.flatMap { it.entries }.map { it.pig.id })
        assertEquals(5, groups.sumOf { group -> group.pens.sumOf { it.entries.size } })
    }

    @Test fun anotherUnitOrFarmCannotEnterThisWalkSelection() {
        assertTrue(InspectionScanResults.selectable(pig("A"), "farm", "unit"))
        assertFalse(InspectionScanResults.selectable(pig("A", unit = "elsewhere"), "farm", "unit"))
        assertFalse(InspectionScanResults.selectable(pig("A", farm = "other"), "farm", "unit"))
        assertFalse(InspectionScanResults.selectable(pig("A").copy(lifecycle = "DEAD"), "farm", "unit"))
    }
}
