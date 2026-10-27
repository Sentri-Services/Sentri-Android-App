package com.sentri.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SentriInputsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stepperExplainsRefusedBoundsAndNeverChangesDisabledValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var value by mutableStateOf(0)
        compose.setContent { SentriAppTheme {
            Column(Modifier.fillMaxSize().background(SentriTheme.colors.background).padding(18.dp)) {
                SentriStepper("Count", value, 0, 2, { value = it }, face = SentriStepperFace.COUNT,
                    draftDelta = value)
                SentriStepper("Locked", 1, 0, 2, { error("Disabled count changed") },
                    enabled = false, reason = "Record is locked")
            }
        } }
        val decrease = context.getString(R.string.inspection_count_decrease, "Count")
        val increase = context.getString(R.string.inspection_count_increase, "Count")
        compose.onNodeWithContentDescription(decrease).assertIsNotEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.sentri_stepper_minimum, 0)).assertIsDisplayed()
        assertEquals(0, value)
        compose.onNodeWithContentDescription(increase).performClick()
        compose.onNodeWithText(context.getString(R.string.sentri_stepper_unsaved, "+1")).assertIsDisplayed()
        compose.onNodeWithContentDescription(increase).performClick().assertIsNotEnabled().performClick()
        assertEquals(2, value)
        compose.onNodeWithText(context.getString(R.string.sentri_stepper_maximum, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_count_increase, "Locked"))
            .assertIsNotEnabled().performClick()
        compose.onNodeWithText("Record is locked").assertIsDisplayed()
    }

    @Test fun fieldKeepsVisibleLabelsValuesAndPersistentErrorOrDisabledReason() {
        var note by mutableStateOf("")
        var hideKeyboard: () -> Unit = {}
        compose.setContent { SentriAppTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            SideEffect { hideKeyboard = { keyboard?.hide() } }
            Column(Modifier.width(320.dp).fillMaxHeight().background(SentriTheme.colors.background)
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SentriField(note, { note = it }, "本栏位需要记录的备注与说明", singleLine = false,
                    minLines = 2, optional = true, hint = "本机草稿")
                SentriField("0", {}, "Dosage", unit = "mL", error = "Enter a dose above 0")
                SentriField("Saved note", { error("Disabled field changed") }, "Locked note",
                    enabled = false, reason = "Saving on this phone")
                SentriPanel { SentriStepper("本批次待核对的数量", 0, 0, 10, {},
                    Modifier.padding(12.dp), draftDelta = -2) }
            }
        } }
        compose.onNodeWithText("本栏位需要记录的备注与说明").performTextInput("AB12")
        assertEquals("AB12", note)
        compose.onNodeWithText("本栏位需要记录的备注与说明").assertTextContains("AB12")
        compose.onNodeWithText("Enter a dose above 0").assertIsDisplayed()
        compose.onNodeWithText("Locked note").assertIsNotEnabled()
        compose.onNodeWithText("Saving on this phone").assertIsDisplayed()
        compose.onNodeWithText("本机草稿").assertIsDisplayed()
        compose.runOnIdle { hideKeyboard() }
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        try { java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "sentri-inputs-review.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { }
    }
}
