package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.therapy.MedicineDirectoryContent
import com.sentri.nativeapp.therapy.MedicineDirectoryState
import com.sentri.nativeapp.therapy.TherapyMedication
import com.sentri.nativeapp.therapy.TherapyMedicationCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicineDirectoryContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legacyMedicineAndFormulationRemainReadOnlyAndOfflineLabeled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chinese = context.resources.configuration.locales[0].language == "zh"
        val state = MedicineDirectoryState(PigProfileFixture.lease,
            CachedResult(listOf(TherapyMedication("medicine", "farm", "阿莫西林", "Amoxicillin"),
                TherapyMedication("other", "farm", "土霉素", "Oxytetracycline")), DataSource.CACHE, 1),
            CachedResult(listOf(TherapyMedicationCatalog("form", "farm", "medicine",
                "阿莫西林粉", "Amoxicillin powder", "粉剂", "Powder")), DataSource.CACHE, 1))
        compose.setContent { SentriAppTheme { MedicineDirectoryContent(state, onRefresh = {}) } }
        compose.onNodeWithText(context.getString(R.string.medicine_directory_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_offline)).assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "阿莫西林" else "Amoxicillin").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "阿莫西林粉 (粉剂)" else "Amoxicillin powder (Powder)",
            substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_search)).performTextInput("oxytetra")
        compose.onNodeWithText(if (chinese) "土霉素" else "Oxytetracycline").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "阿莫西林" else "Amoxicillin").assertDoesNotExist()
    }
}
