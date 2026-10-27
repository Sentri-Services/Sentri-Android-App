package com.sentri.nativeapp.inspection

import org.junit.Assert.*
import org.junit.Test

class PenCountPreviewTest {
    private fun pig(id: String, batch: String?, tagged: Boolean = false) =
        LocationInventoryPig(id, "farm", "pen", "ACTIVE", if (tagged) id else null, null, batch)

    @Test fun singleUnassignedGroupFollowsTotalWithoutTouchingIdentifiedPigs() {
        val initial = PenCountPreview(LocationInventorySnapshot("pen", listOf(
            pig("tagged", null, true), pig("u1", null), pig("u2", null))))
        val changed = initial.setSingleTotal(1)
        assertEquals(0, changed.quantities[null])
        assertEquals(1, changed.totalCount)
        assertEquals(1, changed.identifiedCount)
        assertTrue(changed.valid) // A reason is optional.
        assertFalse(changed.copy(reason = PenCountReason.OTHER).valid)
        assertTrue(changed.copy(reason = PenCountReason.OTHER, otherReason = "Recounted").valid)
        assertEquals(3, initial.totalCount)
        assertTrue(runCatching { initial.setSingleTotal(0) }.isFailure)
        assertTrue(runCatching { initial.setQuantity(null, Int.MAX_VALUE) }.isFailure)
    }

    @Test fun batchDistributionCanChangeEvenWhenTheTotalStaysTheSame() {
        val initial = PenCountPreview(LocationInventorySnapshot("pen", listOf(
            pig("a1", "a"), pig("a2", "a"), pig("b1", "b"))))
        val changed = initial.setQuantity("a", 1).setQuantity("b", 2)
        assertEquals(0, changed.delta)
        assertTrue(changed.changed)
        assertTrue(changed.valid)
        assertEquals(listOf(PenCountReason.MISCOUNT, PenCountReason.OTHER), changed.reasons)
        val down = initial.setQuantity("a", 0).copy(reason = PenCountReason.DIED_UNRECORDED)
        val up = down.setQuantity("a", 3)
        assertNull(up.reason)
        assertTrue(PenCountReason.BORN_UNRECORDED in up.reasons)
        assertTrue(runCatching { initial.setQuantity("a", -1) }.isFailure)
    }

    @Test fun fullyIdentifiedPopulationHasNoEditableRemainder() {
        val initial = PenCountPreview(LocationInventorySnapshot("pen", listOf(pig("a", null, true))))
        assertTrue(initial.quantities.isEmpty())
        assertFalse(initial.changed)
        assertFalse(initial.valid)
        assertTrue(runCatching { initial.setSingleTotal(2) }.isFailure)
    }
}
