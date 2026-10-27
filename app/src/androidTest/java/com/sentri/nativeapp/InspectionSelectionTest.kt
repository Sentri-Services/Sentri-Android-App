package com.sentri.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sentri.nativeapp.inspection.InspectionSelection
import com.sentri.nativeapp.inspection.TreatmentExclusionReason
import com.sentri.nativeapp.inspection.reviewTreatmentSelection
import com.sentri.nativeapp.pigs.FarmUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InspectionSelectionTest {
    private val original = PigProfileFixture.pig
    private val code = requireNotNull(original.location?.penLocation)
    private val first = original.copy(id = "first")
    private val second = original.copy(id = "second")
    private val otherPen = original.copy(id = "other-pen",
        location = original.location!!.copy(penLocation = "other"))
    private val otherUnit = original.copy(id = "other-unit",
        location = original.location!!.copy(unitId = "foreign"))

    @Test fun individualSelectionDoesNotSelectPenAndPenSelectsOnlyVisiblePigsInUnit() {
        val unitId = "unit"
        val farmId = PigProfileFixture.scope.farmId
        val visible = listOf(first, second, otherPen, otherUnit)
        val one = InspectionSelection.togglePig(emptyMap(), visible, first.id, unitId, farmId)
        assertEquals(setOf(first.id), one.keys)
        val pen = InspectionSelection.togglePen(one, visible, code, unitId, farmId)
        assertEquals(setOf(first.id, second.id), pen.keys)
        val removed = InspectionSelection.togglePen(pen, visible, code, unitId, farmId)
        assertEquals(emptySet<String>(), removed.keys)
        assertEquals(one, InspectionSelection.togglePig(one, visible, otherUnit.id, unitId, farmId))
    }

    @Test fun selectionAcrossPagesKeepsEarlierPigAndDoesNotClaimHiddenPenMembers() {
        val farmId = PigProfileFixture.scope.farmId
        val one = InspectionSelection.togglePig(emptyMap(), listOf(first), first.id, "unit", farmId)
        val nextPage = InspectionSelection.togglePen(one, listOf(second), code, "unit", farmId)
        assertEquals(setOf(first.id, second.id), nextPage.keys)
        val onlySecondToggledOff = InspectionSelection.togglePen(nextPage, listOf(second), code, "unit", farmId)
        assertEquals(setOf(first.id), onlySecondToggledOff.keys)
    }

    @Test fun treatmentReviewUsesCurrentServerProfilesAndPreservesSelectionOrder() {
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val selected = listOf(first, second, otherPen, otherUnit)
        val latest = mapOf(first.id to first.copy(earNotch = "New tag"),
            second.id to second.copy(lifecycle = "DEAD"),
            otherPen.id to otherPen.copy(location = otherPen.location!!.copy(unitId = "foreign")))
        val review = reviewTreatmentSelection(selected, latest, unit)
        assertEquals(listOf(first.id), review.eligible.map { it.id })
        assertEquals("New tag", review.eligible.single().earNotch)
        assertEquals(listOf(TreatmentExclusionReason.INACTIVE, TreatmentExclusionReason.MOVED,
            TreatmentExclusionReason.MISSING), review.excluded.map { it.reason })
        assertEquals(listOf(second.id, otherPen.id, otherUnit.id), review.excluded.map { it.pig.id })
    }
}
