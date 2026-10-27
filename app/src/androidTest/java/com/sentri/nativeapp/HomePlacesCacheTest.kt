package com.sentri.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sentri.nativeapp.home.HomePlaces
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.pigs.SnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePlacesCacheTest {
    @Test fun sectionMetadataSurvivesSnapshotAndLegacySnapshotNeedsRefresh() {
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        val decoded = SnapshotCodec.locations(SnapshotCodec.locations(Locations(listOf(unit), emptyList())))
        assertEquals("section-a", HomePlaces.from(decoded).sections.single().id)
        assertEquals("怀孕舍", decoded.units.single().sectionNameCn)

        val old = """{"format":1,"locations":{"units":[{"id":"unit-7","farm":"farm","cn":"七单元","en":"Unit 7","location":"Z-A-7","area":"AnimalHousing"}],"pens":[]}}"""
        val legacy = HomePlaces.from(SnapshotCodec.locations(old))
        assertEquals(0, legacy.sections.size)
        assertEquals("unit-7", legacy.unitsMissingSection.single().id)
    }
}
