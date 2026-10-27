package com.sentri.nativeapp.inspection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import com.sentri.nativeapp.R
import com.sentri.nativeapp.devices.AndroidScannerInput
import com.sentri.nativeapp.devices.ScannerKeyboard
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriField
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriIcon
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriAnimalRow
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeUnitMaintenanceDetail
import com.sentri.nativeapp.home.HomeUnitOverviewState
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.PigProfile
import com.sentri.nativeapp.pigs.PigHealthHistory
import com.sentri.nativeapp.pigs.PigOperator
import com.sentri.nativeapp.therapy.MedicineDirectoryState
import com.sentri.nativeapp.therapy.TreatmentDraftScreen
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampInstant
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** The current walk roster, farm-scoped find flow and independently saved unit check-in. */
private data class RecordedConditionRead(val pigId: String, val name: String, val kindLabel: String,
    val createdAt: String?, val operator: PigOperator?, val catalogId: String, val disease: Boolean,
    val category: String?, val description: String?)
private data class CatalogReadDetail(val name: String, val kind: String, val category: String?,
    val description: String?)

@Composable
fun InspectionWalkScreen(unit: FarmUnit, state: InspectionWalkState, onRefresh: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onCheckIn: () -> Unit, onBack: () -> Unit,
    onFind: (String) -> Unit = {}, onPreviousSearch: () -> Unit = {}, onNextSearch: () -> Unit = {},
    onPigOpen: (String) -> Unit = {}, onCatalogOpen: () -> Unit = {},
    onLensSelected: (InspectionLens) -> Unit = {}, onHealthLensRetry: () -> Unit = {},
    onFilterOpen: () -> Unit = {}, onFilterApply: (String?, String?, String?) -> Unit = { _, _, _ -> },
    onPenMapOpen: () -> Unit = {},
    unitMaintenance: HomeUnitOverviewState? = null,
    onMaintenanceRetry: () -> Unit = {}, onMaintenanceFaultOpen: (DeviceInspection) -> Unit = {},
    onPenOpen: (String) -> Unit = {}, onPenBack: () -> Unit = {},
    onPenPrevious: () -> Unit = {}, onPenNext: () -> Unit = {},
    onPigSelection: (String) -> Unit = {}, onPenSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onPenDevicesRetry: () -> Unit = {}, onCheckInHistoryOpen: () -> Unit = {},
    onFaultDraftChange: (String, DeviceFaultDraft) -> Unit = { _, _ -> },
    onFaultDraftSave: ((String) -> Unit)? = null, onFaultDraftRetry: () -> Unit = {},
    onPenNoteOpen: ((String) -> Unit)? = null,
    onPenNoteChange: (String, String) -> Unit = { _, _ -> },
    onPenNoteSave: (String) -> Unit = {}, onPenNoteClear: (String) -> Unit = {},
    onPenInventoryRetry: () -> Unit = {},
    onCheckInHistoryRefresh: () -> Unit = {}, onCheckInHistoryPrevious: () -> Unit = {},
    onCheckInHistoryNext: () -> Unit = {},
    onCheckInDetailOpen: (String) -> Unit = {}, onCheckInDetailRefresh: () -> Unit = {},
    onCheckInDetailClose: () -> Unit = {},
    onScannedTag: (String) -> Unit = {},
    focusedFaultId: String? = null,
    medicineDirectory: MedicineDirectoryState? = null,
    onTreatmentOpen: () -> Unit = {}, onRefreshMedicines: () -> Unit = {},
    onTreatmentValidate: () -> Unit = {}, onTreatmentReviewClose: () -> Unit = {}) {
    var selectedPig by remember { mutableStateOf<PigProfile?>(null) }
    var selectedCondition by remember { mutableStateOf<RecordedConditionRead?>(null) }
    LaunchedEffect(selectedPig?.id) { selectedCondition = null }
    var historyOpen by remember { mutableStateOf(false) }
    var confirmCheckIn by remember { mutableStateOf(false) }
    var checkInReceiptOpen by remember { mutableStateOf(false) }
    var checkInReceiptWaiting by remember { mutableStateOf(false) }
    var previousCheckInOperationId by remember { mutableStateOf<String?>(null) }
    var savedCheckInThisVisit by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var returnToFindAfterPig by remember { mutableStateOf(false) }
    var searchTransferCandidate by remember { mutableStateOf<PigProfile?>(null) }
    var penMapOpen by remember { mutableStateOf(false) }
    var maintenanceOpen by remember { mutableStateOf(false) }
    var catalogOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var draftStage by remember { mutableStateOf<String?>(null) }
    var draftCondition by remember { mutableStateOf<String?>(null) }
    var draftBatch by remember { mutableStateOf<String?>(null) }
    var catalogDetail by remember { mutableStateOf<CatalogReadDetail?>(null) }
    var selectedOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var singlePigActionsOpen by remember { mutableStateOf(false) }
    var treatmentPigs by remember { mutableStateOf<List<PigProfile>>(emptyList()) }
    var checkInHistoryOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var returnToFindAfterScan by remember { mutableStateOf(false) }
    var catalogQuery by remember { mutableStateOf("") }
    var expandedCatalogGroups by remember { mutableStateOf(emptySet<String>()) }
    var searchDraft by remember { mutableStateOf("") }
    val walkListState = rememberLazyListState()
    LaunchedEffect(unit.id, state.lease) {
        selectedPig = null
        selectedCondition = null
        historyOpen = false
        confirmCheckIn = false
        checkInReceiptOpen = false
        checkInReceiptWaiting = false
        previousCheckInOperationId = null
        savedCheckInThisVisit = false
        findOpen = false
        returnToFindAfterPig = false
        searchTransferCandidate = null
        penMapOpen = false
        maintenanceOpen = false
        catalogOpen = false
        filterOpen = false
        draftStage = null
        draftCondition = null
        draftBatch = null
        catalogDetail = null
        selectedOpen = false
        actionsOpen = false
        singlePigActionsOpen = false
        treatmentPigs = emptyList()
        checkInHistoryOpen = false
        scanOpen = false
        returnToFindAfterScan = false
    }
    LaunchedEffect(state.localCheckIn?.operationId, previousCheckInOperationId,
        checkInReceiptWaiting, state.saveFailed) {
        if (checkInReceiptWaiting && !state.saveFailed && state.localCheckIn != null &&
            state.localCheckIn.operationId != previousCheckInOperationId)
            savedCheckInThisVisit = true
    }
    val closePigDetail = {
        selectedPig = null
        selectedCondition = null
        singlePigActionsOpen = false
        if (returnToFindAfterPig) findOpen = true
        returnToFindAfterPig = false
    }
    BackHandler { when { treatmentPigs.isNotEmpty() -> treatmentPigs = emptyList();
        checkInHistoryOpen && state.checkInDetailId != null -> onCheckInDetailClose();
        checkInHistoryOpen -> checkInHistoryOpen = false;
        historyOpen -> historyOpen = false; selectedCondition != null -> selectedCondition = null;
        singlePigActionsOpen -> singlePigActionsOpen = false;
        selectedPig != null -> closePigDetail();
        searchTransferCandidate != null -> { searchTransferCandidate = null; findOpen = true };
        scanOpen -> { scanOpen = false; if (returnToFindAfterScan) findOpen = true;
            returnToFindAfterScan = false };
        actionsOpen && (state.treatmentValidationLoading || state.treatmentValidation != null ||
            state.treatmentValidationProblem != null) -> onTreatmentReviewClose();
        actionsOpen -> actionsOpen = false;
        checkInReceiptOpen -> checkInReceiptOpen = false;
        confirmCheckIn -> confirmCheckIn = false;
        findOpen -> findOpen = false; penMapOpen -> penMapOpen = false;
        maintenanceOpen -> maintenanceOpen = false;
        filterOpen -> filterOpen = false;
        catalogDetail != null -> { catalogDetail = null; catalogOpen = true };
        catalogOpen -> catalogOpen = false; selectedOpen -> selectedOpen = false;
        state.penCode != null -> onPenBack(); else -> onBack() } }
    val colors = SentriTheme.colors
    val scopedMaintenance = unitMaintenance?.takeIf { it.lease == state.lease && it.unitId == unit.id }
    val page = state.page?.value
    val healthRoster = state.completeRoster?.value
    val filteredRoster = healthRoster?.pigs?.filter { pig ->
        matchesWalkFilters(pig, state.stageFilter, state.conditionFilter, state.batchFilter)
    }
    val fullRosterView = state.lens == InspectionLens.HEALTH || state.stageFilter != null ||
        state.conditionFilter != null || state.batchFilter != null
    val visiblePigs = visibleWalkPigs(state)
    val groups = remember(visiblePigs) { visiblePigs.orEmpty().groupBy { it.location?.penLocation ?: "" } }
    LaunchedEffect(state.selectedPigs.size) {
        if (state.selectedPigs.isEmpty()) { selectedOpen = false; actionsOpen = false }
    }
    if (treatmentPigs.isNotEmpty()) {
        TreatmentDraftScreen(treatmentPigs, medicineDirectory, onRefreshMedicines,
            onBack = { treatmentPigs = emptyList() }, conditionName = selectedCondition?.name,
            fixedCondition = selectedCondition?.let { condition ->
                com.sentri.nativeapp.therapy.TreatmentConditionKey(
                    if (condition.disease) com.sentri.nativeapp.therapy.TreatmentConditionKind.DISEASE
                    else com.sentri.nativeapp.therapy.TreatmentConditionKind.SYMPTOM, condition.catalogId)
            })
    } else if (checkInHistoryOpen) {
        if (state.checkInDetailId != null)
            CheckInDetailPage(unit, state, onBack = onCheckInDetailClose,
                onRefresh = onCheckInDetailRefresh)
        else CheckInHistoryPage(unit, state, onBack = { checkInHistoryOpen = false },
            onRefresh = onCheckInHistoryRefresh, onPrevious = onCheckInHistoryPrevious,
            onNext = onCheckInHistoryNext, onOpen = onCheckInDetailOpen)
    } else if (historyOpen && selectedPig != null) {
        HealthHistoryPage(selectedPig!!, state.pigDetail.takeIf { state.pigDetailId == selectedPig?.id },
            onBack = { historyOpen = false })
    } else if (selectedOpen) {
        SelectedPigsPage(unit, state.selectedPigs.values.toList(), onBack = { selectedOpen = false },
            onDeselect = onPigSelection, onClear = onClearSelection,
            onActions = { actionsOpen = true })
    } else if (findOpen) {
        FindPigPage(unit, state, searchDraft, onQueryChange = { next ->
            searchDraft = next
            if (next.isBlank()) onFind("")
        }, onFind = onFind, onCancel = { findOpen = false },
            onScan = { findOpen = false; scanOpen = true; returnToFindAfterScan = true },
            onPrevious = onPreviousSearch, onNext = onNextSearch,
            onPigSelect = onPigSelection, onSelectedOpen = { selectedOpen = true },
            onActionsOpen = { actionsOpen = true },
            onPigOpen = { pig ->
                findOpen = false
                returnToFindAfterPig = true
                selectedPig = pig
                onPigOpen(pig.id)
            }, onTransfer = { pig ->
                findOpen = false
                searchTransferCandidate = pig
                onPenMapOpen()
            })
    } else if (scanOpen) {
        InspectionScanPage(unit, state, onBack = {
            scanOpen = false
            if (returnToFindAfterScan) findOpen = true
            returnToFindAfterScan = false
        }, onScannedTag = onScannedTag,
            onPigSelect = onPigSelection, onSelectedOpen = { selectedOpen = true },
            onActionsOpen = { actionsOpen = true },
            onLoadCurrentUnitPens = onPenMapOpen,
            onPigOpen = { pig -> selectedPig = pig; onPigOpen(pig.id) })
    } else if (state.penCode != null) {
        PenRosterPage(unit, state, onPenBack, onPenOpen, onPenPrevious, onPenNext,
            onSelect = onPigSelection, onSelectedOpen = { selectedOpen = true },
            onActionsOpen = { actionsOpen = true },
            onDevicesRetry = onPenDevicesRetry, onInventoryRetry = onPenInventoryRetry,
            onFaultDraftChange = onFaultDraftChange,
            onFaultDraftSave = onFaultDraftSave, onFaultDraftRetry = onFaultDraftRetry,
            onNoteOpen = onPenNoteOpen, onNoteChange = onPenNoteChange,
            onNoteSave = onPenNoteSave, onNoteClear = onPenNoteClear,
            focusedFaultId = focusedFaultId) { pig ->
            selectedPig = pig
            onPigOpen(pig.id)
        }
    } else Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inspection_walk_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(localUnitName(unit), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
            val refreshLabel = stringResource(R.string.inspection_refresh)
            Surface(onClick = onRefresh, modifier = Modifier.semantics { contentDescription = refreshLabel },
                shape = RoundedCornerShape(12.dp), color = colors.paper, border = BorderStroke(1.dp, colors.line)) {
                Text("↻", Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    fontFamily = SentriType.sans, fontSize = 24.sp, color = colors.ink)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), state = walkListState,
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scopedMaintenance != null) SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(stringResource(R.string.connected_unit_maintenance_title), when {
                            scopedMaintenance.maintenance != null -> stringResource(
                                R.string.connected_unit_maintenance_count,
                                scopedMaintenance.maintenance.value.open.size)
                            scopedMaintenance.maintenanceLoading -> stringResource(
                                R.string.connected_unit_maintenance_loading)
                            else -> stringResource(R.string.connected_unit_maintenance_failed)
                        }, "wrench", { maintenanceOpen = true })
                    }
                    SentriButton(stringResource(R.string.inspection_catalog_title), {
                        catalogQuery = ""
                        expandedCatalogGroups = emptySet()
                        catalogDetail = null
                        catalogOpen = true
                        onCatalogOpen()
                    }, Modifier.fillMaxWidth(), primary = false)
                    state.localCheckIn?.let { receipt -> SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(stringResource(R.string.inspection_local_check_in_open),
                            stringResource(checkInStatusText(receipt.status)), null,
                            onClick = { checkInReceiptWaiting = false; checkInReceiptOpen = true })
                    } }
                    EarlierCheckInNotices(state)
                    if (state.saveFailed) WalkMessage(stringResource(R.string.inspection_save_failed))
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.latestCheckIn?.let { LastCheckInSummary(it) }
                            if (state.latestCheckInLoading && state.latestCheckIn == null)
                                WalkMessage(stringResource(R.string.inspection_last_check_in_loading))
                            if (state.latestCheckInFailed)
                                WalkMessage(stringResource(R.string.inspection_last_check_in_failed))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SentriButton(stringResource(R.string.inspection_check_in_history_open), {
                                    checkInHistoryOpen = true
                                    onCheckInHistoryOpen()
                                }, Modifier.weight(1f), primary = false)
                                SentriButton(stringResource(if (savedCheckInThisVisit)
                                    R.string.inspection_check_in_again else R.string.inspection_finish),
                                    { confirmCheckIn = true }, Modifier.weight(1f), enabled = !state.saving)
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val allCount = if (fullRosterView && filteredRoster != null) filteredRoster.size else page?.total
                    val healthCount = healthRoster?.let { filteredRoster.orEmpty().count(::hasRecordedCondition) }
                    InspectionLensButton(stringResource(R.string.inspection_lens_all), allCount,
                        if (allCount == null) stringResource(R.string.inspection_lens_all)
                        else stringResource(R.string.inspection_lens_all_count, allCount),
                        { onLensSelected(InspectionLens.ALL) }, Modifier.weight(1f),
                        isSelected = state.lens == InspectionLens.ALL)
                    InspectionLensButton(stringResource(R.string.inspection_lens_health), healthCount,
                        if (healthCount == null) stringResource(R.string.inspection_lens_health)
                        else stringResource(R.string.inspection_lens_health_count, healthCount),
                        { onLensSelected(InspectionLens.HEALTH) }, Modifier.weight(1f),
                        isSelected = state.lens == InspectionLens.HEALTH)
                    InspectionLensButton(stringResource(R.string.inspection_lens_feed), null,
                        stringResource(R.string.inspection_lens_feed), {}, Modifier.weight(1f),
                        enabled = false)
                    SentriIconButton("filter", stringResource(R.string.inspection_filter_open), {
                        draftStage = state.stageFilter
                        draftCondition = state.conditionFilter
                        draftBatch = state.batchFilter
                        filterOpen = true
                        onFilterOpen()
                    })
                }
                Text(stringResource(R.string.inspection_lens_feed_unavailable),
                    fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
                val appliedCount = listOf(state.stageFilter, state.conditionFilter, state.batchFilter)
                    .count { it != null }
                if (appliedCount > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.inspection_filter_applied, appliedCount),
                        Modifier.weight(1f), fontFamily = SentriType.sans, fontSize = 12.sp,
                        color = colors.muted)
                    SentriButton(stringResource(R.string.inspection_filter_clear),
                        { onFilterApply(null, null, null) }, primary = false)
                }
                }
            }
            if (!fullRosterView) {
                if (state.page?.source == DataSource.CACHE)
                    item { WalkMessage(stringResource(R.string.inspection_offline_page)) }
                if (state.failed) item { WalkMessage(stringResource(R.string.inspection_read_failed)) }
                if (state.loading && page == null) item { WalkMessage(stringResource(R.string.inspection_loading)) }
            } else {
                if (state.completeRosterLoading)
                    item { WalkMessage(stringResource(if (state.lens == InspectionLens.HEALTH)
                        R.string.inspection_health_lens_loading else R.string.inspection_filter_loading)) }
                if (state.completeRosterFailed) item {
                    WalkMessage(stringResource(if (state.lens == InspectionLens.HEALTH)
                        R.string.inspection_health_lens_failed else R.string.inspection_filter_failed))
                    SentriButton(stringResource(if (state.lens == InspectionLens.HEALTH)
                        R.string.inspection_health_lens_retry else R.string.inspection_filter_retry), onHealthLensRetry,
                        Modifier.fillMaxWidth(), primary = false)
                }
                if (state.completeRoster?.source == DataSource.CACHE)
                    item { WalkMessage(stringResource(if (state.lens == InspectionLens.HEALTH)
                        R.string.inspection_health_lens_offline else R.string.inspection_filter_offline)) }
            }
            if (visiblePigs != null) {
                item {
                    val start = if (!fullRosterView) page!!.request.offset +
                        if (page.pigs.isEmpty()) 0 else 1 else 0
                    val end = if (!fullRosterView) page!!.request.offset + page.pigs.size
                        else visiblePigs.size
                    SentriSectionHeading(stringResource(R.string.inspection_pigs),
                        meta = if (!fullRosterView)
                            stringResource(R.string.inspection_page_range, start, end, page!!.total ?: end)
                        else if (state.stageFilter != null || state.conditionFilter != null ||
                            state.batchFilter != null)
                            stringResource(R.string.inspection_filter_result, end, healthRoster!!.total)
                        else stringResource(R.string.inspection_health_lens_result, end, healthRoster!!.total))
                }
                if (visiblePigs.isEmpty()) item { WalkMessage(stringResource(when {
                    state.stageFilter != null || state.conditionFilter != null ||
                        state.batchFilter != null -> R.string.inspection_filter_empty
                    state.lens == InspectionLens.ALL -> R.string.inspection_no_pigs
                    else -> R.string.inspection_health_lens_empty
                })) }
                groups.forEach { (penCode, pigs) ->
                    item(key = "pen-$penCode") {
                        SentriPanel(Modifier.fillMaxWidth()) {
                            Column {
                                val penNumber = pigs.firstOrNull()?.location?.penNumber
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val selected = pigs.count { it.id in state.selectedPigs }
                                    val penSelectLabel = stringResource(R.string.inspection_select_pen,
                                        if (penNumber != null && penNumber > 0) penNumber.toString() else penCode)
                                    TriStateCheckbox(when {
                                        selected == 0 -> ToggleableState.Off
                                        selected == pigs.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    }, onClick = { onPenSelection(penCode) }, enabled = penCode.isNotEmpty(),
                                        modifier = Modifier.semantics { contentDescription = penSelectLabel })
                                    SentriRow(if (penCode.isEmpty()) stringResource(R.string.inspection_unknown_pen)
                                        else if (penNumber != null && penNumber > 0) stringResource(R.string.inspection_pen_number, penNumber)
                                        else stringResource(R.string.inspection_pen_code, penCode),
                                        stringResource(if (!fullRosterView)
                                            R.string.inspection_pen_page_count else R.string.inspection_pen_visible_count,
                                            pigs.size), null,
                                        if (penCode.isEmpty()) null else { { onPenOpen(penCode) } },
                                        Modifier.weight(1f))
                                }
                                pigs.forEach { pig ->
                                    InspectionAnimalRow(pig, pig.id in state.selectedPigs,
                                        { onPigSelection(pig.id) }, { selectedPig = pig; onPigOpen(pig.id) })
                                }
                            }
                        }
                    }
                }
                if (!fullRosterView) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (page!!.request.offset > 0) SentriButton(stringResource(R.string.inspection_previous), onPrevious,
                            Modifier.weight(1f), primary = false)
                        if (page.total?.let { page.request.offset + page.pigs.size < it } == true)
                            SentriButton(stringResource(R.string.inspection_next), onNext,
                                Modifier.weight(1f), primary = false)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (state.selectedPigs.isNotEmpty()) SelectionDock(state.selectedPigs.size,
            onReview = { selectedOpen = true }, onActions = { actionsOpen = true })
        Surface(color = colors.paper, border = BorderStroke(1.dp, colors.line)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WalkToolDockAction(stringResource(R.string.inspection_go_to_pen), "grid", {
                    penMapOpen = true
                    onPenMapOpen()
                }, Modifier.weight(1f))
                WalkToolDockAction(stringResource(R.string.inspection_scan_title), "scan",
                    { scanOpen = true }, Modifier.weight(1f), primary = true)
                WalkToolDockAction(stringResource(R.string.inspection_find_pig), "search", {
                    searchDraft = ""
                    findOpen = true
                    onFind("")
                }, Modifier.weight(1f))
            }
        }
    }
    selectedPig?.takeUnless { historyOpen || treatmentPigs.isNotEmpty() || singlePigActionsOpen }?.let { pig ->
        val detail = state.pigDetail.takeIf { state.pigDetailId == pig.id }
        val absent = detail?.let { it.source == DataSource.NETWORK && it.value == null } == true
        val shown = detail?.value ?: pig
        val shownFromHealthSnapshot = !absent && detail?.value == null && state.lens == InspectionLens.HEALTH &&
            state.completeRoster?.takeIf { it.source == DataSource.CACHE }?.value?.pigs
                ?.any { it.id == pig.id } == true
        val condition = selectedCondition?.takeIf { it.pigId == pig.id }
        val conditionUnchanged = condition?.let { selected ->
            if (selected.disease) detail?.value?.health?.diseases?.any {
                it.id == selected.catalogId && it.createdAt == selected.createdAt
            } == true else detail?.value?.health?.symptoms?.any {
                it.id == selected.catalogId && it.createdAt == selected.createdAt
            } == true
        } == true
        // Each level starts at the top when returning from a condition to the pig profile.
        key(condition) {
        SentriSheet(condition?.name ?: pigLabel(shown), stringResource(R.string.home_back),
            stringResource(R.string.home_close),
            { if (condition != null) selectedCondition = null else closePigDetail() },
            onClose = closePigDetail) {
            if (state.pigDetailId == pig.id && state.pigDetailLoading)
                WalkMessage(stringResource(R.string.inspection_detail_loading))
            if (state.pigDetailId == pig.id && state.pigDetailFailed)
                WalkMessage(stringResource(R.string.inspection_detail_failed))
            if (detail?.source == DataSource.CACHE || shownFromHealthSnapshot)
                WalkMessage(stringResource(R.string.inspection_detail_offline))
            if (state.pigDetailFailed || detail?.source == DataSource.CACHE)
                SentriButton(stringResource(R.string.inspection_detail_refresh), { onPigOpen(pig.id) },
                    Modifier.fillMaxWidth(), primary = false, enabled = !state.pigDetailLoading)
            if (condition != null) {
                if (absent) WalkMessage(stringResource(R.string.inspection_detail_missing))
                else if (detail?.source == DataSource.NETWORK && !conditionUnchanged)
                    WalkMessage(stringResource(R.string.inspection_condition_changed))
                Text(condition.kindLabel, fontFamily = SentriType.sans, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.ink)
                recordedConditionMeta(condition.createdAt, condition.operator)?.let { meta ->
                    Text(meta, fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                }
                Text(stringResource(R.string.inspection_condition_care_unavailable),
                    fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                if (condition.category != null || condition.description != null)
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.inspection_condition_catalog_info),
                                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                            condition.category?.let { Text(it, fontFamily = SentriType.sans,
                                fontSize = 13.sp, color = colors.muted) }
                            condition.description?.let { Text(it, fontFamily = SentriType.sans,
                                fontSize = 13.sp, color = colors.ink) }
                        }
                    }
                detail?.value?.takeIf { conditionUnchanged && it.lifecycle == "ACTIVE" && it.location?.unitId == unit.id }
                    ?.let { current -> SentriButton(stringResource(R.string.treatment_draft_title), {
                        treatmentPigs = listOf(current)
                        onTreatmentOpen()
                    }, Modifier.fillMaxWidth(), primary = false) }
            } else {
            Text(stringResource(R.string.inspection_recorded_conditions),
                fontFamily = SentriType.sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = colors.ink)
            if (!absent) shown.location?.let { location ->
                val chinese = LocalConfiguration.current.locales[0].language == "zh"
                val sourceUnit = if (location.unitId == unit.id) localUnitName(unit)
                    else location.unit?.let { if (chinese) it.nameCn else it.nameEn }
                        ?.takeIf(String::isNotBlank)
                        ?: location.unitLocation?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.inspection_scan_unknown_unit)
                Text(stringResource(R.string.inspection_detail_location, sourceUnit,
                    location.penLocation ?: stringResource(R.string.inspection_unknown_pen)),
                    fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
            if (absent) Text(stringResource(R.string.inspection_detail_missing),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            else if (shown.health == null) Text(stringResource(R.string.inspection_health_not_returned),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            else {
                val diseases = shown.health.diseases
                val symptoms = shown.health.symptoms
                if (diseases.isEmpty() && symptoms.isEmpty())
                    Text(stringResource(R.string.inspection_no_recorded_conditions),
                        fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
                else {
                    val chinese = LocalConfiguration.current.locales[0].language == "zh"
                    Text(stringResource(R.string.inspection_condition_care_unavailable),
                        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column {
                            diseases.forEach { disease ->
                                val name = disease.detail?.let { if (chinese) it.nameCn else it.nameEn }
                                    ?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.inspection_unknown_disease)
                                val kind = stringResource(R.string.inspection_catalog_diseases)
                                SentriRow(name, recordedConditionMeta(disease.createdAt, disease.operator), null,
                                    if (detail?.value != null) ({ selectedCondition = RecordedConditionRead(
                                        pig.id, name, kind, disease.createdAt, disease.operator,
                                        disease.id, true,
                                        disease.detail?.let { catalogName(it.categoryCn, it.categoryEn, chinese) }
                                            ?.takeIf(String::isNotBlank),
                                        disease.detail?.let { catalogName(it.descriptionCn, it.descriptionEn, chinese) }
                                            ?.takeIf(String::isNotBlank)) }) else null,
                                    trailing = kind)
                            }
                            symptoms.forEach { symptom ->
                                val name = symptom.detail?.let { if (chinese) it.nameCn else it.nameEn }
                                    ?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.inspection_unknown_symptom)
                                val kind = stringResource(R.string.inspection_catalog_symptoms)
                                SentriRow(name, recordedConditionMeta(symptom.createdAt, symptom.operator), null,
                                    if (detail?.value != null) ({ selectedCondition = RecordedConditionRead(
                                        pig.id, name, kind, symptom.createdAt, symptom.operator,
                                        symptom.id, false,
                                        symptom.detail?.let { catalogName(it.partCn, it.partEn, chinese) }
                                            ?.takeIf(String::isNotBlank),
                                        symptom.detail?.let { catalogName(it.descriptionCn, it.descriptionEn, chinese) }
                                            ?.takeIf(String::isNotBlank)) }) else null,
                                    trailing = kind)
                            }
                        }
                    }
                }
                shown.health.temperature?.let { temperature ->
                    Text(stringResource(R.string.inspection_recorded_temperature, temperature.toPlainString()),
                        fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
                }
                shown.health.triage?.takeIf(String::isNotBlank)?.let { triage ->
                    Text(stringResource(R.string.inspection_legacy_pig_triage,
                        legacyTriageLabel(triage)), fontFamily = SentriType.sans,
                        fontSize = 13.sp, color = colors.muted)
                    Text(stringResource(R.string.inspection_legacy_pig_triage_note),
                        fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
                    val changed = listOfNotNull(shown.health.triageChangedAt?.let(::localCheckInTime),
                        shown.health.triageOperator?.name?.takeIf(String::isNotBlank)).joinToString(" · ")
                    if (changed.isNotEmpty()) Text(changed, fontFamily = SentriType.sans,
                        fontSize = 11.sp, color = colors.muted)
                }
                if (detail?.value != null) {
                    val historyCount = shown.health.history.size
                    if (historyCount > 0) SentriButton(
                        stringResource(R.string.inspection_health_history_open, historyCount),
                        { historyOpen = true }, Modifier.fillMaxWidth(), primary = false)
                    else Text(stringResource(R.string.inspection_health_history_empty),
                        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                }
            }
            if (detail?.value?.location?.unitId?.let { it != unit.id } == true)
                Text(stringResource(if (pig.location?.unitId == unit.id)
                    R.string.inspection_detail_moved else R.string.inspection_detail_other_unit),
                    fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            detail?.value?.takeIf { it.lifecycle == "ACTIVE" && it.location?.unitId == unit.id }
                ?.let { SentriButton(stringResource(R.string.inspection_actions_title), {
                    singlePigActionsOpen = true
                }, Modifier.fillMaxWidth(), primary = false) }
            Text(stringResource(R.string.inspection_actions_pending),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
        }
        }
    }
    if (actionsOpen && state.selectedPigs.isNotEmpty() && treatmentPigs.isEmpty()) {
        val reviewActive = state.treatmentValidationLoading || state.treatmentValidation != null ||
            state.treatmentValidationProblem != null
        if (reviewActive) TreatmentSelectionReviewSheet(state, onDismiss = onTreatmentReviewClose,
            onRetry = onTreatmentValidate, onContinue = {
                val verified = state.treatmentValidation?.eligible.orEmpty()
                if (verified.isEmpty()) return@TreatmentSelectionReviewSheet
                actionsOpen = false
                selectedOpen = false
                treatmentPigs = verified
                onTreatmentReviewClose()
                onTreatmentOpen()
            })
        else {
            val eligible = state.selectedPigs.values.filter {
                it.lifecycle == "ACTIVE" && it.farmId == unit.farmId && it.location?.unitId == unit.id
            }
            InspectionActionsSheet(state.selectedPigs.values.toList(), onDismiss = { actionsOpen = false },
                onTreat = if (eligible.isEmpty()) null else onTreatmentValidate,
                eligibleCount = eligible.size,
                treatmentUnavailableReason = stringResource(R.string.treatment_draft_no_active_pigs))
        }
    }
    if (singlePigActionsOpen && treatmentPigs.isEmpty()) selectedPig?.let { pig ->
        val current = state.pigDetail.takeIf { state.pigDetailId == pig.id }?.value
            ?.takeIf { it.lifecycle == "ACTIVE" && it.location?.unitId == unit.id }
        InspectionActionsSheet(listOf(pig), onDismiss = { singlePigActionsOpen = false },
            onTreat = current?.let { confirmed -> {
                singlePigActionsOpen = false
                treatmentPigs = listOf(confirmed)
                onTreatmentOpen()
            } }, subjectLabel = pigLabel(current ?: pig), eligibleCount = if (current == null) 0 else 1,
            treatmentUnavailableReason = stringResource(R.string.inspection_action_refresh_pig))
    }
    searchTransferCandidate?.let { candidate ->
        CrossUnitScanTransferSheet(unit, candidate, state.penRoster, state.penRosterLoading,
            state.penRosterFailed, onDismiss = {
                searchTransferCandidate = null
                findOpen = true
            }, onRetryPens = onPenMapOpen)
    }
    if (penMapOpen) SentriSheet(stringResource(R.string.inspection_go_to_pen),
        stringResource(R.string.home_back), stringResource(R.string.home_close), { penMapOpen = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.inspection_pen_map_scope, localUnitName(unit)),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (state.penRosterLoading && state.penRoster == null)
                WalkMessage(stringResource(R.string.inspection_pen_map_loading))
            if (state.penRosterFailed) {
                WalkMessage(stringResource(R.string.inspection_pen_map_failed))
                SentriButton(stringResource(R.string.inspection_retry), onPenMapOpen,
                    Modifier.fillMaxWidth(), primary = false)
            }
            state.penRoster?.let { result ->
                val roster = result.value
                if (result.source == DataSource.CACHE)
                    WalkMessage(stringResource(R.string.inspection_pen_map_offline))
                if (roster.registeredPens == null)
                    WalkMessage(stringResource(R.string.inspection_pen_map_old_snapshot))
                val counts = roster.pens.associate { it.code to it.pigs }
                val cells = roster.registeredPens?.map { it.code to it.number }
                    ?: roster.pens.map { it.code to it.number }
                if (cells.isEmpty()) WalkMessage(stringResource(R.string.inspection_pen_map_empty))
                cells.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (code, number) ->
                            val count = code?.let { counts[it] } ?: 0
                            val label = if (number > 0) stringResource(R.string.inspection_pen_number, number)
                                else code?.let { stringResource(R.string.inspection_pen_code, it) }
                                    ?: stringResource(R.string.inspection_unknown_pen)
                            Surface(onClick = {
                                if (code != null) {
                                    penMapOpen = false
                                    onPenOpen(code)
                                }
                            }, enabled = code != null, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp), color = if (count > 0) colors.well else colors.paper,
                                border = BorderStroke(1.dp, colors.line)) {
                                Column(Modifier.padding(horizontal = 4.dp, vertical = 11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontFamily = SentriType.sans, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold, color = colors.ink)
                                    Text(if (count > 0) stringResource(R.string.inspection_pen_map_pigs, count)
                                        else if (code != null) stringResource(R.string.inspection_pen_map_vacant)
                                        else stringResource(R.string.inspection_pen_map_unavailable),
                                        maxLines = 1, fontFamily = SentriType.sans,
                                        fontSize = 11.sp, color = colors.muted)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    if (maintenanceOpen) SentriSheet(stringResource(R.string.connected_unit_maintenance_title),
        stringResource(R.string.home_back), stringResource(R.string.home_close), { maintenanceOpen = false }) {
        HomeUnitMaintenanceDetail(scopedMaintenance, onRetry = onMaintenanceRetry,
            onOpenFault = { issue -> maintenanceOpen = false; onMaintenanceFaultOpen(issue) })
    }
    if (filterOpen) SentriSheet(stringResource(R.string.inspection_filter_open),
        stringResource(if (healthRoster == null) R.string.home_back else R.string.inspection_filter_show),
        stringResource(R.string.home_close), onDismiss = {
            if (healthRoster != null) onFilterApply(draftStage, draftCondition, draftBatch)
            filterOpen = false
        }, onClose = { filterOpen = false }) {
        when {
            state.completeRosterLoading -> WalkMessage(stringResource(R.string.inspection_filter_loading))
            state.completeRosterFailed || healthRoster == null -> {
                WalkMessage(stringResource(R.string.inspection_filter_failed))
                SentriButton(stringResource(R.string.inspection_filter_retry), onFilterOpen,
                    Modifier.fillMaxWidth(), primary = false)
            }
            else -> {
                if (state.completeRoster?.source == DataSource.CACHE)
                    WalkMessage(stringResource(R.string.inspection_filter_offline))
                val batchOptions = healthRoster.pigs.mapNotNull { pig ->
                    pig.batch?.takeIf { batch -> batch.farmId == pig.farmId &&
                        batch.id == pig.currentBatchId && batch.serial.isNotBlank() }
                }.distinctBy { it.id }.sortedBy { it.serial }
                if (batchOptions.isNotEmpty()) {
                    SentriSectionHeading(stringResource(R.string.inspection_filter_batch))
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column {
                            SentriRow(stringResource(R.string.inspection_filter_any), null, null,
                                { draftBatch = null }, selected = draftBatch == null)
                            batchOptions.forEach { batch ->
                                SentriRow(stringResource(R.string.inspection_filter_batch_value, batch.serial),
                                    null, null, { draftBatch = batch.id }, selected = draftBatch == batch.id)
                            }
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                }
                val stageOptions = healthRoster.pigs.mapNotNull(PigProfile::breederProductionStatus)
                    .distinct().sorted().filter { inspectionStageLabelId(it) != null }
                SentriSectionHeading(stringResource(R.string.inspection_filter_stage))
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column {
                        SentriRow(stringResource(R.string.inspection_filter_any), null, null,
                            { draftStage = null }, selected = draftStage == null)
                        stageOptions.forEach { code ->
                            val label = stringResource(requireNotNull(inspectionStageLabelId(code)))
                            SentriRow(label, null, null, { draftStage = code }, selected = draftStage == code)
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                SentriSectionHeading(stringResource(R.string.inspection_filter_condition))
                val chinese = LocalConfiguration.current.locales[0].language == "zh"
                val conditions = healthRoster.pigs.flatMap { pig ->
                    pig.health?.diseases.orEmpty().mapNotNull { disease ->
                        disease.detail?.let { "d:${disease.id}" to catalogName(it.nameCn, it.nameEn, chinese) }
                    } + pig.health?.symptoms.orEmpty().mapNotNull { symptom ->
                        symptom.detail?.let { "s:${symptom.id}" to catalogName(it.nameCn, it.nameEn, chinese) }
                    }
                }.filter { it.second.isNotBlank() }.distinctBy { it.first }.sortedBy { it.second }
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column {
                        SentriRow(stringResource(R.string.inspection_filter_any), null, null,
                            { draftCondition = null }, selected = draftCondition == null)
                        conditions.forEach { (key, label) ->
                            SentriRow(label, null, null, { draftCondition = key },
                                selected = draftCondition == key)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.inspection_filter_care_unavailable),
                    fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                SentriButton(stringResource(R.string.inspection_filter_reset), {
                    draftStage = null
                    draftCondition = null
                    draftBatch = null
                }, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
    if (catalogOpen) SentriSheet(stringResource(R.string.inspection_catalog_title),
        stringResource(R.string.home_back), stringResource(R.string.home_close), { catalogOpen = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.inspection_catalog_read_only), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            SentriField(catalogQuery, { catalogQuery = it }, stringResource(R.string.inspection_catalog_search), Modifier.fillMaxWidth())
            if (state.catalogLoading) Text(stringResource(R.string.inspection_catalog_loading),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (state.catalogFailed) Text(stringResource(R.string.inspection_catalog_failed),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (state.catalog?.source == DataSource.CACHE) Text(stringResource(R.string.inspection_catalog_offline),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            state.catalog?.value?.let { catalog ->
                val query = catalogQuery.trim().lowercase(Locale.ROOT)
                val chinese = LocalConfiguration.current.locales[0].language == "zh"
                val symptomKind = stringResource(R.string.inspection_catalog_symptoms)
                val diseaseKind = stringResource(R.string.inspection_catalog_diseases)
                val diseases = catalog.diseases.filter { item -> query.isEmpty() ||
                    listOf(item.nameCn, item.nameEn, item.abbrCn, item.abbrEn, item.categoryCn,
                        item.categoryEn).any { it.lowercase(Locale.ROOT).contains(query) } }
                val symptoms = catalog.symptoms.filter { item -> query.isEmpty() ||
                    listOf(item.nameCn, item.nameEn, item.partCn, item.partEn).any {
                        it.lowercase(Locale.ROOT).contains(query) } }
                if (diseases.isEmpty() && symptoms.isEmpty())
                    Text(stringResource(R.string.inspection_catalog_empty), fontFamily = SentriType.sans,
                        fontSize = 13.sp, color = colors.muted)
                if (symptoms.isNotEmpty()) {
                    SentriSectionHeading(stringResource(R.string.inspection_catalog_symptoms),
                        meta = symptoms.size.toString())
                    if (query.isNotEmpty()) SentriPanel(Modifier.fillMaxWidth()) {
                        Column { symptoms.forEach { item ->
                            SentriRow(catalogName(item.nameCn, item.nameEn, chinese),
                                catalogName(item.partCn, item.partEn, chinese).takeIf(String::isNotBlank), null, {
                                    catalogDetail = CatalogReadDetail(catalogName(item.nameCn, item.nameEn, chinese),
                                        symptomKind,
                                        catalogName(item.partCn, item.partEn, chinese).takeIf(String::isNotBlank),
                                        catalogName(item.descriptionCn, item.descriptionEn, chinese).takeIf(String::isNotBlank))
                                    catalogOpen = false
                                })
                        } }
                    } else symptoms.groupBy { it.partCn.trim() to it.partEn.trim() }
                        .entries.sortedBy { catalogName(it.key.first, it.key.second, chinese) }
                        .forEach { (part, items) ->
                            val groupKey = "symptom:${part.first}:${part.second}"
                            CatalogGroupPanel(catalogName(part.first, part.second, chinese)
                                .ifBlank { stringResource(R.string.inspection_catalog_uncategorized) },
                                items.size, groupKey in expandedCatalogGroups, {
                                    expandedCatalogGroups = if (groupKey in expandedCatalogGroups)
                                        expandedCatalogGroups - groupKey else expandedCatalogGroups + groupKey
                                }) {
                                items.forEach { item ->
                                    SentriRow(catalogName(item.nameCn, item.nameEn, chinese), null, null, {
                                        catalogDetail = CatalogReadDetail(catalogName(item.nameCn, item.nameEn, chinese),
                                            symptomKind,
                                            catalogName(item.partCn, item.partEn, chinese).takeIf(String::isNotBlank),
                                            catalogName(item.descriptionCn, item.descriptionEn, chinese).takeIf(String::isNotBlank))
                                        catalogOpen = false
                                    })
                                }
                            }
                        }
                }
                if (diseases.isNotEmpty()) {
                    SentriSectionHeading(stringResource(R.string.inspection_catalog_diseases),
                        meta = diseases.size.toString())
                    if (query.isNotEmpty()) SentriPanel(Modifier.fillMaxWidth()) {
                        Column { diseases.forEach { item ->
                            SentriRow(catalogName(item.nameCn, item.nameEn, chinese),
                                catalogName(item.categoryCn, item.categoryEn, chinese).takeIf(String::isNotBlank), null, {
                                    catalogDetail = CatalogReadDetail(catalogName(item.nameCn, item.nameEn, chinese),
                                        diseaseKind,
                                        catalogName(item.categoryCn, item.categoryEn, chinese).takeIf(String::isNotBlank),
                                        catalogName(item.descriptionCn, item.descriptionEn, chinese).takeIf(String::isNotBlank))
                                    catalogOpen = false
                                })
                        } }
                    } else diseases.groupBy { it.categoryCn.trim() to it.categoryEn.trim() }
                        .entries.sortedBy { catalogName(it.key.first, it.key.second, chinese) }
                        .forEach { (category, items) ->
                            val groupKey = "disease:${category.first}:${category.second}"
                            CatalogGroupPanel(catalogName(category.first, category.second, chinese)
                                .ifBlank { stringResource(R.string.inspection_catalog_uncategorized) },
                                items.size, groupKey in expandedCatalogGroups, {
                                    expandedCatalogGroups = if (groupKey in expandedCatalogGroups)
                                        expandedCatalogGroups - groupKey else expandedCatalogGroups + groupKey
                                }) {
                                items.forEach { item ->
                                    SentriRow(catalogName(item.nameCn, item.nameEn, chinese), null, null, {
                                        catalogDetail = CatalogReadDetail(catalogName(item.nameCn, item.nameEn, chinese),
                                            diseaseKind,
                                            catalogName(item.categoryCn, item.categoryEn, chinese).takeIf(String::isNotBlank),
                                            catalogName(item.descriptionCn, item.descriptionEn, chinese).takeIf(String::isNotBlank))
                                        catalogOpen = false
                                    })
                                }
                            }
                        }
                }
            }
        }
    }
    catalogDetail?.let { detail ->
        SentriSheet(detail.name, stringResource(R.string.home_back), stringResource(R.string.home_close), {
            catalogDetail = null
            catalogOpen = true
        }, onClose = { catalogDetail = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.inspection_catalog_read_only), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
                if (state.catalog?.source == DataSource.CACHE)
                    Text(stringResource(R.string.inspection_catalog_offline), fontFamily = SentriType.sans,
                        fontSize = 12.sp, color = colors.muted)
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(detail.kind, fontFamily = SentriType.sans, fontSize = 14.sp, color = colors.ink)
                        detail.category?.let { Text(it, fontFamily = SentriType.sans,
                            fontSize = 13.sp, color = colors.muted) }
                        detail.description?.let { Text(it, fontFamily = SentriType.sans,
                            fontSize = 13.sp, color = colors.ink) }
                    }
                }
            }
        }
    }
    if (confirmCheckIn) SentriSheet(stringResource(R.string.inspection_finish),
        stringResource(R.string.home_back), stringResource(R.string.home_close), { confirmCheckIn = false }) {
        Text(stringResource(R.string.inspection_check_in_explanation), fontFamily = SentriType.sans,
            fontSize = 13.sp, color = colors.muted)
        SentriButton(stringResource(R.string.inspection_check_in), {
            confirmCheckIn = false
            previousCheckInOperationId = state.localCheckIn?.operationId
            checkInReceiptWaiting = true
            checkInReceiptOpen = true
            onCheckIn()
        }, Modifier.fillMaxWidth(), enabled = !state.saving)
    }
    if (checkInReceiptOpen) SentriSheet(stringResource(R.string.inspection_local_check_in_title),
        stringResource(R.string.home_back), stringResource(R.string.home_close), {
            checkInReceiptOpen = false
        }) {
        val receipt = state.localCheckIn?.takeIf {
            !checkInReceiptWaiting || it.operationId != previousCheckInOperationId
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.saveFailed && checkInReceiptWaiting ->
                    WalkMessage(stringResource(R.string.inspection_save_failed))
                receipt != null -> {
                    Text(stringResource(R.string.inspection_local_check_in_unit, localUnitName(unit)),
                        fontFamily = SentriType.sans, fontSize = 15.sp, color = colors.ink)
                    Text(stringResource(R.string.inspection_local_check_in_saved_at,
                        localCheckInTime(receipt.savedAtMillis)), fontFamily = SentriType.sans,
                        fontSize = 15.sp, color = colors.ink)
                    WalkMessage(stringResource(checkInStatusText(receipt.status)))
                    if (receipt.status == CommandStatus.SYNCED)
                        WalkMessage(stringResource(R.string.inspection_local_check_in_server_note))
                    EarlierCheckInNotices(state)
                }
                else -> WalkMessage(stringResource(R.string.inspection_local_check_in_saving))
            }
        }
    }
}

private fun catalogName(chineseName: String, englishName: String, chinese: Boolean): String =
    (if (chinese) chineseName else englishName).ifBlank { if (chinese) englishName else chineseName }

@Composable
private fun CatalogGroupPanel(title: String, count: Int, expanded: Boolean,
    onToggle: () -> Unit, content: @Composable () -> Unit) {
    val colors = SentriTheme.colors
    SentriPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SentriIcon("grid", null, Modifier.size(18.dp), colors.muted)
                Text(title, Modifier.weight(1f), fontFamily = SentriType.sans,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                Text(count.toString(), fontFamily = SentriType.mono, fontSize = 11.sp, color = colors.muted)
                SentriIcon("down", null, Modifier.size(16.dp).rotate(if (expanded) 180f else 0f), colors.muted)
            }
            if (expanded) {
                HorizontalDivider(color = colors.line)
                content()
            }
        }
    }
}

@Composable
private fun TreatmentSelectionReviewSheet(state: InspectionWalkState, onDismiss: () -> Unit,
    onRetry: () -> Unit, onContinue: () -> Unit) {
    val review = state.treatmentValidation
    SentriSheet(stringResource(R.string.inspection_treatment_review_title),
        stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.treatmentValidationLoading -> WalkMessage(
                    stringResource(R.string.inspection_treatment_review_loading))
                state.treatmentValidationProblem != null -> {
                    WalkMessage(stringResource(if (state.treatmentValidationProblem == TreatmentSelectionProblem.OFFLINE)
                        R.string.inspection_treatment_review_offline else R.string.inspection_treatment_review_failed))
                    SentriButton(stringResource(R.string.inspection_retry), onRetry, Modifier.fillMaxWidth(), primary = false)
                }
                review != null -> {
                    WalkMessage(stringResource(R.string.inspection_treatment_review_count,
                        review.eligible.size, review.excluded.size))
                    review.excluded.forEach { item ->
                        SentriRow(pigLabel(item.pig), stringResource(when (item.reason) {
                            TreatmentExclusionReason.MISSING -> R.string.inspection_treatment_review_missing
                            TreatmentExclusionReason.MOVED -> R.string.inspection_treatment_review_moved
                            TreatmentExclusionReason.INACTIVE -> R.string.inspection_treatment_review_inactive
                        }), null, null)
                    }
                    if (review.eligible.isNotEmpty()) SentriButton(
                        stringResource(R.string.inspection_treatment_review_continue, review.eligible.size),
                        onContinue, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EarlierCheckInNotices(state: InspectionWalkState) {
    if (state.earlierCheckInsWaiting > 0)
        WalkMessage(stringResource(R.string.inspection_earlier_check_ins_waiting,
            state.earlierCheckInsWaiting))
    if (state.earlierCheckInsNeedReview > 0)
        WalkMessage(stringResource(R.string.inspection_earlier_check_ins_review,
            state.earlierCheckInsNeedReview))
}

private fun checkInStatusText(status: CommandStatus): Int = when (status) {
    CommandStatus.QUEUED -> R.string.inspection_saved_local
    CommandStatus.IN_FLIGHT -> R.string.inspection_syncing
    CommandStatus.SYNCED -> R.string.inspection_server_confirmed
    CommandStatus.UNCERTAIN, CommandStatus.FAILED, CommandStatus.CONFLICTED -> R.string.inspection_needs_review
    CommandStatus.CANCELLED -> R.string.inspection_cancelled
}

private fun localCheckInTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(millis))

private fun localCheckInTime(value: String): String = runCatching {
    val millis = TimestampInstant.seconds(value).movePointRight(3).toLong()
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(millis))
}.getOrDefault(value)

private fun recordedConditionMeta(createdAt: String?, operator: PigOperator?): String? =
    listOfNotNull(createdAt?.let(::localCheckInTime), operator?.name?.takeIf(String::isNotBlank))
        .joinToString(" · ").takeIf(String::isNotBlank)

@Composable
private fun InspectionScanPage(unit: FarmUnit, state: InspectionWalkState, onBack: () -> Unit,
    onScannedTag: (String) -> Unit, onPigSelect: (String) -> Unit,
    onSelectedOpen: () -> Unit, onActionsOpen: () -> Unit,
    onLoadCurrentUnitPens: () -> Unit, onPigOpen: (PigProfile) -> Unit) {
    val context = LocalContext.current
    val colors = SentriTheme.colors
    val focus = remember { FocusRequester() }
    val scanCallback by rememberUpdatedState(onScannedTag)
    var scanner by remember { mutableStateOf<AndroidScannerInput?>(null) }
    var keyboards by remember { mutableStateOf<List<ScannerKeyboard>>(emptyList()) }
    var chosenDevice by remember { mutableStateOf<Int?>(null) }
    var activeDevice by remember { mutableStateOf<Int?>(null) }
    var paused by remember { mutableStateOf(false) }
    var arrivalSequence by remember { mutableStateOf(-1L) }
    var observedSequence by remember { mutableStateOf(state.scannedPigs.firstOrNull()?.sequence) }
    var transferCandidate by remember { mutableStateOf<PigProfile?>(null) }
    DisposableEffect(context, unit.id) {
        val input = AndroidScannerInput(context,
            onDevices = { devices ->
                keyboards = devices
                if (activeDevice != null && devices.none { it.id == activeDevice }) {
                    activeDevice = null
                    chosenDevice = null
                }
            },
            onResult = { scanCallback(it) },
            onDisconnect = { activeDevice = null; chosenDevice = null },
            continuous = true)
        scanner = input
        onDispose { input.close(); scanner = null }
    }
    LaunchedEffect(scanner, keyboards, chosenDevice, paused, transferCandidate) {
        val input = scanner ?: return@LaunchedEffect
        if (paused || transferCandidate != null) {
            input.cancel()
            activeDevice = null
        } else {
            val device = chosenDevice?.takeIf { id -> keyboards.any { it.id == id } }
                ?: keyboards.singleOrNull()?.id
            if (device != null && activeDevice != device) {
                try { input.start(device); activeDevice = device }
                catch (_: Exception) { activeDevice = null }
            }
        }
    }
    LaunchedEffect(scanner) { if (scanner != null) focus.requestFocus() }
    val groups = remember(state.scannedPigs, unit.id) {
        InspectionScanResults.grouped(state.scannedPigs, unit.id)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.scannedPigs.firstOrNull()?.sequence) {
        val latest = state.scannedPigs.firstOrNull() ?: return@LaunchedEffect
        if (latest.sequence == observedSequence) return@LaunchedEffect
        observedSequence = latest.sequence
        arrivalSequence = latest.sequence
        // One controls item, then a heading and pen cards for each unit.
        var target = 1
        loop@ for (group in groups) {
            target++
            for (pen in group.pens) {
                if (pen.entries.any { it.pig.id == latest.pig.id }) break@loop
                target++
            }
        }
        listState.animateScrollToItem(target)
        delay(1_600)
        if (arrivalSequence == latest.sequence) arrivalSequence = -1L
    }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)
        .onPreviewKeyEvent { scanner?.dispatch(it.nativeKeyEvent) ?: false }
        .focusRequester(focus).focusable()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.inspection_scan_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(localUnitName(unit), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "scan-controls") {
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (keyboards.isEmpty()) {
                        WalkMessage(stringResource(R.string.inspection_scan_no_reader))
                        WalkMessage(stringResource(R.string.inspection_scan_pair_reader))
                    } else {
                        if (keyboards.size > 1 && chosenDevice == null) {
                            WalkMessage(stringResource(R.string.inspection_scan_choose_reader))
                            keyboards.forEach { device ->
                                SentriButton(device.name, {
                                    scanner?.cancel()
                                    activeDevice = null
                                    chosenDevice = device.id
                                }, Modifier.fillMaxWidth(), primary = false)
                            }
                        }
                        if (activeDevice != null && !paused && state.scannedPigs.isEmpty())
                            WalkMessage(stringResource(R.string.inspection_scan_empty))
                    }
                    if (state.scanPending > 0)
                        WalkMessage(stringResource(R.string.inspection_scan_looking_up))
                    state.scanProblem?.let { problem -> WalkMessage(stringResource(when (problem) {
                        ScanProblem.NO_MATCH -> R.string.inspection_scan_no_match
                        ScanProblem.NO_CACHED_MATCH -> R.string.inspection_scan_no_cached_match
                        ScanProblem.AMBIGUOUS -> R.string.inspection_scan_ambiguous
                        ScanProblem.READ_FAILED -> R.string.inspection_scan_read_failed
                    })) }
                }
            }
            groups.forEach { group ->
                item(key = "scan-group:${group.unitId}") {
                    val location = group.pens.first().entries.first().pig.location
                    val isChinese = LocalConfiguration.current.locales[0].language == "zh"
                    val title = if (group.unitId == unit.id) localUnitName(unit)
                        else location?.unit?.let { if (isChinese) it.nameCn else it.nameEn }
                            ?.takeIf(String::isNotBlank)
                            ?: location?.unitLocation?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.inspection_scan_unknown_unit)
                    SentriSectionHeading(title)
                }
                items(group.pens, key = { "scan-pen:${group.unitId}:${it.penCode}" }) { pen ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column {
                            Row(Modifier.fillMaxWidth().background(colors.well)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(pen.penCode ?: stringResource(R.string.inspection_unknown_pen),
                                    Modifier.weight(1f), fontFamily = SentriType.mono,
                                    fontSize = 13.sp, color = colors.muted)
                                Text(stringResource(R.string.inspection_scan_pen_count, pen.entries.size),
                                    fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
                            }
                            pen.entries.forEachIndexed { index, entry ->
                                key(entry.pig.id) {
                                    if (index > 0) HorizontalDivider(color = colors.line)
                                    val pig = entry.pig
                                    val isCurrentUnit = InspectionScanResults.selectable(pig, unit.farmId, unit.id)
                                    val canTransfer = pig.farmId == unit.farmId && pig.lifecycle == "ACTIVE" &&
                                        pig.location?.unitId?.let { it != unit.id } == true
                                    val detail = when {
                                        !isCurrentUnit -> stringResource(R.string.inspection_scan_other_unit)
                                        entry.source == DataSource.CACHE -> stringResource(R.string.inspection_scan_offline)
                                        else -> inspectionRosterDetail(pig)
                                    }
                                    Column {
                                        InspectionAnimalRow(pig, pig.id in state.selectedPigs,
                                            if (isCurrentUnit) ({ onPigSelect(pig.id) }) else null,
                                            { onPigOpen(pig) }, contextOverride = detail,
                                            arriving = entry.sequence == arrivalSequence)
                                        if (canTransfer) SentriButton(stringResource(R.string.inspection_scan_move_into_unit), {
                                            transferCandidate = pig
                                            onLoadCurrentUnitPens()
                                        }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), primary = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (keyboards.isNotEmpty() && (activeDevice != null || paused || chosenDevice != null)) {
            SentriButton(stringResource(if (paused) R.string.inspection_scan_resume else R.string.inspection_scan_stop), {
                paused = !paused
            }, Modifier.fillMaxWidth().padding(horizontal = 18.dp), primary = paused)
        }
        if (keyboards.isNotEmpty() && state.selectedPigs.isNotEmpty()) SelectionDock(state.selectedPigs.size,
            onReview = onSelectedOpen, onActions = onActionsOpen)
    }
    transferCandidate?.let { candidate ->
        CrossUnitScanTransferSheet(unit, candidate, state.penRoster, state.penRosterLoading,
            state.penRosterFailed, onDismiss = { transferCandidate = null },
            onRetryPens = onLoadCurrentUnitPens)
    }
}

/** A transfer requires a registered destination pen. Submission waits for a verifiable offline contract. */
@Composable
internal fun CrossUnitScanTransferSheet(unit: FarmUnit, pig: PigProfile,
    penRoster: CachedResult<HomeUnitPenRoster>?, penRosterLoading: Boolean,
    penRosterFailed: Boolean, onDismiss: () -> Unit, onRetryPens: () -> Unit) {
    var selectAfterTransfer by remember(pig.id) { mutableStateOf<Boolean?>(null) }
    var destinationCode by remember(pig.id) { mutableStateOf<String?>(null) }
    val colors = SentriTheme.colors
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val sourceName = pig.location?.unit?.let { if (chinese) it.nameCn else it.nameEn }
        ?.takeIf(String::isNotBlank)
        ?: pig.location?.unitLocation?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inspection_scan_unknown_unit)
    val registered = penRoster?.value?.takeIf { it.unitId == unit.id }?.registeredPens
        ?.filter { it.code != null }.orEmpty()
    SentriSheet(stringResource(R.string.inspection_scan_transfer_title),
        stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pigLabel(pig), fontFamily = SentriType.sans, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(stringResource(R.string.inspection_scan_transfer_route,
                sourceName, localUnitName(unit)),
                fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
            if (selectAfterTransfer == null) {
                WalkMessage(stringResource(R.string.inspection_scan_transfer_explanation))
                SentriButton(stringResource(R.string.inspection_scan_transfer_only),
                    { selectAfterTransfer = false }, Modifier.fillMaxWidth())
                SentriButton(stringResource(R.string.inspection_scan_transfer_and_select),
                    { selectAfterTransfer = true }, Modifier.fillMaxWidth(), primary = false)
            } else {
                Text(stringResource(R.string.inspection_scan_transfer_choose_pen),
                    fontFamily = SentriType.sans, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.ink)
                if (penRosterLoading && penRoster == null)
                    WalkMessage(stringResource(R.string.inspection_pen_map_loading))
                if (penRosterFailed) {
                    WalkMessage(stringResource(R.string.inspection_pen_map_failed))
                    SentriButton(stringResource(R.string.inspection_retry), onRetryPens,
                        Modifier.fillMaxWidth(), primary = false)
                }
                if (penRoster?.source == DataSource.CACHE)
                    WalkMessage(stringResource(R.string.inspection_scan_transfer_old_snapshot))
                if (registered.isEmpty() && !penRosterLoading)
                    WalkMessage(stringResource(R.string.inspection_scan_transfer_no_pens))
                registered.forEach { pen ->
                    val code = pen.code ?: return@forEach
                    SentriRow(stringResource(R.string.inspection_pen_number, pen.number), code,
                        null, onClick = { destinationCode = code },
                        selected = destinationCode == code)
                }
                destinationCode?.let { code ->
                    WalkMessage(stringResource(R.string.inspection_scan_transfer_review,
                        code, stringResource(if (selectAfterTransfer == true)
                            R.string.inspection_scan_transfer_and_select
                            else R.string.inspection_scan_transfer_only)))
                }
                WalkMessage(stringResource(R.string.inspection_scan_transfer_pending))
                SentriButton(stringResource(R.string.inspection_scan_transfer_submit), {},
                    Modifier.fillMaxWidth(), enabled = false)
                SentriButton(stringResource(R.string.home_back), {
                    destinationCode = null
                    selectAfterTransfer = null
                }, Modifier.fillMaxWidth(), primary = false)
            }
        }
    }
}

@Composable
private fun CheckInHistoryPage(unit: FarmUnit, state: InspectionWalkState, onBack: () -> Unit,
    onRefresh: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onOpen: (String) -> Unit) {
    val colors = SentriTheme.colors
    val result = state.recentCheckIns
    val page = result?.value
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inspection_check_in_history_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(localUnitName(unit), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
            SentriButton(stringResource(R.string.inspection_retry), onRefresh, primary = false)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            item { WalkMessage(stringResource(R.string.inspection_check_in_history_scope)) }
            if (state.outstandingCheckIns.isNotEmpty()) {
                item { SentriSectionHeading(stringResource(R.string.inspection_local_check_ins_heading,
                    state.outstandingCheckIns.size)) }
                items(state.outstandingCheckIns, key = { "local:${it.operationId}" }) { receipt ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(localCheckInTime(receipt.savedAtMillis), fontFamily = SentriType.sans,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                            Text(stringResource(checkInStatusText(receipt.status)),
                                fontFamily = SentriType.sans, fontSize = 12.sp,
                                color = if (receipt.status in setOf(CommandStatus.UNCERTAIN,
                                        CommandStatus.FAILED, CommandStatus.CONFLICTED)) colors.red
                                    else colors.muted)
                        }
                    }
                }
            }
            item { SentriSectionHeading(stringResource(R.string.inspection_server_check_ins_heading)) }
            if (state.recentCheckInsLoading)
                item { WalkMessage(stringResource(R.string.inspection_check_in_history_loading)) }
            if (state.recentCheckInsFailed)
                item { WalkMessage(stringResource(R.string.inspection_check_in_history_failed)) }
            if (result?.source == DataSource.CACHE)
                item { WalkMessage(stringResource(R.string.inspection_check_in_history_offline)) }
            if (page != null && page.entries.isEmpty() && !state.recentCheckInsLoading)
                item { WalkMessage(stringResource(R.string.inspection_check_in_history_empty)) }
            if (page != null) {
                items(page.entries, key = { it.id }) { entry ->
                    SentriPanel(Modifier.fillMaxWidth().clickable { onOpen(entry.id) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.createdAt?.let(::localCheckInTime)
                                ?: stringResource(R.string.inspection_last_check_in_time_missing),
                                fontFamily = SentriType.sans, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.ink)
                            Text(entry.operatorName?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.inspection_check_in_history_operator_unknown),
                                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (page.offset > 0) SentriButton(stringResource(R.string.inspection_previous),
                            onPrevious, Modifier.weight(1f), primary = false)
                        if (page.hasMore) SentriButton(stringResource(R.string.inspection_next),
                            onNext, Modifier.weight(1f), primary = false)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun legacyCheckInValue(value: String): String = when (value.lowercase(Locale.ROOT)) {
    "sufficient" -> stringResource(R.string.biz_inspection_illumination_sufficient)
    "insufficient" -> stringResource(R.string.biz_inspection_illumination_insufficient)
    "excellent" -> stringResource(R.string.biz_inspection_value_excellent)
    "good" -> stringResource(R.string.biz_inspection_value_good)
    "normal" -> stringResource(R.string.biz_inspection_value_normal)
    "poor" -> stringResource(R.string.biz_inspection_value_poor)
    "yes" -> stringResource(R.string.biz_inspection_value_yes)
    "no" -> stringResource(R.string.biz_inspection_value_no)
    else -> value
}

@Composable
private fun CheckInDetailPage(unit: FarmUnit, state: InspectionWalkState, onBack: () -> Unit,
    onRefresh: () -> Unit) {
    val colors = SentriTheme.colors
    val result = state.checkInDetail
    val record = result?.value
    val fields: List<Pair<String, String>> = if (record == null) emptyList() else listOfNotNull(
        record.temperature?.let { stringResource(R.string.inspection_check_in_temperature) to it.toPlainString() },
        record.humidity?.let { stringResource(R.string.inspection_check_in_humidity) to it.toPlainString() },
        record.ammonia?.let { stringResource(R.string.inspection_check_in_ammonia) to it.toPlainString() },
        record.co2?.let { stringResource(R.string.inspection_check_in_co2) to it.toPlainString() },
        record.illumination?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_illumination) to legacyCheckInValue(it) },
        record.ventilation?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_ventilation) to legacyCheckInValue(it) },
        record.waterConsumption?.let { stringResource(R.string.inspection_check_in_water) to it.toPlainString() },
        record.feedConsumption?.let { stringResource(R.string.inspection_check_in_feed) to it.toPlainString() },
        record.environmentRemarks?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_environment_remarks) to it },
        record.cleanliness?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_cleanliness) to legacyCheckInValue(it) },
        record.compliance?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_compliance) to legacyCheckInValue(it) },
        record.bioRemarks?.takeIf(String::isNotBlank)?.let { stringResource(R.string.inspection_check_in_bio_remarks) to it },
    )
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inspection_check_in_detail_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(localUnitName(unit), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
            SentriButton(stringResource(R.string.inspection_retry), onRefresh, primary = false)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { WalkMessage(stringResource(R.string.inspection_check_in_detail_scope)) }
            if (state.checkInDetailLoading)
                item { WalkMessage(stringResource(R.string.inspection_check_in_detail_loading)) }
            if (state.checkInDetailFailed)
                item { WalkMessage(stringResource(R.string.inspection_check_in_detail_failed)) }
            if (result?.source == DataSource.CACHE)
                item { WalkMessage(stringResource(R.string.inspection_check_in_history_offline)) }
            if (result != null && record == null)
                item { WalkMessage(stringResource(R.string.inspection_check_in_detail_missing)) }
            if (record != null) {
                item {
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(record.createdAt?.let(::localCheckInTime)
                                ?: stringResource(R.string.inspection_last_check_in_time_missing),
                                fontFamily = SentriType.sans, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.ink)
                            if (fields.isEmpty())
                                Text(stringResource(R.string.inspection_check_in_detail_no_values),
                                    fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
                            fields.forEach { (label, value) ->
                                Column {
                                    Text(label, fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                                    Text(value, fontFamily = SentriType.sans, fontSize = 14.sp, color = colors.ink)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HealthHistoryPage(pig: PigProfile, detail: CachedResult<PigProfile?>?, onBack: () -> Unit) {
    val colors = SentriTheme.colors
    val records = detail?.value?.health?.history.orEmpty().sortedWith(
        compareByDescending<PigHealthHistory> { record ->
            record.createdAt?.let { runCatching { TimestampInstant.seconds(it) }.getOrNull() }
        }.thenByDescending { it.id })
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.inspection_health_history_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(pigLabel(pig), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { WalkMessage(stringResource(R.string.inspection_health_history_legacy)) }
            if (detail?.source == DataSource.CACHE)
                item { WalkMessage(stringResource(R.string.inspection_health_history_offline)) }
            if (detail?.value?.health == null)
                item { WalkMessage(stringResource(R.string.inspection_health_not_returned)) }
            else if (records.isEmpty())
                item { WalkMessage(stringResource(R.string.inspection_health_history_empty)) }
            items(records) { record ->
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(healthEventLabel(record.event), fontFamily = SentriType.sans,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        val names = historyNames(record.diseases, chinese) + historyNames(record.symptoms, chinese)
                        if (names.isNotEmpty()) Text(names.joinToString(" · "), fontFamily = SentriType.sans,
                            fontSize = 13.sp, color = colors.ink)
                        if (record.event == "TriageLevelSet") record.triage?.takeIf(String::isNotBlank)?.let { triage ->
                            Text(stringResource(R.string.inspection_legacy_pig_triage,
                                legacyTriageLabel(triage)), fontFamily = SentriType.sans,
                                fontSize = 13.sp, color = colors.ink)
                        }
                        record.remark?.takeIf(String::isNotBlank)?.let { Text(it, fontFamily = SentriType.sans,
                            fontSize = 13.sp, color = colors.muted) }
                        val meta = listOfNotNull(record.createdAt?.let(::localCheckInTime),
                            record.operator?.name?.takeIf(String::isNotBlank)).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, fontFamily = SentriType.sans,
                            fontSize = 11.sp, color = colors.muted)
                        if (record.event !in knownHealthEvents) Text(record.event, fontFamily = SentriType.sans,
                            fontSize = 11.sp, color = colors.muted)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private val knownHealthEvents = setOf("Recovered", "SymptomAdded", "SymptomRemoved", "DiseaseAdded",
    "DiseaseRemoved", "TriageLevelSet")

@Composable private fun legacyTriageLabel(value: String): String = when (value) {
    "Emergency" -> stringResource(R.string.biz_triage_emergency)
    "Healthy" -> stringResource(R.string.biz_triage_healthy)
    "Normal" -> stringResource(R.string.biz_triage_normal)
    "Priority" -> stringResource(R.string.biz_triage_priority)
    else -> value
}

@Composable private fun healthEventLabel(event: String) = stringResource(when (event) {
    "Recovered" -> R.string.inspection_health_history_recovered
    "SymptomAdded" -> R.string.inspection_health_history_symptom_added
    "SymptomRemoved" -> R.string.inspection_health_history_symptom_removed
    "DiseaseAdded" -> R.string.inspection_health_history_disease_added
    "DiseaseRemoved" -> R.string.inspection_health_history_disease_removed
    "TriageLevelSet" -> R.string.inspection_health_history_triage_changed
    else -> R.string.inspection_health_history_other
})

private fun historyNames(value: JsonValue?, chinese: Boolean): List<String> = runCatching {
    val rows = JSONArray(value?.encoded ?: "[]")
    (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val preferred = if (chinese) "name_cn" else "name_en"
        val alternate = if (chinese) "name_en" else "name_cn"
        row.optString(preferred).takeIf(String::isNotBlank)
            ?: row.optString(alternate).takeIf(String::isNotBlank)
    }
}.getOrDefault(emptyList())

@Composable private fun LastCheckInSummary(result: CachedResult<UnitCheckInSummary?>) {
    val entry = result.value
    val detail = if (entry == null) stringResource(R.string.inspection_last_check_in_none)
        else listOfNotNull(entry.createdAt?.let(::localCheckInTime)
                ?: stringResource(R.string.inspection_last_check_in_time_missing),
            entry.operatorName?.takeIf(String::isNotBlank)).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.inspection_last_check_in), fontFamily = SentriType.sans,
            fontSize = 11.sp, color = SentriTheme.colors.muted)
        Text(detail, fontFamily = SentriType.sans, fontSize = 14.sp, color = SentriTheme.colors.ink)
        if (result.source == DataSource.CACHE)
            Text(stringResource(R.string.inspection_last_check_in_offline), fontFamily = SentriType.sans,
                fontSize = 11.sp, color = SentriTheme.colors.muted)
    }
}

@Composable
private fun PenRosterPage(unit: FarmUnit, state: InspectionWalkState, onBack: () -> Unit,
    onRetry: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSelect: (String) -> Unit, onSelectedOpen: () -> Unit, onActionsOpen: () -> Unit,
    onDevicesRetry: () -> Unit, onInventoryRetry: () -> Unit,
    onFaultDraftChange: (String, DeviceFaultDraft) -> Unit,
    onFaultDraftSave: ((String) -> Unit)?, onFaultDraftRetry: () -> Unit,
    onNoteOpen: ((String) -> Unit)?, onNoteChange: (String, String) -> Unit,
    onNoteSave: (String) -> Unit, onNoteClear: (String) -> Unit, focusedFaultId: String?,
    onPigSelect: (PigProfile) -> Unit) {
    val code = state.penCode ?: return
    var selectedFaultId by remember(code, focusedFaultId) { mutableStateOf(focusedFaultId) }
    var reportOpen by remember(code) { mutableStateOf(false) }
    var noteOpen by remember(code) { mutableStateOf(false) }
    var countOpen by remember(unit.id, code, state.lease) { mutableStateOf(false) }
    var countDraft by remember(unit.id, code, state.lease) { mutableStateOf<PenCountPreview?>(null) }
    val noteEditor = state.penNoteDrafts[code] ?: PenNoteEditor()
    val deviceRows = state.penDevices?.value.orEmpty()
    val openFaults = deviceRows.filter { it.status == "Fault" }
    val resolvedFaults = deviceRows.filter { it.status == "Normal" }
    val otherFaults = deviceRows.filter { it.status !in setOf("Fault", "Normal") }
    val selectedFault = deviceRows.firstOrNull { it.id == selectedFaultId }
    val colors = SentriTheme.colors
    val page = state.penPage?.value
    val penNumber = page?.pigs?.firstOrNull()?.location?.penNumber
        ?: state.page?.value?.pigs?.firstOrNull { it.location?.penLocation == code }?.location?.penNumber
        ?: state.penRoster?.value?.registeredPens?.firstOrNull { it.code == code }?.number
        ?: state.penRoster?.value?.pens?.firstOrNull { it.code == code }?.number
    val title = if (penNumber != null && penNumber > 0)
        stringResource(R.string.inspection_pen_number, penNumber)
    else stringResource(R.string.inspection_pen_code, code)
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = SentriType.sans, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(localUnitName(unit), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            item { WalkMessage(stringResource(R.string.inspection_pen_read_only)) }
            item { SentriButton(stringResource(R.string.inspection_fault_report_title),
                { reportOpen = true }, Modifier.fillMaxWidth(), primary = false) }
            if (state.penPage?.source == DataSource.CACHE)
                item { WalkMessage(stringResource(R.string.inspection_pen_offline)) }
            if (state.penFailed) item { WalkMessage(stringResource(R.string.inspection_pen_failed)) }
            if (state.penLoading && page == null)
                item { WalkMessage(stringResource(R.string.inspection_pen_loading)) }
            if (state.penDevices != null || state.penDevicesLoading || state.penDevicesFailed) {
                item { SentriSectionHeading(stringResource(R.string.inspection_pen_faults),
                    meta = if (state.penDevices != null) openFaults.size.toString() else null) }
                if (state.penDevicesLoading && state.penDevices == null)
                    item { WalkMessage(stringResource(R.string.inspection_pen_faults_loading)) }
                if (state.penDevicesFailed) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalkMessage(stringResource(R.string.inspection_pen_faults_failed))
                        SentriButton(stringResource(R.string.inspection_retry), onDevicesRetry,
                            Modifier.fillMaxWidth(), primary = false)
                    }
                }
                if (state.penDevices?.source == DataSource.CACHE)
                    item { WalkMessage(stringResource(R.string.inspection_pen_faults_offline)) }
                if (focusedFaultId != null && state.penDevices?.source == DataSource.NETWORK &&
                    !state.penDevicesLoading && deviceRows.none { it.id == focusedFaultId })
                    item { WalkMessage(stringResource(R.string.inspection_pen_fault_no_longer_here)) }
                if (state.penDevices != null && openFaults.isEmpty())
                    item { WalkMessage(stringResource(R.string.inspection_pen_faults_none)) }
                items(openFaults, key = { "open:${it.id}" }) { fault ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(faultName(fault), fault.remark?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.inspection_pen_fault_description_missing),
                            "toolbox", { selectedFaultId = fault.id })
                    }
                }
            }
            if (state.penInventory != null || state.penInventoryLoading || state.penInventoryFailed) {
                item { SentriSectionHeading(stringResource(R.string.inspection_pen_inventory_title)) }
                if (state.penInventoryLoading && state.penInventory == null)
                    item { WalkMessage(stringResource(R.string.inspection_pen_inventory_loading)) }
                if (state.penInventoryFailed) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalkMessage(stringResource(R.string.inspection_pen_inventory_failed))
                        SentriButton(stringResource(R.string.inspection_retry), onInventoryRetry,
                            Modifier.fillMaxWidth(), primary = false)
                    }
                }
                state.penInventory?.takeIf { it.value.locationCode == code }?.let { inventory ->
                    val count = inventory.value.totalCount
                    val identified = inventory.value.identityCount
                    item { SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(stringResource(R.string.inspection_pen_inventory_pigs, count),
                            stringResource(R.string.inspection_pen_inventory_identity,
                                identified, count - identified), null, onClick = {
                                if (countDraft == null) countDraft = PenCountPreview(inventory.value)
                                countOpen = true
                            })
                    } }
                    if (inventory.source == DataSource.CACHE)
                        item { WalkMessage(stringResource(R.string.inspection_pen_inventory_offline)) }
                    if (page?.total != null && page.total != count &&
                        inventory.source == DataSource.NETWORK && state.penPage?.source == DataSource.NETWORK)
                        item { WalkMessage(stringResource(R.string.inspection_pen_inventory_mismatch)) }
                }
            }
            if (onNoteOpen != null) item {
                SentriPanel(Modifier.fillMaxWidth()) {
                    SentriRow(stringResource(R.string.inspection_pen_note_title), stringResource(when {
                        noteEditor.unchanged -> R.string.inspection_pen_note_saved
                        noteEditor.text.isNotBlank() -> R.string.inspection_pen_note_unsaved
                        else -> R.string.inspection_pen_note_entry
                    }), "note", onClick = { noteOpen = true; onNoteOpen(code) })
                }
            }
            item { SentriSectionHeading(stringResource(R.string.inspection_pen_log_title)) }
            item { WalkMessage(stringResource(R.string.inspection_pen_log_pending)) }
            if (page != null) {
                item {
                    SentriSectionHeading(stringResource(R.string.inspection_pen_profiles),
                        meta = stringResource(R.string.inspection_pen_profile_count,
                            page.total ?: page.pigs.size))
                }
                if (page.pigs.isEmpty()) item { WalkMessage(stringResource(R.string.inspection_pen_empty)) }
                if (page.pigs.isNotEmpty()) item {
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column { page.pigs.forEach { pig ->
                            InspectionAnimalRow(pig, pig.id in state.selectedPigs,
                                { onSelect(pig.id) }, { onPigSelect(pig) })
                        } }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (page.request.offset > 0)
                            SentriButton(stringResource(R.string.inspection_previous), onPrevious,
                                Modifier.weight(1f), primary = false)
                        if (page.total?.let { page.request.offset + page.pigs.size < it } == true)
                            SentriButton(stringResource(R.string.inspection_next), onNext,
                                Modifier.weight(1f), primary = false)
                    }
                }
            }
            if (resolvedFaults.isNotEmpty()) {
                item { SentriSectionHeading(stringResource(R.string.inspection_pen_faults_history),
                    meta = resolvedFaults.size.toString()) }
                items(resolvedFaults, key = { "resolved:${it.id}" }) { fault ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(faultName(fault), fault.remark, "toolbox", { selectedFaultId = fault.id })
                    }
                }
            }
            if (otherFaults.isNotEmpty()) {
                item { SentriSectionHeading(stringResource(R.string.inspection_pen_faults_unknown),
                    meta = otherFaults.size.toString()) }
                items(otherFaults, key = { "other:${it.id}" }) { fault ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        SentriRow(faultName(fault), fault.status, "toolbox", { selectedFaultId = fault.id })
                    }
                }
            }
            if (state.penFailed || (!state.penLoading && page == null)) item {
                SentriButton(stringResource(R.string.inspection_retry), { onRetry(code) },
                    Modifier.fillMaxWidth(), primary = false)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (state.selectedPigs.isNotEmpty()) SelectionDock(state.selectedPigs.size,
            onReview = onSelectedOpen, onActions = onActionsOpen)
    }
    selectedFault?.let { fault ->
        SentriSheet(faultName(fault), stringResource(R.string.home_back), stringResource(R.string.home_close),
            { selectedFaultId = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.penDevices?.source == DataSource.CACHE)
                    WalkMessage(stringResource(R.string.inspection_pen_faults_offline))
                Text(fault.remark?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.inspection_pen_fault_description_missing),
                    fontFamily = SentriType.sans, color = colors.ink)
                fault.reportedAt?.let {
                    Text("${stringResource(R.string.inspection_pen_fault_reported)} · ${localCheckInTime(it)}",
                        fontFamily = SentriType.sans, color = colors.muted)
                }
                if (fault.status == "Fault")
                    Text(stringResource(R.string.inspection_pen_fault_resolve_pending),
                        fontFamily = SentriType.sans, color = colors.muted)
            }
        }
    }
    if (countOpen) countDraft?.let { draft ->
        val currentInventory = state.penInventory
        val batchNames = state.penBatches.mapNotNull { (id, result) ->
            result.value?.takeIf { it.id == id && it.farmId == unit.farmId && it.serial.isNotBlank() }
                ?.let { id to it.serial }
        }.toMap()
        val stale = currentInventory?.value?.let { latest -> latest.locationCode != draft.baseline.locationCode ||
            latest.pigs.toSet() != draft.baseline.pigs.toSet() } != false
        PenCountPreviewSheet(draft, batchNames, stale, currentInventory?.source == DataSource.CACHE,
            batchesLoading = state.penBatchesLoading, batchesFailed = state.penBatchesFailed,
            batchesOffline = state.penBatches.values.any { it.source == DataSource.CACHE },
            onBatchesRetry = onInventoryRetry,
            onChange = { countDraft = it }, onReset = {
                val latest = state.penInventory?.value
                if (latest != null) countDraft = PenCountPreview(latest) else onInventoryRetry()
            }, onDismiss = { countOpen = false })
    }
    if (noteOpen) PenNoteDraftSheet(code, noteEditor,
        onChange = { onNoteChange(code, it) }, onSave = { onNoteSave(code) },
        onClear = { onNoteClear(code) }, onRetry = { onNoteOpen?.invoke(code) },
        onDismiss = { noteOpen = false })
    if (reportOpen) DeviceFaultDraftSheet(code, openFaults,
        draft = state.faultDrafts[code] ?: DeviceFaultDraft(),
        loading = state.faultDraftsLoading, loadFailed = state.faultDraftsLoadFailed,
        saving = code in state.faultDraftSavingPens, saved = code in state.faultDraftSavedPens,
        saveFailed = code in state.faultDraftSaveFailedPens,
        onSave = onFaultDraftSave?.let { save -> { save(code) } }, onRetry = onFaultDraftRetry,
        onDraftChange = { onFaultDraftChange(code, it) }, onExistingFault = { id ->
        reportOpen = false
        selectedFaultId = id
    }, onDismiss = { reportOpen = false })
}

@Composable private fun faultName(fault: DeviceInspection): String = fault.deviceName?.takeIf(String::isNotBlank)
    ?: stringResource(when (fault.deviceType) {
        "Feed" -> R.string.inspection_pen_fault_feed
        "Water" -> R.string.inspection_pen_fault_water
        else -> R.string.inspection_pen_fault_other
    })

@Composable
private fun FindPigPage(unit: FarmUnit, state: InspectionWalkState, query: String,
    onQueryChange: (String) -> Unit, onFind: (String) -> Unit, onCancel: () -> Unit,
    onScan: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onPigSelect: (String) -> Unit, onSelectedOpen: () -> Unit, onActionsOpen: () -> Unit,
    onPigOpen: (PigProfile) -> Unit, onTransfer: (PigProfile) -> Unit) {
    val colors = SentriTheme.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var scannerConnected by remember { mutableStateOf(false) }
    val searchedQuery = query.trim()
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    DisposableEffect(context) {
        val input = AndroidScannerInput(context, onDevices = { scannerConnected = it.isNotEmpty() },
            onResult = {}, onDisconnect = { scannerConnected = false })
        onDispose { input.close() }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    LaunchedEffect(searchedQuery, state.searchQuery) {
        if (searchedQuery.isNotEmpty() && searchedQuery != state.searchQuery) {
            delay(250)
            onFind(searchedQuery)
        }
    }
    Surface(Modifier.fillMaxSize(), color = colors.paper) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onQueryChange, Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.inspection_find_hint)) },
                    leadingIcon = { SentriIcon("search", null, Modifier.size(18.dp), colors.muted) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotEmpty()) SentriIconButton("close",
                                stringResource(R.string.inspection_search_clear),
                                { onQueryChange(""); focus.requestFocus() }, bordered = false)
                            if (scannerConnected) SentriIconButton("scan",
                                stringResource(R.string.inspection_scan_title), {
                                    keyboard?.hide(); onScan()
                                }, bordered = false)
                        }
                    }, singleLine = true)
                Text(stringResource(R.string.inspection_search_cancel),
                    Modifier.clickable { keyboard?.hide(); onCancel() }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.ink)
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                if (searchedQuery.isEmpty()) item {
                    WalkMessage(stringResource(R.string.inspection_search_hint_all_units))
                }
                if (state.searchLoading && searchedQuery == state.searchQuery) item {
                    WalkMessage(stringResource(R.string.inspection_search_loading))
                }
                if (state.searchFailed && searchedQuery == state.searchQuery) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalkMessage(stringResource(R.string.inspection_search_failed))
                        SentriButton(stringResource(R.string.inspection_retry), { onFind(searchedQuery) },
                            Modifier.fillMaxWidth(), primary = false)
                    }
                }
                state.searchPage?.takeIf { searchedQuery.isNotEmpty() &&
                    searchedQuery == state.searchQuery }?.let { result ->
                    val found = result.value
                    if (result.source == DataSource.CACHE) item {
                        WalkMessage(stringResource(R.string.inspection_search_offline))
                    }
                    item {
                        Text(stringResource(R.string.inspection_search_count, found.total ?: found.pigs.size),
                            fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.ink)
                        Text(stringResource(R.string.inspection_page_range,
                            found.request.offset + if (found.pigs.isEmpty()) 0 else 1,
                            found.request.offset + found.pigs.size, found.total ?: found.pigs.size),
                            fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                    }
                    if (found.pigs.isEmpty()) item {
                        WalkMessage(stringResource(R.string.inspection_search_no_match, searchedQuery))
                    }
                    found.pigs.groupBy { it.location?.unitId }.toList()
                        .sortedWith(compareBy({ if (it.first == unit.id) 0 else 1 }, { it.first.orEmpty() }))
                        .forEach { (sourceUnitId, members) ->
                            item(key = "find-group:$sourceUnitId") {
                                val source = members.first().location
                                val sourceName = if (sourceUnitId == unit.id) localUnitName(unit)
                                    else source?.unit?.let { if (chinese) it.nameCn else it.nameEn }
                                        ?.takeIf(String::isNotBlank)
                                        ?: source?.unitLocation?.takeIf(String::isNotBlank)
                                        ?: stringResource(R.string.inspection_scan_unknown_unit)
                                SentriSectionHeading(sourceName)
                                val ordered = members.sortedWith(compareBy({ findMatchRank(it, searchedQuery) },
                                    { it.earTag ?: it.earNotch ?: it.extendedEarTag ?: it.id }, { it.id }))
                                ordered.groupBy { it.location?.penLocation }.forEach { (penCode, pigs) ->
                                    SentriPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Column {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(markSearchMatch(penCode ?: stringResource(R.string.inspection_unknown_pen),
                                                    searchedQuery, colors.muted, colors.ink), Modifier.weight(1f),
                                                    fontFamily = SentriType.sans, fontSize = 13.sp)
                                                Text(stringResource(R.string.inspection_search_pen_matches, pigs.size),
                                                    fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
                                            }
                                            pigs.forEach { pig ->
                                                val selectable = InspectionScanResults.selectable(pig, unit.farmId, unit.id)
                                                val identity = pigLabel(pig)
                                                val matchedContext = if (identity.contains(searchedQuery, ignoreCase = true)) null
                                                    else when {
                                                        pig.earNotch?.contains(searchedQuery, ignoreCase = true) == true ->
                                                            stringResource(R.string.inspection_search_matched_notch, pig.earNotch)
                                                        pig.extendedEarTag?.contains(searchedQuery, ignoreCase = true) == true ->
                                                            stringResource(R.string.inspection_search_matched_extended, pig.extendedEarTag)
                                                        else -> null
                                                    }
                                                InspectionAnimalRow(pig, selectable && pig.id in state.selectedPigs,
                                                    if (selectable) ({ keyboard?.hide(); onPigSelect(pig.id) }) else null,
                                                    { keyboard?.hide(); onPigOpen(pig) },
                                                    contextOverride = matchedContext, highlightQuery = searchedQuery)
                                                if (pig.farmId == unit.farmId && pig.lifecycle == "ACTIVE" &&
                                                    pig.location?.unitId?.let { it != unit.id } == true)
                                                    SentriButton(stringResource(R.string.inspection_scan_move_into_unit), {
                                                        keyboard?.hide(); onTransfer(pig)
                                                    }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                                        primary = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (found.request.offset > 0)
                                SentriButton(stringResource(R.string.inspection_previous), onPrevious,
                                    Modifier.weight(1f), primary = false)
                            if (found.total?.let { found.request.offset + found.pigs.size < it } == true)
                                SentriButton(stringResource(R.string.inspection_next), onNext,
                                    Modifier.weight(1f), primary = false)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (state.selectedPigs.isNotEmpty()) SelectionDock(state.selectedPigs.size,
                { keyboard?.hide(); onSelectedOpen() }, { keyboard?.hide(); onActionsOpen() })
        }
    }
}

private fun findMatchRank(pig: PigProfile, query: String): Int {
    val tags = listOfNotNull(pig.earTag, pig.earNotch, pig.extendedEarTag)
    return when {
        tags.any { it.startsWith(query, ignoreCase = true) } -> 0
        tags.any { it.contains(query, ignoreCase = true) } -> 1
        else -> 2
    }
}

private fun markSearchMatch(value: String, query: String, muted: Color, ink: Color): AnnotatedString =
    buildAnnotatedString {
        val match = value.indexOf(query, ignoreCase = true)
        if (query.isBlank() || match < 0) {
            withStyle(SpanStyle(color = muted)) { append(value) }
        } else {
            withStyle(SpanStyle(color = muted)) { append(value.take(match)) }
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) {
                append(value.substring(match, match + query.length))
            }
            withStyle(SpanStyle(color = muted)) { append(value.drop(match + query.length)) }
        }
    }

@Composable
private fun InspectionLensButton(label: String, count: Int?, accessibleLabel: String,
    onClick: () -> Unit, modifier: Modifier, isSelected: Boolean = false, enabled: Boolean = true) {
    val colors = SentriTheme.colors
    val ink = when {
        !enabled -> colors.muted.copy(alpha = .55f)
        isSelected -> colors.paper
        else -> colors.ink
    }
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.height(56.dp).semantics {
            contentDescription = accessibleLabel
            selected = isSelected
            role = Role.Tab
        }, shape = RoundedCornerShape(12.dp),
        color = when { !enabled -> colors.well; isSelected -> colors.ink; else -> colors.paper },
        border = if (isSelected) null else BorderStroke(1.dp, colors.controlBorder)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, fontFamily = SentriType.sans, fontSize = 12.sp, color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count?.toString() ?: "—", fontFamily = SentriType.mono, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun WalkToolDockAction(label: String, icon: String, onClick: () -> Unit,
    modifier: Modifier, primary: Boolean = false) {
    val colors = SentriTheme.colors
    Surface(onClick = onClick, modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp), color = if (primary) colors.ink else colors.paper,
        border = if (primary) null else BorderStroke(1.dp, colors.controlBorder)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            SentriIcon(icon, null, Modifier.size(19.dp), if (primary) colors.paper else colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(label, fontFamily = SentriType.sans, fontSize = 11.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (primary) colors.paper else colors.ink)
        }
    }
}

@Composable
private fun SelectionDock(count: Int, onReview: () -> Unit, onActions: () -> Unit) {
    val colors = SentriTheme.colors
    Surface(color = colors.paper, border = BorderStroke(1.dp, colors.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SentriButton(stringResource(R.string.inspection_selected_count, count), onReview,
                Modifier.weight(1f), primary = false)
            SentriButton(stringResource(R.string.inspection_actions_title), onActions,
                Modifier.weight(1f))
        }
    }
}

@Composable
private fun SelectedPigsPage(unit: FarmUnit, pigs: List<PigProfile>, onBack: () -> Unit,
    onDeselect: (String) -> Unit, onClear: () -> Unit, onActions: () -> Unit) {
    val colors = SentriTheme.colors
    val groups = pigs.groupBy { it.location?.penLocation }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inspection_selected_title), fontFamily = SentriType.sans,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(stringResource(R.string.inspection_selected_count, pigs.size), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            }
            SentriButton(stringResource(R.string.inspection_clear_selection), onClear, primary = false)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { WalkMessage(stringResource(R.string.inspection_selection_scope, localUnitName(unit))) }
            item { SentriButton(stringResource(R.string.inspection_actions_title), onActions,
                Modifier.fillMaxWidth()) }
            groups.forEach { (penCode, members) ->
                item(key = "selected-$penCode") {
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column {
                            Text(penCode ?: stringResource(R.string.inspection_unknown_pen),
                                Modifier.padding(14.dp), fontFamily = SentriType.sans,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                            members.forEach { pig ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val pigSelectLabel = stringResource(R.string.inspection_select_pig, pigLabel(pig))
                                    Checkbox(true, onCheckedChange = { onDeselect(pig.id) },
                                        modifier = Modifier.semantics { contentDescription = pigSelectLabel })
                                    SentriRow(pigLabel(pig), null, null, onClick = { onDeselect(pig.id) },
                                        modifier = Modifier.weight(1f), selected = true)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun InspectionActionsSheet(pigs: List<PigProfile>, onDismiss: () -> Unit,
    onTreat: (() -> Unit)?, subjectLabel: String? = null,
    eligibleCount: Int = pigs.size, treatmentUnavailableReason: String? = null) {
    var unavailableOpen by remember(pigs.map(PigProfile::id), subjectLabel) { mutableStateOf(false) }
    val pending = stringResource(R.string.inspection_action_not_connected)
    val treatAccessible = stringResource(R.string.inspection_action_treat_accessible,
        stringResource(R.string.inspection_action_tile_treat), stringResource(R.string.treatment_draft_title))
    val healthActions = listOf(
        R.string.inspection_action_condition,
        R.string.inspection_action_edit,
        R.string.inspection_action_resolve,
        R.string.inspection_action_care,
    ) + if (onTreat == null) listOf(R.string.treatment_draft_title) else emptyList()
    val generalActions = listOf(
        R.string.inspection_action_note,
        R.string.inspection_action_transfer,
        R.string.inspection_action_measure,
        R.string.inspection_action_feed,
    )
    SentriSheet(stringResource(R.string.inspection_actions_title), stringResource(R.string.home_back),
        stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(subjectLabel ?: stringResource(R.string.inspection_selected_count, pigs.size),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = SentriTheme.colors.muted)
            if (eligibleCount < pigs.size) Text(
                stringResource(R.string.inspection_action_excluded_pigs, pigs.size - eligibleCount),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = SentriTheme.colors.muted)
            if (onTreat != null) {
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Surface(onClick = onTreat,
                            modifier = Modifier.width(78.dp).semantics {
                                contentDescription = treatAccessible
                            }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp),
                                    color = SentriTheme.colors.rowIconBackground) {
                                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                        SentriIcon("treat", null, Modifier.size(22.dp), SentriTheme.colors.rowIconInk)
                                    }
                                }
                                Text(stringResource(R.string.inspection_action_tile_treat), fontFamily = SentriType.sans,
                                    fontSize = 13.sp, color = SentriTheme.colors.ink)
                            }
                        }
                        Text(stringResource(R.string.inspection_action_treatment_preview),
                            Modifier.padding(top = 8.dp), fontFamily = SentriType.sans, fontSize = 11.sp,
                            color = SentriTheme.colors.muted)
                    }
                }
            }
            SentriPanel(Modifier.fillMaxWidth()) {
                SentriRow(stringResource(R.string.inspection_action_unavailable, healthActions.size + generalActions.size),
                    pending, "more", onClick = { unavailableOpen = !unavailableOpen })
            }
            if (unavailableOpen) SentriPanel(Modifier.fillMaxWidth()) {
                Column {
                    SentriSectionHeading(stringResource(R.string.inspection_actions_health),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    healthActions.forEach { title -> SentriRow(stringResource(title),
                        if (title == R.string.treatment_draft_title)
                            treatmentUnavailableReason ?: stringResource(R.string.treatment_draft_no_active_pigs)
                        else stringResource(R.string.inspection_action_health_read_only), null, null) }
                    SentriSectionHeading(stringResource(R.string.inspection_actions_general),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    generalActions.forEach { title -> SentriRow(stringResource(title),
                        stringResource(when (title) {
                            R.string.inspection_action_note -> R.string.inspection_action_note_unavailable
                            R.string.inspection_action_transfer -> R.string.inspection_action_transfer_unavailable
                            R.string.inspection_action_measure -> R.string.inspection_action_measure_unavailable
                            else -> R.string.inspection_action_feed_unavailable
                        }), null, null) }
                }
            }
        }
    }
}

@Composable private fun WalkMessage(message: String) {
    SentriPanel(Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), fontFamily = SentriType.sans, fontSize = 12.sp,
            color = SentriTheme.colors.muted)
    }
}

@Composable private fun localUnitName(unit: FarmUnit) =
    if (LocalConfiguration.current.locales[0].language == "zh") unit.nameCn else unit.nameEn

@Composable private fun pigLabel(pig: PigProfile): String =
    pig.earTag?.takeIf(String::isNotBlank) ?: pig.earNotch?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inspection_no_tag)

@Composable private fun recordedConditionNames(pig: PigProfile): List<String> {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val diseases = pig.health?.diseases.orEmpty().map { item -> item.detail?.let {
        catalogName(it.nameCn, it.nameEn, chinese).takeIf(String::isNotBlank) }
        ?: stringResource(R.string.inspection_unknown_disease) }
    val symptoms = pig.health?.symptoms.orEmpty().map { item -> item.detail?.let {
        catalogName(it.nameCn, it.nameEn, chinese).takeIf(String::isNotBlank) }
        ?: stringResource(R.string.inspection_unknown_symptom) }
    return diseases + symptoms
}

@Composable private fun InspectionAnimalRow(pig: PigProfile, selected: Boolean,
    onSelectedChange: (() -> Unit)?, onOpen: () -> Unit, contextOverride: String? = null,
    highlightQuery: String? = null, arriving: Boolean = false) {
    val names = if (pig.health == null) listOf(stringResource(R.string.inspection_conditions_unknown))
        else recordedConditionNames(pig)
    val description = when {
        pig.health == null -> stringResource(R.string.inspection_health_not_returned)
        names.isEmpty() -> stringResource(R.string.inspection_no_recorded_conditions)
        else -> stringResource(R.string.inspection_recorded_condition_names, names.joinToString(" · "))
    }
    SentriAnimalRow(pigLabel(pig), contextOverride ?: inspectionRosterDetail(pig), names, description,
        onOpen, selected = selected,
        selectionLabel = stringResource(R.string.inspection_select_pig, pigLabel(pig)),
        onSelectedChange = onSelectedChange?.let { action -> { _: Boolean -> action() } },
        highlightQuery = highlightQuery, arriving = arriving)
}

private fun inspectionStageLabelId(code: String?): Int? = when (code) {
        "PREGNANT" -> R.string.inspection_stage_gestation
        "LACTATING" -> R.string.inspection_stage_lactation
        "FATTENING" -> R.string.biz_production_fattening
        "IN_ESTRUS" -> R.string.biz_production_in_estrus
        "MATED" -> R.string.biz_production_mated
        "NOT_IN_ESTRUS" -> R.string.biz_production_not_in_estrus
        "OBSOLETED" -> R.string.biz_production_obsoleted
        else -> null
    }

@Composable private fun inspectionRosterDetail(pig: PigProfile): String {
    val status = inspectionStageLabelId(pig.breederProductionStatus)
    val stage = status?.let { stringResource(R.string.inspection_stage_days_unknown, stringResource(it)) }
        ?: stringResource(R.string.inspection_stage_unavailable)
    return stage
}
