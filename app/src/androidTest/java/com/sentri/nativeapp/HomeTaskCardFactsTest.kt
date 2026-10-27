package com.sentri.nativeapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.ConnectedHomeScreen
import com.sentri.nativeapp.home.HomeLiveState
import com.sentri.nativeapp.home.HomePlaces
import com.sentri.nativeapp.home.HomeTaskCardFactsViewModel
import com.sentri.nativeapp.home.HomeTaskScopeProjector
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.pigs.ScopeLease
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
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@RunWith(AndroidJUnit4::class)
class HomeTaskCardFactsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oldFarmHeatDetailCannotReplaceNewFarmCardFact() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var currentFarm = "old"
        val facts = HomeTaskCardFactsViewModel({ lease, _ ->
            if (lease.scope.farmId == "old") withContext(NonCancellable) {
                started.complete(Unit); release.await(); returned.complete(Unit)
            }
            val sign = if (lease.scope.farmId == "old") EstrusStatus.SIGNS_OF_ESTRUS
                else EstrusStatus.NOT_IN_ESTRUS
            CachedResult(EstrusTask("task", lease.scope.farmId, "batch", "RUNNING", listOf(
                EstrusPig("pig", "E-1", null, false, sign, null,
                    location = TaskPigLocation("Z-A-1-P1", "unit", "Z-A-1", "Z-A-1-P1"))))
                as EstrusTask?, DataSource.NETWORK, 1)
        }, { lease -> if (lease.scope.farmId != currentFarm) throw RequestContextChanged() })
        fun scope(farm: String) = ScopeLease(DataScope("dev", "oauth", "user", farm),
            if (farm == "old") 1 else 2)
        fun fixture(farm: String): Pair<HomePlaces, List<com.sentri.nativeapp.home.HomeScopedTask>> {
            val unit = FarmUnit("unit", farm, "一单元", "Unit 1", "Z-A-1", "AnimalHousing",
                "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production")
            val places = HomePlaces.from(Locations(listOf(unit), emptyList()))
            val task = TaskMetadata("task", farm, ProductionTaskKind.ESTRUS,
                ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
                TaskBatch("batch", "34", null, null, emptyList()), null,
                listOf(TaskPigReference("pig", false, "E-1", null, "Z-A-1-P1", null)),
                emptyList(), emptyList(), emptyList())
            return places to HomeTaskScopeProjector.project(listOf(task), places, 1).visible
        }
        val (oldPlaces, oldTasks) = fixture("old")
        val (newPlaces, newTasks) = fixture("new")
        compose.setContent { Text("Card scope") }
        compose.runOnIdle { facts.open(scope("old"), "unit", oldTasks, oldPlaces, 1, 1) }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle {
            currentFarm = "new"
            facts.open(scope("new"), "unit", newTasks, newPlaces, 1, 1)
        }
        compose.waitUntil(10_000) { facts.state.value.heat.containsKey("task") }
        release.complete(Unit)
        compose.waitUntil(10_000) { returned.isCompleted }
        compose.waitForIdle()
        assertEquals("new", facts.state.value.lease?.scope?.farmId)
        assertEquals(0, facts.state.value.heat.getValue("task").signs("unit"))
        facts.clear()
    }

    @Test fun heatUnitCardUsesOnlyMatchingRecordedSignsAndLabelsOldDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = listOf(
            FarmUnit("u7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
                "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"),
            FarmUnit("u8", "farm", "八单元", "Unit 8", "Z-A-8", "AnimalHousing",
                "section", "A", "配种舍", "Breeding", "Z", "生产区", "Production"))
        val task = TaskMetadata("task", "farm", ProductionTaskKind.ESTRUS,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch", "34", null, null, emptyList()), null,
            listOf(TaskPigReference("p7", false, "E-7", null, "Z-A-7-P1", null),
                TaskPigReference("p8", false, "E-8", null, "Z-A-8-P1", null)),
            emptyList(), emptyList(), emptyList())
        var detail = EstrusTask("task", "farm", "batch", "RUNNING", listOf(
            EstrusPig("p7", "E-7", null, false, EstrusStatus.SIGNS_OF_ESTRUS,
                "2026-10-21T09:41:00+08:00", location = TaskPigLocation("Z-A-7-P1", "u7", "Z-A-7", "Z-A-7-P1")),
            EstrusPig("p8", "E-8", null, false, EstrusStatus.IN_ESTRUS,
                "2026-10-21T09:42:00+08:00", location = TaskPigLocation("Z-A-8-P1", "u8", "Z-A-8", "Z-A-8-P1"))))
        var source = DataSource.NETWORK
        val reads = AtomicInteger()
        val facts = HomeTaskCardFactsViewModel(
            { _, _ -> reads.incrementAndGet(); CachedResult(detail as EstrusTask?, source, 1) }, {})
        val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
        val places = HomePlaces.from(Locations(units, emptyList()))
        var home by mutableStateOf(HomeLiveState(lease = lease,
            places = CachedResult(places, DataSource.NETWORK, 1),
            production = CachedResult(listOf(task), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1)))
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(home,
            onRefresh = {}, onExit = {}, cardFacts = facts) } }
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        val unit7 = if (context.resources.configuration.locales[0].language == "zh") "七单元" else "Unit 7"
        val unit8 = if (context.resources.configuration.locales[0].language == "zh") "八单元" else "Unit 8"
        compose.onNodeWithText(unit7).performClick()
        compose.waitUntil(10_000) { facts.state.value.heat["task"]?.signs("u7") == 1 }
        compose.onNodeWithText(context.getString(R.string.connected_heat_signs, 1)).assertIsDisplayed()

        compose.onNodeWithText(unit7).performClick()
        compose.onNodeWithText(unit8).performClick()
        compose.waitUntil(10_000) { facts.state.value.unitId == "u8" &&
            facts.state.value.heat.containsKey("task") }
        compose.onNodeWithText(context.getString(R.string.connected_heat_signs, 1)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_task_summary, "34", 1)).assertIsDisplayed()

        compose.onNodeWithText(unit8).performClick()
        compose.onNodeWithText(unit7).performClick()
        source = DataSource.CACHE
        compose.runOnIdle { home = home.copy(production = CachedResult(listOf(task), DataSource.NETWORK, 2)) }
        compose.waitUntil(10_000) { facts.state.value.heat["task"]?.source == DataSource.CACHE }
        compose.onNodeWithText(context.getString(R.string.connected_heat_signs_cached, 1)).assertIsDisplayed()

        detail = detail.copy(status = "COMPLETED")
        compose.runOnIdle { home = home.copy(production = CachedResult(listOf(task), DataSource.NETWORK, 3)) }
        compose.waitUntil(10_000) { reads.get() >= 5 && facts.state.value.heat.isEmpty() }
        compose.onNodeWithText(context.getString(R.string.connected_heat_signs_cached, 1)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_task_summary, "34", 1)).assertIsDisplayed()
        facts.clear()
    }
}
