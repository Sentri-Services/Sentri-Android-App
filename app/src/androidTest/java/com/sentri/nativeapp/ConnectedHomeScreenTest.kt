package com.sentri.nativeapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.ConnectedHomeScreen
import com.sentri.nativeapp.home.AndroidHomeSectionStore
import com.sentri.nativeapp.home.HomeLiveState
import com.sentri.nativeapp.home.HomePlaces
import com.sentri.nativeapp.home.HomePregnancyDetailContent
import com.sentri.nativeapp.home.HomePregnancyDetailState
import com.sentri.nativeapp.home.HomeVaccinationDetailContent
import com.sentri.nativeapp.home.HomeVaccinationDetailState
import com.sentri.nativeapp.home.HomeScopedVaccination
import com.sentri.nativeapp.home.HomeScopedTask
import com.sentri.nativeapp.home.HomeUnitHub
import com.sentri.nativeapp.home.HomeUnitMaintenance
import com.sentri.nativeapp.home.HomeUnitMaintenanceDetail
import com.sentri.nativeapp.home.HomeUnitOverviewState
import com.sentri.nativeapp.home.HomeUnitStock
import com.sentri.nativeapp.home.HomePenCount
import com.sentri.nativeapp.home.HomeRegisteredPen
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeUnitStockDetail
import com.sentri.nativeapp.home.HomeTransferTask
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.inspection.DeviceInspection
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.Farm
import com.sentri.nativeapp.network.Identity
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPigReference
import com.sentri.nativeapp.production.PregnancyPig
import com.sentri.nativeapp.production.PregnancyResult
import com.sentri.nativeapp.production.PregnancyStatus
import com.sentri.nativeapp.production.PregnancyTask
import com.sentri.nativeapp.production.TaskPigLocation
import com.sentri.nativeapp.vaccination.VaccinationPig
import com.sentri.nativeapp.vaccination.VaccinationTargetDose
import com.sentri.nativeapp.vaccination.VaccinationTask
import com.sentri.nativeapp.vaccination.VaccinationVaccine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.math.BigDecimal
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConnectedHomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unitShowsFarmDatedFutureStartsWithoutAddingThemToTodaysWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val tomorrow = Calendar.getInstance(zone).apply {
            timeInMillis = System.currentTimeMillis()
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
        }
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
            .format(tomorrow.time)
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        val future = TaskMetadata("future", "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.SCHEDULED, null, null, null, null, null, null,
            TaskBatch("batch-future", "Future batch", null, null, emptyList()),
            com.sentri.nativeapp.production.TaskPlan(Instant.ofEpochMilli(tomorrow.timeInMillis).toString(), 3),
            listOf(TaskPigReference("pig-future", false, null, null, "Z-A-7-P1", null)),
            emptyList(), emptyList(), emptyList())
        val state = HomeLiveState(
            account = AccountContext(Identity("user", "user", null, "worker", emptyList()),
                Farm("farm", "Real Farm", "Asia/Shanghai")),
            places = CachedResult(HomePlaces.from(Locations(listOf(unit), emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(listOf(future), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1))
        compose.setContent { SentriAppTheme { Box(Modifier.width(320.dp)) {
            ConnectedHomeScreen(state, onRefresh = {}, onExit = {})
        } } }
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(if (context.resources.configuration.locales[0].language == "zh")
            "七单元" else "Unit 7").performClick()
        val title = context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "Future batch")
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.connected_upcoming_title)))
        compose.onNodeWithContentDescription(context.getString(
            R.string.connected_upcoming_day_description, dateKey, 1))
            .performScrollTo().assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_upcoming_selected, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_upcoming_view)).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_upcoming_details_title)).assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun remembersLastSectionWithinAccountAndFarmOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "home-section-test-${UUID.randomUUID()}"
        val store = AndroidHomeSectionStore(context, name)
        val scope = DataScope("dev", "oauth-a", "user-a", "farm")
        val otherAccount = scope.copy(oauthId = "oauth-b")
        val sectionA = FarmUnit("unit-a", "farm", "一单元", "Unit 1", "Z-A-1", "AnimalHousing",
            "section-a", "A", "妊娠舍", "Gestation", "Z", "生产区", "Production zone")
        val sectionB = FarmUnit("unit-b", "farm", "二单元", "Unit 2", "Z-B-2", "AnimalHousing",
            "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone")
        val places = CachedResult(HomePlaces.from(Locations(listOf(sectionA, sectionB), emptyList())),
            DataSource.NETWORK, 1)
        val account = AccountContext(Identity("user-a", "user", null, "worker", emptyList()),
            Farm("farm", "Real Farm"))
        store.write(scope, "section-b")
        var state by mutableStateOf(HomeLiveState(account = account, lease = ScopeLease(scope, 1),
            places = places, production = CachedResult(emptyList(), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1)))
        val bName = if (context.resources.configuration.locales[0].language == "zh") "分娩舍" else "Farrowing"
        val aName = if (context.resources.configuration.locales[0].language == "zh") "妊娠舍" else "Gestation"
        try {
            compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {},
                onExit = {}, sectionStore = store) } }
            compose.onNodeWithText(bName).assertIsDisplayed()
            compose.runOnIdle { state = state.copy(lease = ScopeLease(otherAccount, 2)) }
            compose.onNodeWithText(aName).assertIsDisplayed()
            assertEquals(null, store.read(otherAccount))
            compose.runOnIdle { state = state.copy(lease = ScopeLease(scope, 3)) }
            compose.onNodeWithText(bName).assertIsDisplayed()
            compose.onNodeWithText(bName).performClick()
            compose.onNodeWithText(aName).performClick()
            assertEquals("section-a", AndroidHomeSectionStore(context, name).read(scope))
            assertEquals(null, store.read(otherAccount))
            assertEquals(null, store.read(scope.copy(farmId = "another-farm")))
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun scheduledTaskAppearsAtItsStartWithoutRefreshingHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        val startAt = Instant.ofEpochMilli(System.currentTimeMillis() + 15_000).toString()
        val scheduled = TaskMetadata("due-soon", "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.SCHEDULED, null, null, null, null, null, null,
            TaskBatch("batch-due-soon", "Due soon", null, null, emptyList()),
            com.sentri.nativeapp.production.TaskPlan(startAt, 3),
            listOf(TaskPigReference("pig-due-soon", false, null, null, "Z-A-7-P1", null)),
            emptyList(), emptyList(), emptyList())
        val state = HomeLiveState(
            account = AccountContext(Identity("user", "user", null, "worker", emptyList()), Farm("farm", "Real Farm")),
            places = CachedResult(HomePlaces.from(Locations(listOf(unit), emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(listOf(scheduled), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
        )
        var refreshes = 0
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = { refreshes++ }, onExit = {}) } }
        val batch = context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "Due soon")
        compose.onNodeWithText(batch).assertDoesNotExist()
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(batch).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(batch).assertIsDisplayed()
        assertEquals(0, refreshes)
    }

    @Test fun showsAccountScopeAndOnlyReliablyLocatedTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        fun task(id: String, location: String?) = TaskMetadata(id, "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch-$id", id, null, null, emptyList()), null,
            listOf(TaskPigReference("pig-$id", false, null, null, location, null)),
            emptyList(), emptyList(), emptyList())
        val state = HomeLiveState(
            account = AccountContext(Identity("user", "user", null, "worker", emptyList()), Farm("farm", "Real Farm")),
            places = CachedResult(HomePlaces.from(Locations(listOf(unit), emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(listOf(task("31", "Z-A-7-P1"), task("hidden", null)), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
        )
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {}) } }
        compose.onNodeWithText("Real Farm").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_todays_work)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "31")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_overview_targets, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "hidden")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_picker_tasks_unknown)).assertIsDisplayed()
        compose.onNodeWithText(if (context.resources.configuration.locales[0].language == "zh") "七单元" else "Unit 7").performClick()
        compose.onNodeWithText(context.getString(R.string.home_inspect_unit)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_todays_work)).assertIsDisplayed()
        val unitSummary = context.getString(R.string.connected_task_summary, "31", 1)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(unitSummary))
        compose.onNodeWithText(unitSummary).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_tab_toolbox)).performClick()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_title)).assertIsDisplayed()
    }

    @Test fun vaccinationAppearsOnceInItsKnownSectionAndOpensHonestDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        val pig = VaccinationPig("pig", "farm", "001", null, null, null, null,
            "ACTIVE", "Z-A-7-P1", null, null, null)
        val dose = VaccinationTargetDose("farm", "vaccination", "pig", "vaccine", "brand", 1,
            "PENDING", null, null, null, null, pig)
        val task = VaccinationTask("vaccination", "farm", "vaccine", "brand", "IN_PROGRESS",
            null, null, null, null, null, null, null, "IM", null, null, "mL", 1,
            BigDecimal.ONE, 0, 0, 0, 1, "PLAN", null, null, null, false, null,
            VaccinationVaccine("vaccine", "farm", "蓝耳疫苗", "PRRS vaccine", "ACTIVE"), null, listOf(dose))
        val state = HomeLiveState(
            places = CachedResult(HomePlaces.from(Locations(listOf(unit), emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(emptyList(), DataSource.NETWORK, 1),
            vaccination = CachedResult(listOf(task), DataSource.CACHE, 1),
        )
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {}) } }
        val vaccine = if (context.resources.configuration.locales[0].language == "zh") "蓝耳疫苗" else "PRRS vaccine"
        val vaccineTitle = context.getString(R.string.connected_overview_vaccination_instance,
            vaccine, "vaccination")
        compose.onNodeWithText(vaccineTitle).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_target_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(vaccineTitle).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_detail_pending)).assertIsDisplayed()
    }

    @Test fun fiveTasksGroupByCategoryAndGroupsOpenIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
            "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        fun production(id: String) = TaskMetadata(id, "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch-$id", id, null, null, emptyList()), null,
            listOf(TaskPigReference("pig-$id", false, null, null, "Z-A-7-P1", null)),
            emptyList(), emptyList(), emptyList())
        fun vaccination(id: String): VaccinationTask {
            val pig = VaccinationPig("pig-$id", "farm", id, null, null, null, null,
                "ACTIVE", "Z-A-7-P1", null, null, null)
            val dose = VaccinationTargetDose("farm", id, pig.id, "vaccine", "brand", 1,
                "PENDING", null, null, null, null, pig)
            return VaccinationTask(id, "farm", "vaccine", "brand", "IN_PROGRESS",
                null, null, null, null, null, null, null, "IM", null, null, "mL", 1,
                BigDecimal.ONE, 0, 0, 0, 1, "PLAN", null, null, null, false, null,
                VaccinationVaccine("vaccine", "farm", "蓝耳疫苗", "PRRS vaccine", "ACTIVE"),
                null, listOf(dose))
        }
        var state by mutableStateOf(HomeLiveState(
            places = CachedResult(HomePlaces.from(Locations(listOf(unit), emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(listOf(production("31"), production("32")), DataSource.NETWORK, 1),
            vaccination = CachedResult(listOf(vaccination("v1"), vaccination("v2"), vaccination("v3")),
                DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1)))
        val productionTitle = context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "31")
        val vaccineName = if (context.resources.configuration.locales[0].language == "zh")
            "蓝耳疫苗" else "PRRS vaccine"
        val vaccinationTitle = context.getString(R.string.connected_overview_vaccination_instance,
            vaccineName, "v1")
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {}) } }
        compose.onNodeWithText(context.getString(R.string.wb_production)).assertIsDisplayed()
        compose.onNodeWithText(productionTitle).assertIsDisplayed()
        compose.onNodeWithText(vaccinationTitle).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_group)).performClick()
        compose.onNodeWithText(productionTitle).assertExists()
        compose.onNodeWithText(vaccinationTitle).assertExists()
        compose.onNodeWithText(context.getString(R.string.wb_production)).performClick()
        compose.onNodeWithText(productionTitle).assertDoesNotExist()
        compose.onNodeWithText(vaccinationTitle).assertExists()
        compose.runOnIdle { state = state.copy(vaccination = CachedResult(
            state.vaccination!!.value.dropLast(1), DataSource.NETWORK, 2)) }
        compose.onNodeWithText(context.getString(R.string.wb_production)).assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(productionTitle))
        compose.onNodeWithText(productionTitle).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(vaccinationTitle))
        compose.onNodeWithText(vaccinationTitle).assertIsDisplayed()
    }

    @Test fun vaccinationDetailShowsOnlyCurrentScopeAndEachDoseRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = listOf(
            FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
                "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
            FarmUnit("unit-9", "farm", "九单元", "Unit 9", "Z-B-9", "AnimalHousing",
                "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone"))
        val places = HomePlaces.from(Locations(units, emptyList()))
        fun pig(id: String, tag: String, code: String) = VaccinationPig(id, "farm", tag, null,
            null, null, null, "ACTIVE", code, null, null, null)
        val doses = listOf(
            VaccinationTargetDose("farm", "vaccination", "pig-7", "vaccine", "brand", 1,
                "COMPLETED", "2026-10-21T08:00:00+08:00", "2026-10-21T09:00:00+08:00", null,
                null, pig("pig-7", "E-7", "Z-A-7-P1")),
            VaccinationTargetDose("farm", "vaccination", "pig-7", "vaccine", "brand", 2,
                "PENDING", "2026-10-23T08:00:00+08:00", null, null, null,
                pig("pig-7", "E-7", "Z-A-7-P1")),
            VaccinationTargetDose("farm", "vaccination", "pig-9", "vaccine", "brand", 1,
                "PENDING", null, null, null, null, pig("pig-9", "E-9", "Z-B-9-P1")))
        val task = VaccinationTask("vaccination", "farm", "vaccine", "brand", "IN_PROGRESS",
            null, null, null, null, null, null, null, "IM", null, null, "mL", 2,
            BigDecimal.ONE, 1, 0, 1, 2, "PLAN", null, null, null, false, null,
            VaccinationVaccine("vaccine", "farm", "蓝耳疫苗", "PRRS vaccine", "ACTIVE"), null, doses)
        val scoped = HomeScopedVaccination(task, mapOf("pig-7" to "unit-7", "pig-9" to "unit-9"),
            setOf("section-a", "section-b"))
        compose.setContent { SentriAppTheme {
            HomeVaccinationDetailContent(scoped, places, setOf("unit-7"),
                HomeVaccinationDetailState(taskId = task.id,
                    result = CachedResult(task, DataSource.CACHE, 1)), onRefresh = {})
        } }
        compose.onNodeWithText("E-7").assertIsDisplayed()
        compose.onNodeWithText("E-9").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_dose_row, 1,
            context.getString(R.string.connected_vaccination_dose_recorded))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_dose_row, 2,
            context.getString(R.string.connected_vaccination_dose_unrecorded))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_vaccination_detail_offline)).assertIsDisplayed()
    }

    @Test fun overviewTileNarrowsCrossSectionTargetsWithoutSplittingTaskIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = listOf(
            FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
                "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
            FarmUnit("unit-9", "farm", "九单元", "Unit 9", "Z-B-9", "AnimalHousing",
                "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone"))
        val task = TaskMetadata("cross", "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch-cross", "31", null, null, emptyList()), null,
            listOf(TaskPigReference("pig-1", false, null, null, "Z-A-7-P1", null),
                TaskPigReference("pig-2", false, null, null, "Z-B-9-P1", null)),
            emptyList(), emptyList(), emptyList())
        val state = HomeLiveState(
            places = CachedResult(HomePlaces.from(Locations(units, emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(listOf(task), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1))
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {}) } }
        compose.onNodeWithText(context.getString(R.string.connected_overview_production_instance,
            context.getString(R.string.home_task_pregnancy), "31")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_overview_targets, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_overview_targets, 2)).assertDoesNotExist()
        val sectionName = if (context.resources.configuration.locales[0].language == "zh")
            "怀孕舍" else "Gestation"
        compose.onNodeWithText(sectionName).performClick()
        compose.onNodeWithText(context.getString(R.string.home_select_section)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_todays_work)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.home_tab_home)).assertIsDisplayed()
        val summary = context.getString(R.string.connected_picker_section_count, 1, 1)
        assertEquals(2, compose.onAllNodesWithText(summary).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_todays_work)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_picker_tasks_count, 1)).assertIsDisplayed()
    }

    @Test fun transferUsesExplicitRouteAndOpensReadOnlyTaskPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = listOf(
            FarmUnit("unit-7", "farm", "七单元", "Unit 7", "Z-A-7", "AnimalHousing",
                "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone"),
            FarmUnit("unit-9", "farm", "九单元", "Unit 9", "Z-B-9", "AnimalHousing",
                "section-b", "B", "分娩舍", "Farrowing", "Z", "生产区", "Production zone"))
        val state = HomeLiveState(
            places = CachedResult(HomePlaces.from(Locations(units, emptyList())), DataSource.NETWORK, 1),
            production = CachedResult(emptyList(), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(listOf(HomeTransferTask("tr-31", "farm", "IN_PROGRESS",
                "PRODUCTION_SOW", "2026-10-20T08:00:00+08:00", "31",
                listOf("Z-A-7"), listOf("Z-B-9"))), DataSource.NETWORK, 1))
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {}) } }
        val route = if (context.resources.configuration.locales[0].language == "zh")
            "七单元 → 九单元" else "Unit 7 → Unit 9"
        compose.onNodeWithText(route).assertIsDisplayed()
        compose.onNodeWithText(route).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_transfer_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_transfer_detail_loading)).assertIsDisplayed()
    }

    @Test fun pregnancyDetailShowsOnlyCurrentUnitAndLatestRecordedResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = TaskMetadata("check-31", "farm", ProductionTaskKind.PREGNANCY,
            ProductionTaskStatus.RUNNING, null, null, null, null, null, null,
            TaskBatch("batch-31", "31", null, null, emptyList()), null,
            listOf(TaskPigReference("pig-1", false, "001", null, "Z-A-7-P1", null),
                TaskPigReference("pig-2", false, "002", null, "Z-A-8-P1", null)),
            emptyList(), emptyList(), emptyList())
        val detail = PregnancyTask("check-31", "farm", "batch-31", "RUNNING", listOf(
            PregnancyPig("pig-1", "001", null, false,
                TaskPigLocation("Z-A-7-P1", "unit-7", "7", "P1"),
                PregnancyResult(PregnancyStatus.PREGNANT, null, true, null, null, null, null, null)),
            PregnancyPig("pig-2", "002", null, false,
                TaskPigLocation("Z-A-8-P1", "unit-8", "8", "P1"), null)))
        compose.setContent { SentriAppTheme {
            HomePregnancyDetailContent(HomeScopedTask(metadata, setOf("unit-7", "unit-8"), setOf("section-a")),
                setOf("unit-7"), HomePregnancyDetailState(taskId = "check-31",
                    result = CachedResult(detail, DataSource.CACHE, 1)), onRefresh = {})
        } }
        compose.onNodeWithText(context.getString(R.string.connected_pregnancy_record_count, 1)).assertIsDisplayed()
        compose.onNodeWithText("001").assertIsDisplayed()
        compose.onNodeWithText("002").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_pregnancy_pregnant)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_offline_snapshot)).assertIsDisplayed()
    }

    @Test fun unitHubDistinguishesCountsOldSnapshotAndInspectionEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var inspectionOpens = 0
        var stockOpens = 0
        val state = HomeUnitOverviewState(unitId = "unit-7",
            stock = CachedResult(HomeUnitStock("unit-7", 86, 8), DataSource.CACHE, 1),
            lastCheckIn = CachedResult(UnitCheckInSummary("check", "farm", "Z-A-7",
                "2026-10-20T08:20:00+08:00", "worker"), DataSource.NETWORK, 1))
        compose.setContent { SentriAppTheme {
            HomeUnitHub(state, onInspect = { inspectionOpens++ }, onStock = { stockOpens++ },
                onMaintenance = {}, onRetry = {})
        } }
        compose.onNodeWithText(context.getString(R.string.connected_unit_stock_numbers, 86, 8)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_old_snapshot)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_inspect_unit)).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_unit_stock_entry)).performClick()
        assertEquals(1, inspectionOpens)
        assertEquals(1, stockOpens)
    }

    @Test fun maintenanceDetailShowsOnlyOpenRegisteredPenRecordsAndOfflineSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun issue(id: String, pen: String, status: String) = DeviceInspection(id, "farm", pen,
            "Feed", null, status, null, null, null, null, null)
        val maintenance = HomeUnitMaintenance("unit-7", setOf("P1", "P2"), listOf(
            issue("open", "P1", "Fault"), issue("resolved", "P2", "Normal")))
        var openedPen: String? = null
        compose.setContent { SentriAppTheme {
            HomeUnitMaintenanceDetail(HomeUnitOverviewState(unitId = "unit-7",
                maintenance = CachedResult(maintenance, DataSource.CACHE, 1)), onRetry = {},
                onOpenFault = { openedPen = it.penCode })
        } }
        compose.onNodeWithText(context.getString(R.string.connected_unit_maintenance_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_code, "P1")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_code, "P2")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connected_unit_old_snapshot)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_code, "P1")).performClick()
        assertEquals("P1", openedPen)
    }

    @Test fun stockDetailShowsEachOccupiedPenAndUnassignedSeparately() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roster = HomeUnitPenRoster("unit-7", 4, 1, listOf(
            HomePenCount("Z-A-7-P1", 1, 2), HomePenCount("Z-A-7-P2", 2, 1)), listOf(
            HomeRegisteredPen(1, "Z-A-7-P1", 1), HomeRegisteredPen(2, "Z-A-7-P2", 2),
            HomeRegisteredPen(3, "Z-A-7-P3", 3)))
        var openedPen: String? = null
        compose.setContent { SentriAppTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            HomeUnitStockDetail(HomeUnitOverviewState(unitId = "unit-7",
                penRoster = CachedResult(roster, DataSource.CACHE, 1)), onRetry = {},
                onOpenPen = { openedPen = it })
            }
        } }
        compose.onNodeWithText(context.getString(R.string.connected_unit_stock_numbers, 4, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_number, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_number, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_empty_pens, 1)).assertIsDisplayed()
        compose.onNodeWithText("Z-A-7-P3").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_empty_pen_count)).assertIsDisplayed()
        compose.onNodeWithText("Z-A-7-P1").performScrollTo().performClick()
        assertEquals("Z-A-7-P1", openedPen)
        compose.onNodeWithText("Z-A-7-P3").performScrollTo().performClick()
        assertEquals("Z-A-7-P3", openedPen)
        compose.onNodeWithText(context.getString(R.string.connected_unit_pen_unassigned, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_old_snapshot)).assertIsDisplayed()
    }
}
