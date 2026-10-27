package com.sentri.nativeapp

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.therapy.MedicineDirectoryState
import com.sentri.nativeapp.therapy.TherapyMedication
import com.sentri.nativeapp.therapy.TherapyTreatment
import com.sentri.nativeapp.therapy.TreatmentDraftScreen
import org.junit.Assert.*
import com.sentri.nativeapp.therapy.TreatmentConditions
import com.sentri.nativeapp.therapy.TreatmentConditionKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreatmentDraftScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoPigPreviewUsesRealMedicineCacheAndKeepsSaveDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = PigProfileFixture.pig.copy(id = "p1", earTag = "T-1", role = "BREEDING_SOW")
        val second = first.copy(id = "p2", earTag = "T-2")
        val medications = (1..65).map { index -> TherapyMedication("med-$index", PigProfileFixture.scope.farmId,
            "药品 $index", "Medicine $index") }
        val state = MedicineDirectoryState(PigProfileFixture.lease,
            CachedResult(medications, DataSource.CACHE, 1),
            treatments = CachedResult(listOf(TherapyTreatment("method-1", "注射", "Injection",
                "肌肉", "Intramuscular")), DataSource.CACHE, 1))
        compose.setContent { SentriAppTheme {
            TreatmentDraftScreen(listOf(first, second), state, onRefreshMedicines = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.treatment_draft_targets, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_old_catalog)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_medicine)).performClick()
        val chosenName = if (context.resources.configuration.locales[0].language == "zh") "药品 65" else "Medicine 65"
        compose.onNodeWithText(chosenName).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_more_medicines, 5))
            .performScrollTo().performClick()
        compose.onNodeWithText(chosenName).performScrollTo().performClick()
        compose.onNodeWithText(chosenName).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_method)).performClick()
        val methodName = if (context.resources.configuration.locales[0].language == "zh")
            "注射 · 肌肉" else "Injection · Intramuscular"
        compose.onNodeWithText(methodName).performClick()
        compose.onNodeWithText(methodName).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_old_methods)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_dose)).performTextInput("2.5")
        compose.onNodeWithText(context.getString(R.string.treatment_draft_adjust, 2)).performClick()
        compose.onNodeWithText("T-1").assertIsDisplayed()
        compose.onNodeWithText("T-2").assertIsDisplayed()
        compose.onNodeWithTag("treatment-dose:p1").assertTextContains("2.5")
        compose.onNodeWithTag("treatment-dose:p2").assertTextContains("2.5")
        compose.onNodeWithTag("treatment-dose:p2").performTextReplacement("3")
        compose.onNodeWithTag("treatment-dose:p2").performImeAction()
        compose.onNodeWithTag("treatment-dose:p2").assertTextContains("3")
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithTag("treatment-shared-dose").performTextReplacement("4")
        compose.onNodeWithText(context.getString(R.string.treatment_draft_adjust, 2)).performClick()
        compose.onNodeWithTag("treatment-dose:p1").assertTextContains("4")
        compose.onNodeWithTag("treatment-dose:p2").assertTextContains("3")
        compose.onNodeWithTag("treatment-dose:p2").performTextClearance()
        compose.onNodeWithTag("treatment-dose:p2").performImeAction()
        compose.onNodeWithTag("treatment-dose:p2").assertTextContains("4")
        compose.onNodeWithTag("treatment-dose:p2").performTextReplacement("4.00")
        compose.onNodeWithTag("treatment-dose:p2").performImeAction()
        compose.onNodeWithTag("treatment-dose:p2").assertTextContains("4")
        compose.onNodeWithText(context.getString(R.string.treatment_draft_search_pigs)).performTextInput("missing")
        compose.onNodeWithText(context.getString(R.string.treatment_draft_no_matching_pigs)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_save)).assertDoesNotExist()
    }

    @Test fun conditionPickerUsesExistingCasesAndRemovesAnAssociationWhenItsOnlyCarrierIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = PigProfileFixture.pig
        val first = base.copy(id = "p1", earTag = "T-1", health = base.health!!.copy(
            symptoms = listOf(base.health.symptoms.first().copy(targetId = "p1"))))
        val second = first.copy(id = "p2", earTag = "T-2", health = first.health!!.copy(symptoms = emptyList()))
        val options = TreatmentConditions.existing(listOf(first, second))
        assertEquals(setOf("p1", "p2"), options.options.single { it.key.kind == TreatmentConditionKind.DISEASE }.pigIds)
        assertEquals(setOf("p1"), options.options.single { it.key.kind == TreatmentConditionKind.SYMPTOM }.pigIds)
        assertEquals(1, TreatmentConditions.existing(listOf(first.copy(health = null))).unavailablePigs)
        var hideKeyboard: () -> Unit = {}
        compose.setContent { SentriAppTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            androidx.compose.runtime.SideEffect { hideKeyboard = { keyboard?.hide() } }
            TreatmentDraftScreen(listOf(first, second), null, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_existing_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_carriers, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_carriers, 1, 2)).assertIsDisplayed()
        val symptom = if (context.resources.configuration.locales[0].language == "zh") "症" else "Symptom"
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_search)).performTextInput(symptom)
        compose.runOnIdle { hideKeyboard() }
        compose.onNode(hasText(symptom) and hasText(context.getString(R.string.treatment_draft_condition_carriers, 1, 2)))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_scope, 1, 2))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_dose)).performScrollTo().performTextInput("1")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithText(context.getString(R.string.treatment_draft_adjust, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_remove_pigs)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.treatment_draft_remove))[0].performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_none)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition)).performClick()
        compose.onNodeWithText(symptom).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_carriers, 1, 1)).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.treatment_draft_condition_none)) and
            !hasText(context.getString(R.string.treatment_draft_condition))).performClick()
    }

    @Test fun singlePigPreviewCannotSaveAsOfflineRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SentriAppTheme {
            TreatmentDraftScreen(listOf(PigProfileFixture.pig), null, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.treatment_draft_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_preview_warning)).assertIsDisplayed()
    }
}
