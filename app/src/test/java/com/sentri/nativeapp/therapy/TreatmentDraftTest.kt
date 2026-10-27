package com.sentri.nativeapp.therapy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreatmentDraftTest {
    @Test fun ownDoseStaysWhenSharedDoseChangesAndClearReturnsToShared() {
        val original = TreatmentDraft(listOf("p1", "p2"), medicineId = "med", method = "Injection",
            unit = "mL", sharedDose = "2.5")
        val adjusted = original.setOwnDose("p2", "3").copy(sharedDose = "4")
        assertEquals("4", adjusted.doseFor("p1"))
        assertEquals("3", adjusted.doseFor("p2"))
        assertEquals(1, adjusted.adjustedCount)
        assertEquals("4", adjusted.setOwnDose("p2", "").doseFor("p2"))
        assertTrue(adjusted.valid)
    }

    @Test fun everyPigNeedsPositiveDoseAndLastPigCannotBeRemoved() {
        val draft = TreatmentDraft(listOf("p1", "p2"), medicineId = "med", method = "Oral",
            unit = "mL", sharedDose = "")
        assertFalse(draft.valid)
        assertFalse(draft.setOwnDose("p1", "2").valid)
        assertTrue(draft.setOwnDose("p1", "2").setOwnDose("p2", "1.5").valid)
        val one = draft.removePig("p2")
        assertEquals(listOf("p1"), one.pigIds)
        runCatching { one.removePig("p1") }.onSuccess { error("Last pig was removed") }
    }
}
