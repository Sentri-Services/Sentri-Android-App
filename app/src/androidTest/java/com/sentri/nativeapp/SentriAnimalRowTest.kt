package com.sentri.nativeapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sentri.nativeapp.design.SentriAnimalRow
import com.sentri.nativeapp.design.SentriAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SentriAnimalRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longConditionListKeepsItsFirstNameAndSelectionIsSeparateFromOpening() {
        var selected by mutableStateOf(false)
        var opened = 0
        val names = listOf("A long recorded condition that cannot fit in the row") + (2..8).map { "Condition $it" }
        val description = names.joinToString("; ")
        compose.setContent { SentriAppTheme {
            Box(Modifier.width(320.dp)) {
                SentriAnimalRow("A-001", "A long stage name with an unknown day count", names, description,
                    onOpen = { opened++ }, selected = selected, selectionLabel = "Select A-001",
                    onSelectedChange = { selected = it })
            }
        } }
        compose.onNodeWithText("+7").assertIsDisplayed()
        compose.onNodeWithContentDescription(description).assertExists()
        compose.onNodeWithContentDescription("Select A-001").performClick().assertIsOn()
        assertEquals(0, opened)
        compose.onNodeWithText("A-001").performClick()
        assertEquals(1, opened)
        compose.onNodeWithContentDescription("Select A-001").assertIsOn()
    }
}
