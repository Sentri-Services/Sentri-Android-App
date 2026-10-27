package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.HomeEstrusDetailContent
import com.sentri.nativeapp.home.HomeEstrusDetailState
import com.sentri.nativeapp.home.HomePlaces
import com.sentri.nativeapp.home.HomeTaskScopeProjector
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.production.EstrusPig
import com.sentri.nativeapp.production.EstrusStatus
import com.sentri.nativeapp.production.EstrusTask
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPigLocation
import com.sentri.nativeapp.production.TaskPigReference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeEstrusDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentUnitShowsOnlyItsRecordedCheckWithoutGuessingTodaysCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val places = HomePlaces.from(Locations(listOf(
            FarmUnit("u7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
                "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"),
            FarmUnit("u8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing",
                "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production")), emptyList()))
        val metadata = TaskMetadata("task", "farm", ProductionTaskKind.ESTRUS,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch", "34", null, null, emptyList()), null,
            listOf(TaskPigReference("p7", false, "E-7", null, "Z-A-7-P1", null),
                TaskPigReference("p8", false, "E-8", null, "Z-A-8-P1", null)),
            emptyList(), emptyList(), emptyList())
        val scoped = HomeTaskScopeProjector.project(listOf(metadata), places, 1).visible.single()
        val detail = EstrusTask("task", "farm", "batch", "RUNNING", listOf(
            EstrusPig("p7", "E-7", null, false, EstrusStatus.SIGNS_OF_ESTRUS,
                "2026-10-21T09:41:00+08:00", location = TaskPigLocation("Z-A-7-P1", "u7", "Z-A-7", "Z-A-7-P1")),
            EstrusPig("p8", "E-8", null, false, EstrusStatus.IN_ESTRUS,
                "2026-10-21T09:42:00+08:00", location = TaskPigLocation("Z-A-8-P1", "u8", "Z-A-8", "Z-A-8-P1"))))
        compose.setContent { SentriAppTheme {
            HomeEstrusDetailContent(scoped, places, setOf("u7"),
                HomeEstrusDetailState(taskId = "task",
                    result = CachedResult(detail, DataSource.CACHE, 1)), onRefresh = {})
        } }
        compose.onNodeWithText("E-7").assertIsDisplayed()
        compose.onNodeWithText("E-8").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_estrus_status_signs)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_estrus_detail_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_offline_snapshot)).assertIsDisplayed()
    }
}
