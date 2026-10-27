package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.HomePenCount
import com.sentri.nativeapp.home.HomeRegisteredPen
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeUnitMaintenance
import com.sentri.nativeapp.home.HomeUnitOverviewState
import com.sentri.nativeapp.inspection.InspectionWalkScreen
import com.sentri.nativeapp.inspection.InspectionLens
import com.sentri.nativeapp.inspection.CrossUnitScanTransferSheet
import com.sentri.nativeapp.inspection.InspectionWalkState
import com.sentri.nativeapp.inspection.TreatmentSelectionReview
import com.sentri.nativeapp.inspection.TreatmentSelectionExclusion
import com.sentri.nativeapp.inspection.TreatmentSelectionProblem
import com.sentri.nativeapp.inspection.TreatmentExclusionReason
import com.sentri.nativeapp.inspection.LocalCheckInReceipt
import com.sentri.nativeapp.inspection.InspectionSelection
import com.sentri.nativeapp.inspection.ScannedPig
import com.sentri.nativeapp.inspection.HealthDisease
import com.sentri.nativeapp.inspection.HealthSymptom
import com.sentri.nativeapp.inspection.HealthReferenceCatalog
import com.sentri.nativeapp.inspection.LocationInventoryPig
import com.sentri.nativeapp.inspection.LocationInventorySnapshot
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.inspection.RecentUnitCheckInsPage
import com.sentri.nativeapp.inspection.UnitInspectionRecord
import com.sentri.nativeapp.inspection.DeviceInspection
import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.PigFilter
import com.sentri.nativeapp.pigs.PigProfileOrder
import com.sentri.nativeapp.pigs.PigProfilePage
import com.sentri.nativeapp.pigs.PigProfileRequest
import com.sentri.nativeapp.pigs.UnitPigProfileRoster
import com.sentri.nativeapp.pigs.PigHealthHistory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class InspectionWalkScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stageFilterShowsMatchesBeyondTheFirstPageAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val sick = PigProfileFixture.pig.copy(batch = PigProfileFixture.pig.batch?.copy(
            farmId = PigProfileFixture.scope.farmId))
        val healthy = sick.copy(id = "healthy", earNotch = "HealthyTag",
            breederProductionStatus = "PREGNANT",
            currentBatchId = "foreign-batch",
            batch = sick.batch?.copy(id = "foreign-batch", serial = "Foreign", farmId = "other"),
            health = sick.health!!.copy(diseases = emptyList(), symptoms = emptyList()),
            semen = sick.semen?.copy(boarId = "healthy"))
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            page = CachedResult(PigProfilePage(request, listOf(healthy), 2), DataSource.NETWORK, 1),
            completeRoster = CachedResult(UnitPigProfileRoster(unit.id, listOf(healthy, sick), 2),
                DataSource.NETWORK, 2)))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onFilterApply = { stage, condition, batch ->
                    state = state.copy(stageFilter = stage, conditionFilter = condition,
                        batchFilter = batch)
                })
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_filter_open)).performClick()
        compose.onNodeWithText(context.getString(R.string.biz_production_mated)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_show)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_result, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText("Notch:A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HealthyTag").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_clear)).performClick()
        compose.onNodeWithText("HealthyTag").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_filter_open)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_batch_value, "SN:Case"))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_batch_value, "Foreign"))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_filter_show)).performClick()
        compose.onNodeWithText("Notch:A").performScrollTo().assertIsDisplayed()
    }

    @Test fun healthLensUsesCompleteRosterBeyondTheCurrentPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig
        val healthy = pig.copy(id = "healthy", earNotch = "HealthyTag",
            health = pig.health!!.copy(diseases = emptyList(), symptoms = emptyList()),
            semen = pig.semen?.copy(boarId = "healthy"))
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            page = CachedResult(PigProfilePage(request, listOf(healthy), 2), DataSource.NETWORK, 1),
            lens = InspectionLens.HEALTH,
            completeRoster = CachedResult(UnitPigProfileRoster(unit.id, listOf(healthy, pig), 2),
                DataSource.CACHE, 2))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_lens_health_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_health_lens_offline)).assertIsDisplayed()
        compose.onNodeWithText("Notch:A").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HealthyTag").assertDoesNotExist()
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_offline)).assertIsDisplayed()
    }

    @Test fun checkInReceiptDistinguishesPhoneSaveFromServerConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = { state = state.copy(localCheckIn = LocalCheckInReceipt(
                    "operation", 1_780_000_000_000L, CommandStatus.QUEUED)) }, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_finish)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_local_check_in_title)).assertIsDisplayed()
        val unitName = if (context.resources.configuration.locales[0].language == "zh") unit.nameCn else unit.nameEn
        compose.onNodeWithText(context.getString(R.string.inspection_local_check_in_unit, unitName)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.inspection_saved_local)).assertCountEquals(2)
        compose.runOnIdle { state = state.copy(localCheckIn = state.localCheckIn!!.copy(status = CommandStatus.SYNCED),
            earlierCheckInsWaiting = 1, earlierCheckInsNeedReview = 2) }
        compose.onNodeWithText(context.getString(R.string.inspection_local_check_in_server_note)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.inspection_earlier_check_ins_waiting, 1)).assertCountEquals(2)
        compose.onAllNodesWithText(context.getString(R.string.inspection_earlier_check_ins_review, 2)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_again)).assertIsDisplayed()
    }

    @Test fun failedLocalCheckInKeepsFinishActionForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit))
        var attempts = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = { attempts++; state = state.copy(saveFailed = true) }, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_finish)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.inspection_save_failed)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_finish)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_again)).assertDoesNotExist()
        assertEquals(1, attempts)
    }

    @Test fun unitEquipmentEntryUsesOnlyTheCurrentUnitAndOpensItsFault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val issue = DeviceInspection("issue", unit.farmId, "Z-S-U-P2", "Water", "Drink line",
            "Fault", "Low pressure", "2026-10-21T09:41:00+08:00", null, null, null)
        val maintenance = CachedResult(HomeUnitMaintenance(unit.id, setOf(issue.penCode), listOf(issue)),
            DataSource.CACHE, 1)
        var overview by mutableStateOf(HomeUnitOverviewState(PigProfileFixture.lease, "other",
            maintenance = maintenance))
        var opened: String? = null
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, InspectionWalkState(PigProfileFixture.lease, unit),
                onRefresh = {}, onPrevious = {}, onNext = {}, onCheckIn = {}, onBack = {},
                unitMaintenance = overview, onMaintenanceFaultOpen = { opened = it.id })
        } }
        compose.onNodeWithText(context.getString(R.string.connected_unit_maintenance_title)).assertDoesNotExist()
        compose.runOnIdle { overview = overview.copy(unitId = unit.id) }
        compose.onNodeWithText(context.getString(R.string.connected_unit_maintenance_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_unit_old_snapshot)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.connected_unit_maintenance_count, 1))
            .assertCountEquals(2)
        compose.onNodeWithText("Drink line").performClick()
        assertEquals(issue.id, opened)
    }

    @Test fun maintenanceFaultDeepLinkOpensRecordAndExplainsWhenItDisappears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val code = "Z-S-U-P2"
        val fault = DeviceInspection("fault", unit.farmId, code, "Water", null,
            "Fault", "水压低", "2026-10-21T09:41:00+08:00", null, null, null)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            penCode = code, penDevices = CachedResult(listOf(fault), DataSource.NETWORK, 1)))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, focusedFaultId = fault.id)
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_resolve_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.runOnIdle { state = state.copy(penDevices = CachedResult(emptyList(), DataSource.NETWORK, 2)) }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_no_longer_here)).assertIsDisplayed()
    }

    @Test fun crossUnitScanRequiresDestinationPenAndCannotSelectBeforeVerifiedTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(location = PigProfileFixture.pig.location!!.copy(unitId = "other"))
        val roster = HomeUnitPenRoster("unit", 0, 0, emptyList(),
            listOf(HomeRegisteredPen(2, "Z-S-U-P2", 2)))
        compose.setContent { SentriAppTheme {
            CrossUnitScanTransferSheet(unit, pig, CachedResult(roster, DataSource.NETWORK, 1),
                penRosterLoading = false, penRosterFailed = false, onDismiss = {}, onRetryPens = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_and_select)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_number, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_review,
            "Z-S-U-P2", context.getString(R.string.inspection_scan_transfer_and_select))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_submit)).assertIsNotEnabled()
    }

    @Test fun crossUnitScanAlsoOffersTransferWithoutSelectingThePig() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(location = PigProfileFixture.pig.location!!.copy(unitId = "other"))
        val roster = HomeUnitPenRoster("unit", 0, 0, emptyList(),
            listOf(HomeRegisteredPen(2, "Z-S-U-P2", 2)))
        compose.setContent { SentriAppTheme {
            CrossUnitScanTransferSheet(unit, pig, CachedResult(roster, DataSource.NETWORK, 1),
                penRosterLoading = false, penRosterFailed = false, onDismiss = {}, onRetryPens = {})
        } }
        compose.onNode(hasText(context.getString(R.string.inspection_scan_transfer_only), substring = false)
            and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_number, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_review,
            "Z-S-U-P2", context.getString(R.string.inspection_scan_transfer_only))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_submit)).assertIsNotEnabled()
    }

    @Test fun penMapShowsEveryRegisteredPenAndOpensAnEmptyPen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val roster = HomeUnitPenRoster("unit", 2, 0,
            listOf(HomePenCount("Z-S-U-P1", 1, 2)),
            listOf(HomeRegisteredPen(1, "Z-S-U-P1", 1), HomeRegisteredPen(2, "Z-S-U-P2", 2)))
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            penRoster = CachedResult(roster, DataSource.CACHE, 1))
        var requested = 0
        var opened: String? = null
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onPenMapOpen = { requested++ }, onPenOpen = { opened = it })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_go_to_pen)).performClick()
        assertEquals(1, requested)
        compose.onNodeWithText(context.getString(R.string.inspection_pen_map_offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_map_vacant)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_number, 2)).performClick()
        assertEquals("Z-S-U-P2", opened)
    }

    @Test fun oldPenSnapshotDoesNotPretendToIncludeEmptyPens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val roster = HomeUnitPenRoster("unit", 2, 0, listOf(HomePenCount("Z-S-U-P1", 1, 2)))
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            penRoster = CachedResult(roster, DataSource.CACHE, 1))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_go_to_pen)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_map_old_snapshot)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_number, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_number, 2)).assertDoesNotExist()
    }

    @Test fun unitRosterShowsRealPenAndConditionNamesWithoutInventedStageDays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = "unit")
        val page = PigProfilePage(request, listOf(PigProfileFixture.pig), 1)
        val state = InspectionWalkState(PigProfileFixture.lease, unit, CachedResult(page, DataSource.CACHE, 1),
            latestCheckIn = CachedResult(UnitCheckInSummary("check-in", PigProfileFixture.scope.farmId,
                unit.locationCode, "2026-10-21T09:41:00+08:00", "Worker"), DataSource.CACHE, 1))
        var checkIns = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {}, onCheckIn = { checkIns++ }, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_offline_page)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_last_check_in), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_last_check_in_offline), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_page_range, 1, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_conditions)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_temperature,
            PigProfileFixture.health.temperature!!.toPlainString())).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_finish)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_explanation)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in)).performClick()
        assertEquals(1, checkIns)
    }

    @Test fun checkInHistoryKeepsServerRecordsDistinctFromQueuedWalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val record = UnitCheckInSummary("check-1", PigProfileFixture.scope.farmId,
            unit.locationCode, "2026-10-21T09:41:00+08:00", "Worker")
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            outstandingCheckIns = listOf(
                LocalCheckInReceipt("local-new", 1_780_000_000_001L, CommandStatus.QUEUED),
                LocalCheckInReceipt("local-old", 1_780_000_000_000L, CommandStatus.UNCERTAIN)),
            recentCheckIns = CachedResult(RecentUnitCheckInsPage(listOf(record), 0, false), DataSource.CACHE, 1))
        var opened = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onCheckInHistoryOpen = { opened++ })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_open)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_local_check_ins_heading, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_saved_local)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_needs_review)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_server_check_ins_heading)).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_offline)).assertIsDisplayed()
        compose.onNodeWithText("Worker").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_scope)).assertIsDisplayed()
    }

    @Test fun checkInHistoryOpensOnlyRecordedDetailAndReturnsToList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val entry = UnitCheckInSummary("check-1", PigProfileFixture.scope.farmId,
            unit.locationCode, "2026-10-21T09:41:00+08:00", "Worker")
        val record = UnitInspectionRecord("check-1", PigProfileFixture.scope.farmId, unit.locationCode,
            entry.createdAt, null, null, null, null, null, null, null, null, null,
            null, null, null, null)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            recentCheckIns = CachedResult(RecentUnitCheckInsPage(listOf(entry), 0, false), DataSource.NETWORK, 1)))
        var opened: String? = null
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onCheckInHistoryOpen = {},
                onCheckInDetailOpen = { id ->
                    opened = id
                    state = state.copy(checkInDetailId = id,
                        checkInDetail = CachedResult(record, DataSource.CACHE, 1))
                }, onCheckInDetailClose = { state = state.copy(checkInDetailId = null, checkInDetail = null) })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_open)).performClick()
        compose.onNodeWithText("Worker").performClick()
        assertEquals("check-1", opened)
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_detail_no_values)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_check_in_history_offline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText("Worker").assertIsDisplayed()
    }

    @Test fun scannerPageShowsDisconnectedStateAndReturnsToWalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val state = InspectionWalkState(PigProfileFixture.lease, unit)
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_scan_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_no_reader)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_pair_reader)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_title)).assertIsDisplayed()
    }

    @Test fun scannedSelectionCanOpenActionsAfterReturningToWalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(earTag = "SCAN-A")
        val second = pig.copy(id = "scan-b", earTag = "SCAN-B")
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            selectedPigs = mapOf(pig.id to pig), scannedPigs = listOf(ScannedPig(second, DataSource.CACHE, 2),
                ScannedPig(pig, DataSource.NETWORK, 1)))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_scan_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_no_reader)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_pen_count, 2)).assertIsDisplayed()
        compose.onAllNodesWithText("Z-S-U-R-1").assertCountEquals(1)
        compose.onNodeWithText("SCAN-A").assertIsDisplayed()
        compose.onNodeWithText("SCAN-B").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_offline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_action_treatment_preview)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_action_unavailable, 8)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_action_condition)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.inspection_action_not_connected))[0]
            .assertIsDisplayed()
    }

    @Test fun inactiveSelectionCannotOpenTreatmentPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(lifecycle = "DEAD")
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            selectedPigs = mapOf(pig.id to pig))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_action_excluded_pigs, 1))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_action_tile_treat)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_action_unavailable, 9)).performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_no_active_pigs))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun findPigSearchesFarmGroupsUnitsAndReturnsToResultsAfterOpeningRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = "unit")
        val page = PigProfilePage(request, listOf(PigProfileFixture.pig), 1)
        val other = PigProfileFixture.pig.copy(id = "other", earNotch = "Notch:B",
            location = PigProfileFixture.pig.location!!.copy(unitId = "other", unitLocation = "Z-S-O",
                unit = com.sentri.nativeapp.pigs.PigLocationName("O", "八单元", "Unit 8")))
        val searchRequest = PigProfileRequest(PigFilter(search = "Notch", pageSize = 30),
            lifecycles = setOf(com.sentri.nativeapp.pigs.PigLifecycle.ACTIVE),
            order = PigProfileOrder.LOCATION_IDENTITY)
        val results = PigProfilePage(searchRequest, listOf(other, PigProfileFixture.pig), 2)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(page, DataSource.NETWORK, 1)))
        var query: String? = null
        var penReads = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onFind = {
                    query = it
                    state = state.copy(searchQuery = it,
                        searchPage = if (it.isBlank()) null else CachedResult(
                            if (it == "909") results.copy(pigs = emptyList(), total = 0) else results,
                            DataSource.NETWORK, 1))
                }, onPenMapOpen = { penReads++ }, onPigSelection = { id ->
                    val pig = results.pigs.first { it.id == id }
                    state = state.copy(selectedPigs = if (id in state.selectedPigs)
                        state.selectedPigs - id else state.selectedPigs + (id to pig))
                })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_find_pig)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_search_hint_all_units)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_search_count, 2)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_find_hint)).performTextInput("Notch")
        compose.waitUntil(3_000) { query == "Notch" }
        assertEquals("Notch", query)
        compose.onNodeWithText(context.getString(R.string.inspection_search_count, 2)).assertIsDisplayed()
        val otherName = if (context.resources.configuration.locales[0].language == "zh") "八单元" else "Unit 8"
        compose.onNodeWithText(otherName).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_select_pig, "Notch:B"))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_select_pig, "Notch:A"))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selection_scope, "Unit 7")).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_search_count, 2)).assertIsDisplayed()
        assertEquals(setOf(PigProfileFixture.pig.id), state.selectedPigs.keys)
        compose.onNodeWithText("Notch:B").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_location, otherName,
            other.location!!.penLocation)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_search_count, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_scan_move_into_unit)).performClick()
        assertEquals(1, penReads)
        compose.onNodeWithText(context.getString(R.string.inspection_scan_transfer_and_select)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_search_cancel)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_search_count, 2)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_search_clear),
            useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_search_hint_all_units)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_find_hint)).performTextInput("909")
        compose.waitUntil(3_000) { query == "909" }
        compose.onNodeWithText(context.getString(R.string.inspection_search_no_match, "909")).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_scan_title),
            useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_search_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_walk_title)).assertIsDisplayed()
    }

    @Test fun deletedPigDoesNotShowHealthFromAnOlderRoster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = "unit")
        val page = PigProfilePage(request, listOf(PigProfileFixture.pig), 1)
        val state = InspectionWalkState(PigProfileFixture.lease, unit, CachedResult(page, DataSource.CACHE, 1),
            pigDetailId = PigProfileFixture.pig.id,
            pigDetail = CachedResult(null, DataSource.NETWORK, 2))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_missing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_temperature,
            PigProfileFixture.health.temperature!!.toPlainString())).assertDoesNotExist()
    }

    @Test fun pigDetailShowsLegacyHealthChangesWithoutInventingFindingCare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(health = PigProfileFixture.health.copy(history = listOf(
            PigHealthHistory(7, "2026-10-21T09:41:00+08:00", "SymptomAdded", "Priority", "持续咳嗽",
                JsonValue("""[{"name_cn":"咳嗽","name_en":"Coughing"}]"""), JsonValue("[]"),
                "operator", PigProfileFixture.health.triageOperator),
            PigHealthHistory(8, "2026-10-21T10:00:00+08:00", "TriageLevelSet", "Emergency", null,
                null, null, "operator", PigProfileFixture.health.triageOperator))))
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(request, listOf(pig), 1), DataSource.NETWORK, 1),
            pigDetailId = pig.id, pigDetail = CachedResult(pig, DataSource.CACHE, 2))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_legacy_pig_triage,
            context.getString(R.string.biz_triage_priority))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_legacy_pig_triage_note)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_condition_care_unavailable)).assertExists()
        val chinese = context.resources.configuration.locales[0].language == "zh"
        compose.onNodeWithText(if (chinese) "病" else "Disease").assertExists()
        compose.onNodeWithText(if (chinese) "症" else "Symptom").assertExists()
        compose.onNodeWithText(context.getString(R.string.inspection_unknown_symptom)).assertExists()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_open, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_legacy)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_symptom_added)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_health_history_triage_changed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_legacy_pig_triage,
            context.getString(R.string.biz_triage_emergency))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_legacy_pig_triage,
            context.getString(R.string.biz_triage_priority))).assertDoesNotExist()
        val symptomName = if (context.resources.configuration.locales[0].language == "zh") "咳嗽" else "Coughing"
        compose.onNodeWithText(symptomName).assertIsDisplayed()
        compose.onNodeWithText("持续咳嗽").assertIsDisplayed()
    }

    @Test fun recordedConditionOpensTreatmentPreviewAndReturnsToItsDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(request, listOf(pig), 1), DataSource.NETWORK, 1),
            pigDetailId = pig.id, pigDetail = CachedResult(pig, DataSource.NETWORK, 2))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        val diseaseName = if (context.resources.configuration.locales[0].language == "zh") "病" else "Disease"
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(diseaseName).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_diseases)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_condition_care_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_title)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_condition_unlinked))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_save)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back),
            useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_diseases)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_conditions)).assertIsDisplayed()
    }

    @Test fun cachedConditionKeepsItsSourceAndRefreshDetectsChangedRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val original = PigProfileFixture.pig
        val disease = original.health!!.diseases.first()
        val pig = original.copy(health = original.health!!.copy(diseases = listOf(disease.copy(
            detail = disease.detail!!.copy(categoryCn = "测试分类", categoryEn = "Test category",
                descriptionCn = "目录描述", descriptionEn = "Catalogue description")))))
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(request, listOf(pig), 1), DataSource.NETWORK, 1),
            pigDetailId = pig.id, pigDetail = CachedResult(pig, DataSource.CACHE, 2)))
        var reads = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onPigOpen = {
                    reads++
                    if (reads > 1) state = state.copy(pigDetail = CachedResult(
                        pig.copy(health = pig.health!!.copy(diseases = emptyList())), DataSource.NETWORK, 3))
                })
        } }
        val chinese = context.resources.configuration.locales[0].language == "zh"
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(if (chinese) "病" else "Disease").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_offline)).assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "测试分类" else "Test category").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "目录描述" else "Catalogue description").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_refresh)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_condition_changed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_detail_offline)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.treatment_draft_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.home_close)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_conditions)).assertDoesNotExist()
    }

    @Test fun singlePigActionsOpenTreatmentAndReturnToTheSameRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(request, listOf(pig), 1), DataSource.NETWORK, 1),
            pigDetailId = pig.id, pigDetail = CachedResult(pig, DataSource.NETWORK, 2))
        var treatmentOpens = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onTreatmentOpen = { treatmentOpens++ })
        } }
        compose.onNodeWithText("Notch:A").performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_action_tile_treat)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_action_treat_accessible,
            context.getString(R.string.inspection_action_tile_treat),
            context.getString(R.string.treatment_draft_title))).performClick()
        assertEquals(1, treatmentOpens)
        compose.onNodeWithText(context.getString(R.string.treatment_draft_save)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back),
            useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_recorded_conditions)).assertIsDisplayed()
    }

    @Test fun catalogueSearchShowsReadOnlyOfflineReferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val state = InspectionWalkState(PigProfileFixture.lease, unit,
            catalog = CachedResult(HealthReferenceCatalog(
                diseases = listOf(HealthDisease("d", "蓝耳病", "PRRS", "蓝耳", "PRRS", "繁殖", "Reproductive", "", "")),
                symptoms = listOf(HealthSymptom("s", "咳嗽", "Coughing", "呼吸", "Breathing",
                    "咳嗽说明", "Cough description"))),
                DataSource.CACHE, 1))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_offline)).assertIsDisplayed()
        val chinese = context.resources.configuration.locales[0].language == "zh"
        compose.onNodeWithText("Coughing").assertDoesNotExist()
        compose.onNodeWithText(if (chinese) "呼吸" else "Breathing").performClick()
        compose.onNodeWithText(if (chinese) "咳嗽" else "Coughing").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "繁殖" else "Reproductive").performClick()
        compose.onNodeWithText(if (chinese) "蓝耳病" else "PRRS").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_search)).performTextInput("Cough")
        compose.onNodeWithText(if (chinese) "咳嗽" else "Coughing").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "蓝耳病" else "PRRS").assertDoesNotExist()
        compose.onNodeWithText(if (chinese) "咳嗽" else "Coughing").performClick()
        compose.onNodeWithText(if (chinese) "咳嗽说明" else "Cough description").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_search)).assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "咳嗽" else "Coughing").assertIsDisplayed()
        compose.onNodeWithText(if (chinese) "咳嗽" else "Coughing").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.home_close),
            useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_catalog_read_only)).assertDoesNotExist()
    }

    @Test fun penShowsSeparateRecordedHeadcountAndMarksOfflineSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val code = requireNotNull(PigProfileFixture.pig.location?.penLocation)
        val inventory = LocationInventorySnapshot(code, listOf(
            LocationInventoryPig("tagged", unit.farmId, code, "ACTIVE", "A001", null, "batch"),
            LocationInventoryPig("untagged", unit.farmId, code, "ACTIVE", null, null, "batch")))
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit, penCode = code,
            penInventory = CachedResult(inventory, DataSource.CACHE, 1)))
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {})
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_pigs, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_identity, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_offline)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(penCode = "different-pen") }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_pigs, 2)).assertDoesNotExist()
    }

    @Test fun countPreviewPreservesUnassignedHeadcountAndKeepsRecordedCountsUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "单元", "Unit", "Z-S-U", "AnimalHousing")
        val code = requireNotNull(PigProfileFixture.pig.location?.penLocation)
        fun row(id: String, batch: String? = null, tag: String? = null) =
            LocationInventoryPig(id, unit.farmId, code, "ACTIVE", tag, null, batch)
        val request = PigProfileRequest(PigFilter(penLocation = code), unitId = unit.id)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit, penCode = code,
            penPage = CachedResult(PigProfilePage(request, listOf(PigProfileFixture.pig), 3), DataSource.NETWORK, 1),
            penInventory = CachedResult(LocationInventorySnapshot(code,
                listOf(row("tag", tag = "tag"), row("u1"), row("u2"))), DataSource.NETWORK, 1)))
        var retries = 0
        var hideKeyboard: () -> Unit = {}
        compose.setContent { SentriAppTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            androidx.compose.runtime.SideEffect { hideKeyboard = { keyboard?.hide() } }
            InspectionWalkScreen(unit, state, {}, {}, {}, {}, {}, onPenInventoryRetry = { retries++ })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_pigs, 3)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_count_unassigned)).assertIsDisplayed()
        val decreaseTotal = context.getString(R.string.inspection_count_decrease,
            context.getString(R.string.inspection_count_total))
        compose.onNodeWithContentDescription(decreaseTotal).performClick().performClick().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_count_delta, 3, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_count_other)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_count_other_note)).performTextInput("Counted again")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithText(context.getString(R.string.inspection_count_save)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_inventory_pigs, 3)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(decreaseTotal).assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(penInventory = CachedResult(LocationInventorySnapshot(code,
                listOf(row("i1", tag = "i1"), row("i2", tag = "i2"))), DataSource.NETWORK, 2))
        }
        compose.onNodeWithText(context.getString(R.string.inspection_count_changed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_count_reload)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_count_no_groups)).assertIsDisplayed()
        compose.onNodeWithContentDescription(decreaseTotal).assertDoesNotExist()
        compose.runOnIdle {
            val batch = requireNotNull(PigProfileFixture.pig.batch)
            state = state.copy(penPage = CachedResult(PigProfilePage(request, listOf(
                PigProfileFixture.pig.copy(currentBatchId = "a", batch = batch.copy(id = "a", serial = "31", farmId = unit.farmId)),
                PigProfileFixture.pig.copy(id = "b", currentBatchId = "b", batch = batch.copy(id = "b", serial = "32", farmId = unit.farmId))), 4),
                DataSource.NETWORK, 3), penBatches = listOf("a" to "31", "b" to "32").associate { (id, sn) ->
                    id to CachedResult<com.sentri.nativeapp.production.ProductionBatch?>(
                        com.sentri.nativeapp.production.ProductionBatch(id, unit.farmId, sn,
                            com.sentri.nativeapp.production.ProductionBatchStatus.STARTED, "line", null, emptyList()),
                        DataSource.CACHE, 3) },
                penInventory = CachedResult(LocationInventorySnapshot(code,
                listOf(row("i", tag = "i"), row("a1", "a"), row("a2", "a"), row("b1", "b"))), DataSource.NETWORK, 3))
        }
        compose.onNodeWithText(context.getString(R.string.inspection_count_reload)).performClick()
        compose.onNodeWithContentDescription(decreaseTotal).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_count_batches_offline)).assertIsDisplayed()
        val batch31 = context.getString(R.string.inspection_count_batch, "31")
        val decreaseBatch = context.getString(R.string.inspection_count_decrease, batch31)
        compose.onNodeWithContentDescription(decreaseBatch).performClick().performClick().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_count_delta, 4, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_count_save)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(decreaseBatch).performScrollTo()
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            java.io.File(context.cacheDir, "inspection-count-icon-review.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
        compose.runOnIdle { state = state.copy(penBatches = emptyMap(), penBatchesFailed = true) }
        compose.onNodeWithText(context.getString(R.string.inspection_count_batches_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(decreaseBatch).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_count_recheck_details)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun penNoteEntryKeepsUnsavedTextAndShowsOnlyLocalDraftSaving() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "单元", "Unit", "Z-S-U", "AnimalHousing")
        val code = requireNotNull(PigProfileFixture.pig.location?.penLocation)
        val request = PigProfileRequest(PigFilter(penLocation = code), unitId = unit.id)
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            penCode = code, penPage = CachedResult(PigProfilePage(request, listOf(PigProfileFixture.pig), 1),
                DataSource.CACHE, 1)))
        var hideKeyboard: () -> Unit = {}
        compose.setContent { SentriAppTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            androidx.compose.runtime.SideEffect { hideKeyboard = { keyboard?.hide() } }
            InspectionWalkScreen(unit, state, {}, {}, {}, {}, {},
                onPenNoteOpen = { pen ->
                    assertEquals(code, pen)
                    state = state.copy(penNoteDrafts = state.penNoteDrafts +
                        (pen to (state.penNoteDrafts[pen] ?: com.sentri.nativeapp.inspection.PenNoteEditor(loaded = true))))
                }, onPenNoteChange = { pen, text ->
                    state = state.copy(penNoteDrafts = state.penNoteDrafts +
                        (pen to state.penNoteDrafts.getValue(pen).copy(text = text)))
                }, onPenNoteSave = { pen ->
                    val editor = state.penNoteDrafts.getValue(pen)
                    state = state.copy(penNoteDrafts = state.penNoteDrafts +
                        (pen to editor.copy(saved = com.sentri.nativeapp.inspection.PenNoteDraft(editor.text, 1))))
                }, onPenNoteClear = { pen ->
                    state = state.copy(penNoteDrafts = state.penNoteDrafts +
                        (pen to com.sentri.nativeapp.inspection.PenNoteEditor(loaded = true)))
                })
        } }
        val title = context.getString(R.string.inspection_pen_note_title)
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_save_draft)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_field)).performTextInput("Fan draft")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Fan draft").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_save_draft)).performScrollTo().performClick()
        compose.onNode(hasText(context.getString(R.string.inspection_pen_note_saved)) and !hasText(title))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_save_draft)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_submit)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_clear)).performScrollTo().performClick()
        compose.onNodeWithText("Fan draft").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_note_save_draft)).assertIsNotEnabled()
    }

    @Test fun penOpensItsOwnScopedRosterWithoutClaimingTotalHeadcount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val code = requireNotNull(PigProfileFixture.pig.location?.penLocation)
        val walkRequest = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val penRequest = PigProfileRequest(PigFilter(penLocation = code, pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val fault = DeviceInspection("fault", PigProfileFixture.scope.farmId, code, "Water", null,
            "Fault", "水压低", "2026-10-21T09:41:00+08:00", null, null, null)
        var deviceRetries = 0
        var hideKeyboard: () -> Unit = {}
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(walkRequest, listOf(PigProfileFixture.pig), 1), DataSource.NETWORK, 1)))
        compose.setContent { SentriAppTheme {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            androidx.compose.runtime.SideEffect { hideKeyboard = { keyboard?.hide() } }
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onPenOpen = { opened ->
                    assertEquals(code, opened)
                    state = state.copy(penCode = opened,
                        penPage = CachedResult(PigProfilePage(penRequest, listOf(PigProfileFixture.pig), 1),
                            DataSource.CACHE, 2),
                        penDevices = CachedResult(listOf(fault), DataSource.CACHE, 2))
                }, onPenBack = { state = state.copy(penCode = null, penPage = null) },
                onPenDevicesRetry = { deviceRetries++ }, onFaultDraftChange = { pen, draft ->
                    state = state.copy(faultDrafts = if (draft.empty) state.faultDrafts - pen
                        else state.faultDrafts + (pen to draft),
                        faultDraftSavedPens = state.faultDraftSavedPens - pen)
                }, onFaultDraftSave = { pen ->
                    assertEquals(code, pen)
                    state = state.copy(faultDraftSavedPens = state.faultDraftSavedPens + pen)
                })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_code, code)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_read_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_faults_offline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_contract_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_choose_photos)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_submit)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_other)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_other_name)).performTextInput("Fan draft")
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_description)).performTextInput("Noise draft")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithText(context.getString(R.string.inspection_fault_draft_save)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_draft_saved)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_draft_save)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_submit)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_title)).performClick()
        compose.onNodeWithText("Fan draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Noise draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_clear_draft))
            .performScrollTo().performClick()
        compose.onNodeWithText("Fan draft").assertDoesNotExist()
        compose.onNodeWithText("Noise draft").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_fault_report_existing))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_resolve_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_water)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_fault_resolve_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_pen_profile_count, 1)).assertIsDisplayed()
        compose.onNodeWithText("Notch:A").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(penDevicesFailed = true) }
        compose.onNodeWithText(context.getString(R.string.inspection_pen_faults_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_retry)).performClick()
        assertEquals(1, deviceRetries)
    }

    @Test fun selectingPigOpensSelectedPageWithoutRecordingInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val request = PigProfileRequest(PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = unit.id)
        val pig = PigProfileFixture.pig
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            CachedResult(PigProfilePage(request, listOf(pig), 1), DataSource.NETWORK, 1)))
        var treatmentOpens = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onPigSelection = { id ->
                    state = state.copy(selectedPigs = InspectionSelection.togglePig(state.selectedPigs,
                        listOf(pig), id, unit.id, PigProfileFixture.scope.farmId))
                }, onClearSelection = { state = state.copy(selectedPigs = emptyMap()) },
                onTreatmentOpen = { treatmentOpens++ },
                onTreatmentValidate = { state = state.copy(treatmentValidation = TreatmentSelectionReview(
                    listOf(pig), emptyList())) },
                onTreatmentReviewClose = { state = state.copy(treatmentValidation = null) })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_stage_days_unknown,
            context.getString(R.string.biz_production_mated)), substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_select_pig, "Notch:A")).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selection_scope, "Unit 7")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_action_treat_accessible,
            context.getString(R.string.inspection_action_tile_treat),
            context.getString(R.string.treatment_draft_title))).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_count, 1, 0))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_continue, 1))
            .performClick()
        assertEquals(1, treatmentOpens)
        compose.onNodeWithText(context.getString(R.string.treatment_draft_preview_warning)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_select_pig, "Notch:A")).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_select_pig, "Notch:A")).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_clear_selection)).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1)).assertDoesNotExist()
    }

    @Test fun multiPigTreatmentReviewShowsChangesAndRequiresExplicitContinue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig
        val moved = pig.copy(id = "moved", earNotch = "Notch:B")
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            selectedPigs = linkedMapOf(pig.id to pig, moved.id to moved)))
        var opened = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onTreatmentValidate = {
                    state = state.copy(treatmentValidation = TreatmentSelectionReview(listOf(pig),
                        listOf(TreatmentSelectionExclusion(moved, TreatmentExclusionReason.MOVED))))
                }, onTreatmentReviewClose = { state = state.copy(treatmentValidation = null) },
                onTreatmentOpen = { opened++ })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_action_treat_accessible,
            context.getString(R.string.inspection_action_tile_treat),
            context.getString(R.string.treatment_draft_title))).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_count, 1, 1))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_moved))
            .assertIsDisplayed()
        assertEquals(0, opened)
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_continue, 1))
            .performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(context.getString(R.string.treatment_draft_preview_warning))
            .assertIsDisplayed()
    }

    @Test fun offlineMultiPigValidationKeepsSelectionAndOffersRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unit = FarmUnit("unit", PigProfileFixture.scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig
        var state by mutableStateOf(InspectionWalkState(PigProfileFixture.lease, unit,
            selectedPigs = mapOf(pig.id to pig)))
        var checks = 0
        compose.setContent { SentriAppTheme {
            InspectionWalkScreen(unit, state, onRefresh = {}, onPrevious = {}, onNext = {},
                onCheckIn = {}, onBack = {}, onTreatmentValidate = {
                    checks++
                    state = state.copy(treatmentValidationProblem = TreatmentSelectionProblem.OFFLINE)
                }, onTreatmentReviewClose = { state = state.copy(treatmentValidationProblem = null) })
        } }
        compose.onNodeWithText(context.getString(R.string.inspection_actions_title)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.inspection_action_treat_accessible,
            context.getString(R.string.inspection_action_tile_treat),
            context.getString(R.string.treatment_draft_title))).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_offline))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_continue, 1))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.inspection_retry)).performClick()
        assertEquals(2, checks)
        compose.onNodeWithText(context.getString(R.string.inspection_treatment_review_offline))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back),
            useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(R.string.inspection_selected_count, 1))
            .assertIsDisplayed()
    }
}
