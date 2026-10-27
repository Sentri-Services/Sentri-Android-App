package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomePlacesTest {
    private fun unit(id: String, sectionId: String = "section-a", sectionCode: String = "A") = FarmUnit(
        id, "farm", "单元$id", "Unit $id", "Z-$sectionCode-$id", "AnimalHousing",
        sectionId, sectionCode, "怀孕舍", "Gestation", "Z", "生产区", "Production zone",
    )

    @Test fun `section identity and unit order come from backend metadata`() {
        val result = HomePlaces.from(Locations(listOf(unit("8"), unit("7"), unit("2", "section-b", "B")), emptyList()))
        assertEquals(listOf("section-a", "section-b"), result.sections.map { it.id })
        assertEquals(listOf("7", "8"), result.sections.first().units.map { it.id })
        assertEquals("Z-A", result.sections.first().locationCode)
        assertEquals(emptyList<FarmUnit>(), result.unitsMissingSection)
    }

    @Test fun `old location snapshot without section metadata stays unresolved`() {
        val legacy = FarmUnit("7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing")
        val result = HomePlaces.from(Locations(listOf(legacy), emptyList()))
        assertEquals(emptyList<HomePlaceSection>(), result.sections)
        assertEquals(listOf(legacy), result.unitsMissingSection)
    }

    @Test fun `conflicting section identity is rejected instead of silently merging units`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomePlaces.from(Locations(listOf(unit("7"), unit("8", sectionCode = "B")), emptyList()))
        }
    }
}
