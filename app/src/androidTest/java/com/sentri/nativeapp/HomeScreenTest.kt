package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overviewUsesProductionHealthOtherAndUnitKeepsFullCards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SentriAppTheme { HomeScreen(onExit = {}) } }
        compose.onNodeWithText(context.getString(R.string.wb_production)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_unit_n, 7)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_todays_work)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_unit_facts, 86, 8)).assertIsDisplayed()
    }

    @Test fun scheduleIsFullScreenAndItsKeyOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SentriAppTheme { HomeScreen(onExit = {}) } }
        compose.onAllNodesWithText(context.getString(R.string.wb_schedule_link))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.wb_tap_row)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.wb_how_to_read)).performClick()
        compose.onNodeWithText(context.getString(R.string.wb_tap_row)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_tab_home)).assertDoesNotExist()
    }

    @Test fun unitPickerShowsCountsCurrentUnitAndFooterBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SentriAppTheme { HomeScreen(onExit = {}) } }
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_all_units, 3)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.home_all_units, 3)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_unit_n, 7)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_unit_n, 7)).performClick()
        assertTrue(compose.onAllNodesWithText(context.getString(R.string.home_unit_facts, 86, 8)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun pullToRefreshAdvancesMockTemplate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SentriAppTheme { HomeScreen(onExit = {}) } }
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_unit_n, 7)).performClick()
        compose.onNodeWithText(context.getString(R.string.wb_health) + " 3").assertIsDisplayed()
        compose.waitUntil(3000) {
            compose.onAllNodesWithText(context.getString(R.string.home_all_units, 3)).fetchSemanticsNodes().isEmpty()
        }
        compose.onRoot().performTouchInput { swipeDown(startY = 600f, endY = 1400f) }
        compose.waitUntil(3000) {
            compose.onAllNodesWithText(context.getString(R.string.wb_health) + " 24").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.wb_health) + " 24").assertIsDisplayed()
    }
}
