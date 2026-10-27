package com.sentri.nativeapp.home

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriIcon
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.inspection.InspectionWalkScreen
import com.sentri.nativeapp.inspection.InspectionWalkViewModel
import com.sentri.nativeapp.therapy.MedicineDirectoryViewModel
import com.sentri.nativeapp.therapy.MedicineDirectoryScreen
import com.sentri.nativeapp.therapy.MedicineDirectoryState
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.vaccination.VaccinationTask
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.sentri.nativeapp.network.FarmTimezone
import kotlinx.coroutines.delay

/** The signed-in workbench only renders facts read for the current farm. */
@Composable
fun ConnectedHomeScreen(state: HomeLiveState, inspection: InspectionWalkViewModel? = null,
    medicines: MedicineDirectoryViewModel? = null,
    pregnancyDetail: HomePregnancyDetailViewModel? = null,
    unitOverview: HomeUnitOverviewViewModel? = null,
    vaccinationDetail: HomeVaccinationDetailViewModel? = null,
    estrusDetail: HomeEstrusDetailViewModel? = null,
    reEstrusDetail: HomeReEstrusDetailViewModel? = null,
    matingDetail: HomeMatingDetailViewModel? = null,
    transferDetail: HomeTransferDetailViewModel? = null,
    otherProductionDetail: HomeOtherProductionDetailViewModel? = null,
    onRefresh: () -> Unit, onExit: () -> Unit, sectionStore: HomeSectionStore? = null,
    pickerSummaries: HomePickerSummaryViewModel? = null,
    cardFacts: HomeTaskCardFactsViewModel? = null) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val storedSections = remember(context, sectionStore) { sectionStore ?: AndroidHomeSectionStore(context) }
    val sectionScope = state.lease?.scope
    var sectionId by rememberSaveable(sectionScope) {
        mutableStateOf(sectionScope?.let(storedSections::read))
    }
    var unitId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedVaccinationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTransferId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMaintenanceFault by remember { mutableStateOf<Pair<com.sentri.nativeapp.inspection.DeviceInspection, HomeUnitMaintenance>?>(null) }
    var pendingStockPen by remember { mutableStateOf<Triple<ScopeLease, String, HomeUnitPenRoster>?>(null) }
    var focusedMaintenanceFaultId by remember { mutableStateOf<String?>(null) }
    var visibilityNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lease) {
        visibilityNowMillis = System.currentTimeMillis()
        while (true) {
            delay(30_000)
            visibilityNowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(state.lease) {
        sheet = null; unitId = null
        pendingMaintenanceFault = null
        pendingStockPen = null
        focusedMaintenanceFaultId = null
        medicines?.clear()
        pregnancyDetail?.clear()
        vaccinationDetail?.clear()
        estrusDetail?.clear()
        reEstrusDetail?.clear()
        matingDetail?.clear()
        transferDetail?.clear()
        otherProductionDetail?.clear()
        pickerSummaries?.clear()
        cardFacts?.clear()
    }
    val places = state.places?.value
    val sections = places?.sections.orEmpty()
    val section = sections.firstOrNull { it.id == sectionId } ?: sections.firstOrNull()
    val unit = section?.units?.firstOrNull { it.id == unitId }
    LaunchedEffect(state.lease, unit?.id) {
        inspection?.let { walk ->
            if (walk.state.value.lease != state.lease || walk.state.value.unit?.id != unit?.id)
                walk.clear()
        }
    }
    LaunchedEffect(state.lease, unit?.id, unit?.locationCode) {
        if (state.lease != null && unit != null) unitOverview?.open(state.lease, unit)
        else unitOverview?.clear()
    }
    val unitOverviewState = unitOverview?.state?.collectAsStateWithLifecycle()?.value
        ?.takeIf { it.lease == state.lease && it.unitId == unit?.id }
    val scope = remember(state.production, state.places, visibilityNowMillis) {
        if (state.production != null && places != null)
            HomeTaskScopeProjector.project(state.production.value, places, visibilityNowMillis)
        else null
    }
    val tasks = when {
        section == null || scope == null -> emptyList()
        unit != null -> scope.inUnit(unit.id)
        else -> scope.inSection(section.id).sortedWith(compareBy<HomeScopedTask>(
            { overviewProductionRank(it.task.kind) }, { it.task.id }))
    }
    val heatTasks = if (unit == null) emptyList() else tasks.filter {
        it.task.kind == ProductionTaskKind.ESTRUS
    }
    LaunchedEffect(state.lease, unit?.id, state.production?.fetchedAt,
        state.places?.fetchedAt, heatTasks) {
        if (state.lease != null && unit != null && places != null && state.production != null)
            cardFacts?.open(state.lease, unit.id, heatTasks, places,
                state.production.fetchedAt, state.places.fetchedAt)
        else cardFacts?.clear()
    }
    val cardFactState = cardFacts?.state?.collectAsStateWithLifecycle()?.value
        ?.takeIf { it.lease == state.lease && it.unitId == unit?.id }
    val vaccinationScope = remember(state.vaccination, state.places, visibilityNowMillis) {
        if (state.vaccination != null && places != null)
            HomeVaccinationScopeProjector.project(state.vaccination.value, places, visibilityNowMillis)
        else null
    }
    val vaccinationTasks = when {
        section == null || vaccinationScope == null -> emptyList()
        unit != null -> vaccinationScope.inUnit(unit.id)
        else -> vaccinationScope.inSection(section.id)
    }
    val transferScope = remember(state.transfer, state.places, visibilityNowMillis) {
        if (state.transfer != null && places != null)
            HomeTransferScopeProjector.project(state.transfer.value, places, visibilityNowMillis)
        else null
    }
    val transferTasks = when {
        section == null || transferScope == null -> emptyList()
        unit != null -> transferScope.inUnit(unit.id)
        else -> transferScope.inSection(section.id)
    }
    val visibleWork = if (section == null) emptyList()
        else HomeUnitWorkOrder.order(tasks, vaccinationTasks, transferTasks)
    val upcomingSchedule = remember(state.production, state.vaccination, state.transfer,
        state.places, state.account?.farm?.timezone, unit?.id, visibilityNowMillis) {
        if (unit == null || places == null || state.production == null || state.vaccination == null ||
            state.transfer == null) null else {
            val productionAhead = HomeTaskScopeProjector.project(state.production.value,
                places, Long.MAX_VALUE).inUnit(unit.id)
            val vaccinationAhead = HomeVaccinationScopeProjector.project(state.vaccination.value,
                places, Long.MAX_VALUE).inUnit(unit.id)
            val transferAhead = HomeTransferScopeProjector.project(state.transfer.value,
                places, Long.MAX_VALUE).inUnit(unit.id)
            HomeUpcomingSchedule.build(HomeUnitWorkOrder.order(productionAhead,
                vaccinationAhead, transferAhead), state.account?.farm?.timezone, visibilityNowMillis)
        }
    }
    var selectedUpcomingDate by remember(state.lease?.scope, unit?.id) { mutableStateOf<String?>(null) }
    val selectedUpcomingDay = upcomingSchedule?.days?.firstOrNull { it.dateKey == selectedUpcomingDate }
    LaunchedEffect(upcomingSchedule?.days?.firstOrNull()?.dateKey) {
        if (selectedUpcomingDate != null && selectedUpcomingDay == null) selectedUpcomingDate = null
    }
    val visibleTaskCount = visibleWork.size
    val pickerSectionCounts = if (scope == null || vaccinationScope == null || transferScope == null)
        null else sections.associate { candidate ->
            candidate.id to HomeUnitWorkOrder.order(scope.inSection(candidate.id),
                vaccinationScope.inSection(candidate.id), transferScope.inSection(candidate.id)).size
        }
    val pickerUnitCounts = if (scope == null || vaccinationScope == null || transferScope == null)
        null else section?.units?.associate { candidate ->
            candidate.id to HomeUnitWorkOrder.order(scope.inUnit(candidate.id),
                vaccinationScope.inUnit(candidate.id), transferScope.inUnit(candidate.id)).size
        }
    val pickerUsesCache = state.places?.source == DataSource.CACHE ||
        state.production?.source == DataSource.CACHE ||
        state.vaccination?.source == DataSource.CACHE || state.transfer?.source == DataSource.CACHE
    val pickerSummaryState = pickerSummaries?.state?.collectAsStateWithLifecycle()?.value
        ?.takeIf { it.lease == state.lease }
    val pickerSummaryUsesCache = pickerSummaryState?.units?.values?.any {
        it.stock?.source == DataSource.CACHE || it.lastCheckIn?.source == DataSource.CACHE
    } == true
    LaunchedEffect(sheet, state.lease, sections, state.places?.fetchedAt) {
        if (sheet == "sections" || sheet == "units") state.lease?.let { lease ->
            pickerSummaries?.open(lease, sections.flatMap(HomePlaceSection::units),
                state.places?.fetchedAt ?: 0L)
        }
        else pickerSummaries?.close()
    }
    val workGroups = remember(visibleWork) { HomeWorkGroups.group(visibleWork) }
    var groupOverrides by remember(state.lease?.scope, section?.id, unit?.id) {
        mutableStateOf<Map<HomeWorkCategory, Boolean>>(emptyMap())
    }
    val task = tasks.firstOrNull { it.task.id == selectedTaskId }
    val vaccinationTask = vaccinationScope?.visible?.firstOrNull { it.task.id == selectedVaccinationId }
    val transferTask = transferTasks.firstOrNull { it.task.id == selectedTransferId }
    val pregnancyState = pregnancyDetail?.state?.collectAsStateWithLifecycle()?.value
    val estrusState = estrusDetail?.state?.collectAsStateWithLifecycle()?.value
    val reEstrusState = reEstrusDetail?.state?.collectAsStateWithLifecycle()?.value
    val matingState = matingDetail?.state?.collectAsStateWithLifecycle()?.value
    val otherProductionState = otherProductionDetail?.state?.collectAsStateWithLifecycle()?.value
    val vaccinationDetailState = vaccinationDetail?.state?.collectAsStateWithLifecycle()?.value
    val transferDetailState = transferDetail?.state?.collectAsStateWithLifecycle()?.value
    val openTask: (String) -> Unit = { id ->
        selectedTaskId = id
        sheet = "task"
        if (state.lease != null && tasks.any { it.task.id == id && it.task.kind == ProductionTaskKind.PREGNANCY })
            pregnancyDetail?.open(state.lease, id)
        if (state.lease != null && tasks.any { it.task.id == id && it.task.kind == ProductionTaskKind.ESTRUS })
            estrusDetail?.open(state.lease, id)
        if (state.lease != null && tasks.any { it.task.id == id && it.task.kind == ProductionTaskKind.RE_ESTRUS })
            reEstrusDetail?.open(state.lease, id)
        if (state.lease != null && tasks.any { it.task.id == id && it.task.kind == ProductionTaskKind.MATING })
            matingDetail?.open(state.lease, id)
        if (state.lease != null) tasks.firstOrNull { it.task.id == id }?.let { selected ->
            if (selected.task.kind in setOf(ProductionTaskKind.FARROWING, ProductionTaskKind.PIGLET_PROCESSING,
                    ProductionTaskKind.POSTPARTUM, ProductionTaskKind.LACTATION,
                    ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING))
                otherProductionDetail?.open(state.lease, selected.task.kind, id)
        }
    }
    val openVaccination: (String) -> Unit = { id ->
        selectedVaccinationId = id
        sheet = "vaccinationTask"
        if (state.lease != null) vaccinationDetail?.open(state.lease, id)
    }
    val openTransfer: (String) -> Unit = { id ->
        selectedTransferId = id
        sheet = "transferTask"
        if (state.lease != null) transferDetail?.open(state.lease, id)
    }
    if (sheet == "inspection" && unit != null && state.lease != null && inspection != null) {
        val inspectionState by inspection.state.collectAsStateWithLifecycle()
        val medicineState = medicines?.state?.collectAsStateWithLifecycle()?.value
        LaunchedEffect(state.lease, unit.id, pendingMaintenanceFault, pendingStockPen) {
            inspection.open(state.lease, unit)
            pendingStockPen?.let { (lease, code, roster) ->
                inspection.openPenFromStock(lease, code, roster)
                pendingStockPen = null
            }
            pendingMaintenanceFault?.let { (issue, maintenance) ->
                focusedMaintenanceFaultId = issue.id.takeIf {
                    inspection.openFaultFromMaintenance(issue, maintenance)
                }
                pendingMaintenanceFault = null
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            InspectionWalkScreen(unit, inspectionState, inspection::refresh, inspection::previous,
            inspection::next, inspection::saveCheckIn,
            onBack = { sheet = null; focusedMaintenanceFaultId = null; unitOverview?.refresh() }, onFind = { inspection.findPig(it) },
            onPreviousSearch = inspection::previousSearchPage, onNextSearch = inspection::nextSearchPage,
            onPigOpen = inspection::openPigDetail, onCatalogOpen = inspection::openCatalog,
            onLensSelected = inspection::selectLens, onHealthLensRetry = inspection::retryHealthLens,
            onFilterOpen = inspection::prepareFilters, onFilterApply = inspection::applyFilters,
            onPenMapOpen = inspection::openPenMap,
            unitMaintenance = unitOverviewState, onMaintenanceRetry = { unitOverview?.refresh() },
            onMaintenanceFaultOpen = { issue ->
                unitOverviewState?.maintenance?.value?.let { maintenance ->
                    focusedMaintenanceFaultId = issue.id.takeIf {
                        inspection.openFaultFromMaintenance(issue, maintenance)
                    }
                }
            },
            onPenOpen = { focusedMaintenanceFaultId = null; inspection.openPen(it) },
            onPenBack = { focusedMaintenanceFaultId = null; inspection.closePen() },
            onPenPrevious = inspection::previousPenPage, onPenNext = inspection::nextPenPage,
            onPigSelection = inspection::togglePigSelection,
            onPenSelection = inspection::toggleVisiblePenSelection,
            onClearSelection = inspection::clearSelection,
            onPenDevicesRetry = inspection::refreshPenDevices,
            onFaultDraftChange = { code, draft -> inspection.updateFaultDraft(state.lease, code, draft) },
            onFaultDraftSave = { code -> inspection.saveFaultDraft(state.lease, code) },
            onFaultDraftRetry = inspection::loadFaultDrafts,
            onPenNoteOpen = { code -> inspection.openPenNoteDraft(state.lease, code) },
            onPenNoteChange = { code, text -> inspection.updatePenNoteDraft(state.lease, code, text) },
            onPenNoteSave = { code -> inspection.savePenNoteDraft(state.lease, code) },
            onPenNoteClear = { code -> inspection.clearPenNoteDraft(state.lease, code) },
            onPenInventoryRetry = inspection::refreshPenInventory,
            onCheckInHistoryOpen = inspection::openCheckInHistory,
            onCheckInHistoryRefresh = inspection::refreshCheckInHistory,
            onCheckInHistoryPrevious = inspection::previousCheckInHistory,
            onCheckInHistoryNext = inspection::nextCheckInHistory,
            onCheckInDetailOpen = inspection::openCheckInDetail,
            onCheckInDetailRefresh = inspection::refreshCheckInDetail,
            onCheckInDetailClose = inspection::closeCheckInDetail,
            onScannedTag = inspection::recordScan,
            focusedFaultId = focusedMaintenanceFaultId,
            medicineDirectory = medicineState?.takeIf { it.lease == state.lease },
            onTreatmentOpen = { medicines?.open(state.lease) },
            onRefreshMedicines = { medicines?.refresh() },
            onTreatmentValidate = inspection::validateTreatmentSelection,
            onTreatmentReviewClose = inspection::closeTreatmentValidation)
        }
        return
    }
    if (sheet == "medicines" && state.lease != null && medicines != null) {
        val medicineState by medicines.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.lease) { medicines.open(state.lease) }
        if (medicineState.lease == state.lease)
            MedicineDirectoryScreen(medicineState, medicines::refresh, onBack = { sheet = "toolbox" })
        else MedicineDirectoryScreen(MedicineDirectoryState(lease = state.lease, loading = true),
            onRefresh = {}, onBack = { sheet = "toolbox" })
        return
    }
    val back = {
        when {
            sheet == "medicines" -> sheet = "toolbox"
            sheet != null -> sheet = null
            unitId != null -> unitId = null
            else -> onExit()
        }
    }
    BackHandler { back() }
    Scaffold(containerColor = SentriTheme.colors.background,
        bottomBar = { WorkbenchBottomBar(false, onHome = { sheet = null; unitId = null },
            onToolbox = { sheet = "toolbox" }, onScan = { sheet = "scan" }) }
    ) { insets ->
        AnimatedContent(targetState = sheet == "sections",
            modifier = Modifier.fillMaxSize().padding(insets),
            transitionSpec = {
                if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                else if (targetState)
                    (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(180)))
                else
                    (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
            }, label = "Section navigation") { choosingSection ->
            if (choosingSection) ConnectedSectionsPage(sections, section?.id,
                pickerSectionCounts, pickerSummaryState, pickerSummaries != null,
                pickerUsesCache || pickerSummaryUsesCache,
                onBack = { sheet = null }, onRetry = { pickerSummaries?.retry() },
                onSelect = { selected ->
                    state.lease?.scope?.let { storedSections.write(it, selected.id) }
                    sectionId = selected.id; unitId = null; sheet = null
                })
            else Column(Modifier.fillMaxSize()) {
            ConnectedHeader(state, section, onSection = { sheet = "sections" }, onRefresh = {
                onRefresh(); if (unit != null) unitOverview?.refresh()
            },
                onSaved = { sheet = "saved" })
            ScopeSelection(unit, onOverview = { unitId = null }, onUnits = { sheet = "units" })
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.places?.source == DataSource.CACHE || state.production?.source == DataSource.CACHE ||
                    state.vaccination?.source == DataSource.CACHE || state.transfer?.source == DataSource.CACHE) item {
                    StatusMessage(stringResource(R.string.connected_offline_snapshot), Modifier.padding(horizontal = 18.dp))
                }
                if (section == null) item {
                    StatusMessage(
                        when {
                            state.placesLoading -> stringResource(R.string.connected_loading_places)
                            state.placesIssue != null -> stringResource(R.string.connected_places_error)
                            else -> stringResource(R.string.connected_no_sections)
                        }, Modifier.padding(horizontal = 18.dp))
                } else {
                    if (unit != null) item {
                        HomeUnitHub(unitOverviewState, onInspect = { sheet = "inspection" },
                            onStock = { sheet = "stock"; unitOverview?.openStock() },
                            onMaintenance = { sheet = "maintenance" },
                            onRetry = { unitOverview?.refresh() },
                            modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    if (unit != null) item {
                        if (upcomingSchedule == null)
                            StatusMessage(stringResource(R.string.connected_upcoming_unavailable),
                                Modifier.padding(horizontal = 18.dp))
                        else ConnectedUpcomingStrip(upcomingSchedule, selectedUpcomingDate,
                            stale = state.account?.cachedAtMillis != null || pickerUsesCache,
                            onSelect = { selectedUpcomingDate = if (selectedUpcomingDate == it) null else it },
                            onOpen = { sheet = "upcoming" },
                            modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.home_todays_work), Modifier.weight(1f),
                                fontFamily = SentriType.sans, fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp, color = SentriTheme.colors.ink)
                            if (state.production != null && state.vaccination != null && state.transfer != null)
                                Text(stringResource(R.string.connected_task_count, visibleTaskCount),
                                    fontFamily = SentriType.sans, fontSize = 11.sp,
                                    color = SentriTheme.colors.muted)
                        }
                    }
                    if (state.production == null) item {
                        StatusMessage(stringResource(if (state.productionLoading) R.string.wb_loading
                            else if (state.productionIssue != null) R.string.connected_tasks_error
                            else R.string.connected_tasks_unavailable), Modifier.padding(horizontal = 18.dp))
                    }
                    if (state.vaccination == null) item {
                        StatusMessage(stringResource(if (state.vaccinationLoading) R.string.connected_vaccination_loading
                            else if (state.vaccinationIssue == HomeReadIssue.INVALID_DATA) R.string.connected_vaccination_invalid
                            else if (state.vaccinationIssue != null) R.string.connected_vaccination_error
                            else R.string.connected_vaccination_unavailable), Modifier.padding(horizontal = 18.dp))
                    }
                    if (state.transfer == null) item {
                        StatusMessage(stringResource(if (state.transferLoading) R.string.connected_transfer_loading
                            else if (state.transferIssue != null) R.string.connected_transfer_error
                            else R.string.connected_transfer_unavailable), Modifier.padding(horizontal = 18.dp))
                    }
                    if (visibleWork.isEmpty() && state.production != null && state.vaccination != null && state.transfer != null)
                        item { StatusMessage(stringResource(R.string.connected_no_visible_tasks),
                            Modifier.padding(horizontal = 18.dp)) }
                    if (visibleWork.size < HomeWorkGroups.GROUP_FROM) {
                        items(visibleWork, key = HomeUnitWorkItem::key) { work ->
                            ConnectedWorkEntry(work, section, unit, places, cardFactState?.heat.orEmpty(), grouped = false,
                                onProduction = openTask, onVaccination = openVaccination,
                                onTransfer = openTransfer)
                        }
                    } else {
                        items(workGroups, key = { "group:${it.category.name}" }) { group ->
                            val open = groupOverrides[group.category] ?: (group.category == workGroups.first().category)
                            ConnectedWorkGroup(group, section, unit, places, cardFactState?.heat.orEmpty(), open,
                                onToggle = { groupOverrides = groupOverrides + (group.category to !open) },
                                onProduction = openTask, onVaccination = openVaccination,
                                onTransfer = openTransfer)
                        }
                    }
                    if (unit == null) {
                        item { SentriSectionHeading(stringResource(R.string.wb_health),
                            Modifier.padding(horizontal = 20.dp)) }
                        item { ConnectedHealthSummaryPending(Modifier.padding(horizontal = 18.dp)) }
                        item { StatusMessage(stringResource(R.string.connected_other_pending),
                            Modifier.padding(horizontal = 18.dp)) }
                    }
                }
                item { Spacer(Modifier.height(22.dp)) }
            }
            }
        }
    }
    when (sheet) {
        "upcoming" -> SentriSheet(stringResource(R.string.connected_upcoming_details_title),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            selectedUpcomingDay?.let { day ->
                val zone = FarmTimezone.zone(state.account?.farm?.timezone)
                val locale = LocalConfiguration.current.locales[0]
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply {
                    timeZone = zone ?: java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(day.dateAnchorMillis)),
                    fontFamily = SentriType.sans, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink)
                day.starts.forEach { item ->
                    val title = when (item) {
                        is HomeUnitWorkItem.Production -> stringResource(R.string.connected_overview_production_instance,
                            taskKind(item.scoped.task.kind), item.scoped.task.batch.serial)
                        is HomeUnitWorkItem.Vaccination -> stringResource(R.string.connected_overview_vaccination_instance,
                            vaccinationName(item.scoped.task), item.scoped.task.planId ?: item.scoped.task.id)
                        is HomeUnitWorkItem.Transfer -> stringResource(R.string.connected_overview_transfer_instance,
                            item.scoped.task.batchSerial ?: item.scoped.task.id)
                    }
                    val time = item.startAt?.let { value -> runCatching {
                        val millis = com.sentri.nativeapp.network.TimestampInstant.seconds(value)
                            .movePointRight(3).toLong()
                        SimpleDateFormat("HH:mm", Locale.ROOT).apply {
                            timeZone = zone ?: return@runCatching ""
                        }.format(Date(millis))
                    }.getOrNull() }?.takeIf(String::isNotBlank)
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, fontFamily = SentriType.sans, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink)
                            Text(if (time == null) stringResource(R.string.connected_upcoming_planned)
                                else stringResource(R.string.connected_upcoming_planned_time, time),
                                fontFamily = SentriType.sans, fontSize = 12.sp,
                                color = SentriTheme.colors.muted)
                        }
                    }
                }
            }
        }
        "units" -> SentriSheet(stringResource(R.string.home_choose_unit), stringResource(R.string.home_back),
            stringResource(R.string.home_close), { sheet = null }) {
            if (pickerUsesCache || pickerSummaryUsesCache) Text(stringResource(R.string.connected_offline_snapshot),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = SentriTheme.colors.muted)
            section?.units?.forEach { item ->
                val count = pickerUnitCounts?.get(item.id)
                val taskDetail = if (count == null) stringResource(R.string.connected_picker_tasks_unknown)
                    else stringResource(R.string.connected_picker_tasks_count, count)
                SentriRow(unitName(item), if (pickerSummaries == null) taskDetail else
                    pickerUnitDetail(taskDetail, pickerSummaryState?.units?.get(item.id)), null,
                    onClick = { unitId = item.id; sheet = null },
                    selected = item.id == unit?.id)
            }
            if (pickerSummaryState?.units?.values?.any { it.stockFailed || it.checkInFailed } == true)
                SentriButton(stringResource(R.string.connected_unit_retry), { pickerSummaries?.retry() },
                    Modifier.fillMaxWidth(), primary = false)
        }
        "task" -> SentriSheet(stringResource(R.string.home_view_task), stringResource(R.string.home_back),
            stringResource(R.string.home_close), { sheet = null }) {
            if (task != null) {
                Text(taskKind(task.task.kind), fontFamily = SentriType.sans, fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, color = SentriTheme.colors.ink)
                if (task.task.kind == ProductionTaskKind.PREGNANCY && pregnancyDetail != null) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id) else section?.units?.map { it.id }?.toSet().orEmpty()
                    HomePregnancyDetailContent(task, allowedUnitIds,
                        pregnancyState?.takeIf { it.lease == state.lease }, pregnancyDetail::refresh)
                } else if (task.task.kind == ProductionTaskKind.ESTRUS && estrusDetail != null && places != null) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id) else section?.units?.map { it.id }?.toSet().orEmpty()
                    HomeEstrusDetailContent(task, places, allowedUnitIds,
                        estrusState?.takeIf { it.lease == state.lease }, estrusDetail::refresh)
                } else if (task.task.kind == ProductionTaskKind.RE_ESTRUS && reEstrusDetail != null && places != null) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id) else section?.units?.map { it.id }?.toSet().orEmpty()
                    HomeReEstrusDetailContent(task, places, allowedUnitIds,
                        reEstrusState?.takeIf { it.lease == state.lease }, reEstrusDetail::refresh)
                } else if (task.task.kind == ProductionTaskKind.MATING && matingDetail != null && places != null) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id) else section?.units?.map { it.id }?.toSet().orEmpty()
                    HomeMatingDetailContent(task, places, allowedUnitIds,
                        matingState?.takeIf { it.lease == state.lease }, matingDetail::refresh)
                } else if (otherProductionDetail != null && places != null &&
                    task.task.kind in setOf(ProductionTaskKind.FARROWING, ProductionTaskKind.PIGLET_PROCESSING,
                        ProductionTaskKind.POSTPARTUM, ProductionTaskKind.LACTATION,
                        ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING)) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id) else section?.units?.map { it.id }?.toSet().orEmpty()
                    HomeOtherProductionDetailContent(task, places, allowedUnitIds,
                        otherProductionState?.takeIf { it.lease == state.lease }, otherProductionDetail::refresh)
                } else {
                    Text(stringResource(R.string.connected_batch, task.task.batch.serial),
                        fontFamily = SentriType.sans, fontSize = 13.sp, color = SentriTheme.colors.muted)
                    Text(stringResource(R.string.connected_task_detail_pending),
                        fontFamily = SentriType.sans, fontSize = 13.sp, color = SentriTheme.colors.muted)
                }
            }
        }
        "vaccinationTask" -> SentriSheet(stringResource(R.string.home_view_task),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            vaccinationTask?.let { item ->
                if (places != null && vaccinationDetail != null) {
                    val allowedUnitIds = if (unit != null) setOf(unit.id)
                        else section?.units?.map(FarmUnit::id)?.toSet().orEmpty()
                    HomeVaccinationDetailContent(item, places, allowedUnitIds,
                        vaccinationDetailState?.takeIf { it.lease == state.lease }, vaccinationDetail::refresh)
                } else Text(stringResource(R.string.connected_vaccination_detail_pending),
                    fontFamily = SentriType.sans, fontSize = 13.sp, color = SentriTheme.colors.muted)
            }
        }
        "transferTask" -> SentriSheet(stringResource(R.string.connected_transfer_title),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            transferTask?.let { item ->
                HomeTransferDetailContent(item, places,
                    transferDetailState?.takeIf { it.lease == state.lease },
                    onRefresh = { transferDetail?.refresh() })
            }
        }
        "stock" -> SentriSheet(stringResource(R.string.connected_unit_stock_entry),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            HomeUnitStockDetail(unitOverviewState, onRetry = { unitOverview?.openStock() },
                onOpenPen = if (inspection != null && unit != null) ({ code ->
                    unitOverviewState?.penRoster?.value?.let { roster ->
                        if (roster.unitId == unit.id && state.lease != null) {
                            pendingStockPen = Triple(state.lease, code, roster)
                            focusedMaintenanceFaultId = null
                            sheet = "inspection"
                        }
                    }
                }) else null)
        }
        "maintenance" -> SentriSheet(stringResource(R.string.connected_unit_maintenance_title),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            HomeUnitMaintenanceDetail(unitOverviewState, onRetry = { unitOverview?.refresh() },
                onOpenFault = { issue ->
                    unitOverviewState?.maintenance?.value?.let { maintenance ->
                        pendingMaintenanceFault = issue to maintenance
                        sheet = "inspection"
                    }
                })
        }
        "saved" -> SentriSheet(stringResource(R.string.home_saved_work), stringResource(R.string.home_back),
            stringResource(R.string.home_close), { sheet = null }) {
            val saved = state.savedWork
            if (saved == null) Text(stringResource(R.string.connected_saved_unavailable))
            else {
                Text(stringResource(R.string.connected_saved_waiting, saved.waiting))
                Text(stringResource(R.string.connected_saved_uploading, saved.uploading))
                Text(stringResource(R.string.connected_saved_attention, saved.attention))
            }
        }
        "toolbox" -> SentriSheet(stringResource(R.string.home_tab_toolbox),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            SentriRow(stringResource(R.string.medicine_directory_title),
                stringResource(R.string.medicine_directory_read_only), "health", { sheet = "medicines" })
        }
        "inspection", "scan" -> SentriSheet(
            if (sheet == "inspection") stringResource(R.string.home_inspect_unit) else stringResource(R.string.home_tab_scan),
            stringResource(R.string.home_back), stringResource(R.string.home_close), { sheet = null }) {
            Text(stringResource(R.string.connected_entry_pending), color = SentriTheme.colors.muted)
        }
    }
}

@Composable private fun ConnectedUpcomingStrip(schedule: HomeUpcomingSchedule,
    selectedDate: String?, stale: Boolean, onSelect: (String) -> Unit, onOpen: () -> Unit,
    modifier: Modifier = Modifier) {
    val colors = SentriTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val zone = requireNotNull(FarmTimezone.zone(schedule.timezone))
    val compact = SimpleDateFormat("M/d", locale).apply { timeZone = zone }
    val weekday = SimpleDateFormat("EEE", locale).apply { timeZone = zone }
    val dayNumber = SimpleDateFormat("d", locale).apply { timeZone = zone }
    val selected = schedule.days.firstOrNull { it.dateKey == selectedDate }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SentriSectionHeading(stringResource(R.string.connected_upcoming_title),
            meta = stringResource(R.string.connected_upcoming_span,
                compact.format(Date(schedule.days.first().dateAnchorMillis)),
                compact.format(Date(schedule.days.last().dateAnchorMillis)), schedule.totalStarts))
        SentriPanel(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(7.dp)) {
                val capacity = (maxWidth / 48.dp).toInt().coerceIn(1, 7)
                val columns = if (capacity in 4..6) 4 else capacity
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    schedule.days.chunked(columns).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                val active = selectedDate == day.dateKey
                                val description = stringResource(R.string.connected_upcoming_day_description,
                                    day.dateKey, day.starts.size)
                                Column(Modifier.weight(1f).defaultMinSize(minWidth = 48.dp, minHeight = 62.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) colors.greenWash else colors.paper)
                                    .clickable { onSelect(day.dateKey) }
                                    .semantics { contentDescription = description; this.selected = active }
                                    .padding(vertical = 7.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(weekday.format(Date(day.dateAnchorMillis)), fontFamily = SentriType.sans,
                                        fontSize = 10.sp, color = colors.muted, maxLines = 1)
                                    Text(dayNumber.format(Date(day.dateAnchorMillis)),
                                        fontFamily = SentriType.sans, fontSize = 13.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = colors.ink)
                                    Box(Modifier.size(4.dp).background(
                                        if (day.starts.isNotEmpty()) colors.green else Color.Transparent, CircleShape))
                                }
                            }
                            repeat(columns - week.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (stale) Text(stringResource(R.string.connected_upcoming_cached),
            fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
        if (selected != null) SentriPanel(Modifier.fillMaxWidth(), background = colors.surface) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selected.starts.isEmpty()) stringResource(R.string.connected_upcoming_none)
                    else stringResource(R.string.connected_upcoming_selected, selected.starts.size),
                    Modifier.weight(1f), fontFamily = SentriType.sans, fontSize = 12.sp,
                    color = colors.ink)
                if (selected.starts.isNotEmpty()) SentriButton(
                    stringResource(R.string.connected_upcoming_view), onOpen, primary = false)
            }
        }
    }
}

@Composable private fun ConnectedSectionsPage(sections: List<HomePlaceSection>, selectedId: String?,
    taskCounts: Map<String, Int>?, summaries: HomePickerSummaryState?, showStock: Boolean,
    usesCache: Boolean, onBack: () -> Unit, onRetry: () -> Unit,
    onSelect: (HomePlaceSection) -> Unit) {
    val colors = SentriTheme.colors
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SentriIconButton("back", stringResource(R.string.home_back), onBack)
                Text(stringResource(R.string.home_your_sections), fontFamily = SentriType.sans,
                    fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            }
            Text(stringResource(R.string.home_select_section), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
        }
        if (usesCache) item { Text(stringResource(R.string.connected_offline_snapshot),
            fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted) }
        if (sections.isEmpty()) item { StatusMessage(stringResource(R.string.connected_no_sections)) }
        else item {
            SentriPanel(Modifier.fillMaxWidth()) {
                Column {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                        val count = taskCounts?.get(section.id)
                        val taskDetail = if (count == null)
                            stringResource(R.string.connected_picker_section_unknown, section.units.size)
                        else stringResource(R.string.connected_picker_section_count, section.units.size, count)
                        SentriRow(sectionName(section), if (showStock)
                            stringResource(R.string.connected_picker_section_detail, taskDetail,
                                pickerSectionStock(section, summaries)) else taskDetail,
                            null, onClick = { onSelect(section) }, selected = section.id == selectedId)
                    }
                }
            }
        }
        if (summaries?.units?.values?.any { it.stockFailed || it.checkInFailed } == true)
            item { SentriButton(stringResource(R.string.connected_unit_retry), onRetry,
                Modifier.fillMaxWidth(), primary = false) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable private fun ConnectedHeader(state: HomeLiveState, section: HomePlaceSection?, onSection: () -> Unit,
    onRefresh: () -> Unit, onSaved: () -> Unit) {
    val colors = SentriTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 12.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clickable(onClick = onSection)) {
            Text(state.account?.farm?.name.orEmpty(), fontFamily = SentriType.sans, fontSize = 10.sp,
                color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (section == null) stringResource(R.string.connected_choose_section) else sectionName(section),
                    fontFamily = SentriType.sans, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(5.dp)); SentriIcon("down", null, Modifier.size(16.dp), colors.muted)
            }
        }
        val refreshLabel = stringResource(R.string.connected_refresh)
        Surface(onClick = onRefresh, modifier = Modifier.semantics { contentDescription = refreshLabel },
            shape = RoundedCornerShape(12.dp), color = colors.paper,
            border = BorderStroke(1.dp, colors.line)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text("↻", fontFamily = SentriType.sans, fontSize = 24.sp, color = colors.ink)
            }
        }
        state.savedWork?.let { saved ->
            val badge = when {
                saved.attention > 0 -> SavedWorkBadgePreview(SavedWorkBadge.NeedsAttention, saved.attention)
                saved.uploading > 0 -> SavedWorkBadgePreview(SavedWorkBadge.Uploading, saved.uploading)
                saved.waiting > 0 -> SavedWorkBadgePreview(SavedWorkBadge.Waiting, saved.waiting)
                else -> null
            }
            if (badge != null) SavedWorkStatusBadge(badge, onSaved)
        }
    }
}

@Composable private fun ScopeSelection(unit: FarmUnit?, onOverview: () -> Unit, onUnits: () -> Unit) {
    val colors = SentriTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
        .background(colors.well, RoundedCornerShape(15.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ScopeChoice(stringResource(R.string.home_overview), unit == null, onOverview, Modifier.weight(0.4f))
        ScopeChoice(if (unit == null) stringResource(R.string.home_choose_unit) else unitName(unit), unit != null,
            onUnits, Modifier.weight(0.6f), trailing = true)
    }
}

@Composable private fun ScopeChoice(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier, trailing: Boolean = false) {
    val colors = SentriTheme.colors
    Row(modifier.height(48.dp).background(if (selected) colors.paper else Color.Transparent,
        RoundedCornerShape(11.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(label, Modifier.weight(1f, fill = false), fontFamily = SentriType.sans, fontSize = 13.sp,
            color = if (selected) colors.ink else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing) { Spacer(Modifier.width(6.dp)); SentriIcon("down", null, Modifier.size(15.dp), colors.muted) }
    }
}

@Composable private fun pickerSectionStock(section: HomePlaceSection,
    state: HomePickerSummaryState?): String {
    val entries = section.units.map { state?.units?.get(it.id) }
    return when {
        entries.any { it == null || it.stockLoading } ->
            stringResource(R.string.connected_unit_stock_loading)
        entries.any { it?.stock == null } ->
            stringResource(R.string.connected_unit_stock_unavailable)
        else -> stringResource(R.string.connected_picker_stock_count,
            entries.sumOf { requireNotNull(it?.stock).value.pigCount })
    }
}

@Composable private fun pickerUnitDetail(taskDetail: String, summary: HomePickerUnitSummary?): String {
    val stock = when {
        summary == null || summary.stockLoading -> stringResource(R.string.connected_unit_stock_loading)
        summary.stock != null -> stringResource(R.string.connected_picker_stock_count,
            summary.stock.value.pigCount)
        else -> stringResource(R.string.connected_unit_stock_unavailable)
    }
    val checkIn = when {
        summary == null || summary.checkInLoading -> stringResource(R.string.connected_unit_check_loading)
        summary.lastCheckIn == null -> stringResource(R.string.connected_unit_check_unavailable)
        summary.lastCheckIn.value == null -> stringResource(R.string.connected_unit_check_none)
        summary.lastCheckIn.value.createdAt == null -> stringResource(R.string.connected_unit_check_time_unknown)
        else -> stringResource(R.string.connected_unit_check_time,
            homeLocalTime(summary.lastCheckIn.value.createdAt))
    }
    val line = stringResource(R.string.connected_picker_unit_detail, taskDetail, stock, checkIn)
    return if (summary?.stock?.source == DataSource.CACHE || summary?.lastCheckIn?.source == DataSource.CACHE)
        stringResource(R.string.connected_picker_unit_cached, line) else line
}

@Composable private fun ConnectedHealthSummaryPending(modifier: Modifier) {
    val colors = SentriTheme.colors
    SentriPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(R.string.connected_to_treat, R.string.connected_monitoring).forEach { title ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), Modifier.weight(1f), fontFamily = SentriType.sans,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text(stringResource(R.string.connected_health_unavailable), fontFamily = SentriType.sans,
                        fontSize = 11.sp, color = colors.muted)
                }
            }
        }
    }
}

@Composable private fun ConnectedWorkEntry(work: HomeUnitWorkItem, section: HomePlaceSection,
    unit: FarmUnit?, places: HomePlaces?, heatFacts: Map<String, HomeHeatCardFact>, grouped: Boolean,
    onProduction: (String) -> Unit, onVaccination: (String) -> Unit,
    onTransfer: (String) -> Unit) {
    if (unit == null) {
        val row: @Composable () -> Unit = { ConnectedOverviewWorkRow(work, section, places,
            onProduction, onVaccination, onTransfer) }
        if (grouped) row() else SentriPanel(Modifier.fillMaxWidth().padding(horizontal = 18.dp), content = row)
    } else when (work) {
        is HomeUnitWorkItem.Production -> if (grouped)
            ConnectedTaskRow(work.scoped, unit, { onProduction(work.scoped.task.id) },
                heatFacts[work.scoped.task.id])
        else ConnectedTaskCard(work.scoped, unit, { onProduction(work.scoped.task.id) },
            Modifier.padding(horizontal = 18.dp), heatFacts[work.scoped.task.id])
        is HomeUnitWorkItem.Vaccination -> if (grouped)
            ConnectedVaccinationRow(work.scoped, unit, { onVaccination(work.scoped.task.id) })
        else ConnectedVaccinationCard(work.scoped, unit, { onVaccination(work.scoped.task.id) },
            Modifier.padding(horizontal = 18.dp))
        is HomeUnitWorkItem.Transfer -> if (grouped)
            ConnectedTransferContent(work.scoped, unit, places, { onTransfer(work.scoped.task.id) })
        else ConnectedTransferRow(work.scoped, unit, places, { onTransfer(work.scoped.task.id) },
            Modifier.padding(horizontal = 18.dp))
    }
}

@Composable private fun ConnectedWorkGroup(group: HomeWorkGroup, section: HomePlaceSection,
    unit: FarmUnit?, places: HomePlaces?, heatFacts: Map<String, HomeHeatCardFact>, open: Boolean, onToggle: () -> Unit,
    onProduction: (String) -> Unit, onVaccination: (String) -> Unit,
    onTransfer: (String) -> Unit) {
    val colors = SentriTheme.colors
    val categoryName = stringResource(when (group.category) {
        HomeWorkCategory.PRODUCTION -> R.string.wb_production
        HomeWorkCategory.VACCINATION -> R.string.connected_vaccination_group
        HomeWorkCategory.TRANSFER -> R.string.connected_transfer_title
    })
    val categoryIcon = when (group.category) {
        HomeWorkCategory.PRODUCTION -> "production"
        HomeWorkCategory.VACCINATION -> "health"
        HomeWorkCategory.TRANSFER -> "transfer"
    }
    SentriPanel(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column {
            Row(Modifier.fillMaxWidth()
                .background(if (open) colors.well else colors.paper)
                .drawBehind {
                    if (open) drawLine(colors.controlBorder, Offset(1.5.dp.toPx(), 0f),
                        Offset(1.5.dp.toPx(), size.height), 3.dp.toPx())
                }
                .clickable(onClick = onToggle)
                .defaultMinSize(minHeight = 70.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = colors.rowIconBackground) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        SentriIcon(categoryIcon, null, Modifier.size(18.dp), colors.rowIconInk)
                    }
                }
                Text(categoryName, Modifier.weight(1f), fontFamily = SentriType.sans,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Column(horizontalAlignment = Alignment.End) {
                    Text(group.items.size.toString(), fontFamily = SentriType.sans,
                        fontSize = 17.sp, color = colors.ink)
                    Text(stringResource(R.string.connected_group_tasks), fontFamily = SentriType.sans,
                        fontSize = 10.sp, color = colors.muted)
                }
                SentriIcon("down", null, Modifier.size(15.dp).rotate(if (open) 180f else 0f), colors.muted)
            }
            if (open) Column(Modifier.fillMaxWidth().drawBehind {
                val guideX = 27.dp.toPx()
                drawLine(colors.line, Offset(guideX, 0f), Offset(guideX, size.height), 1.dp.toPx())
            }.padding(start = 42.dp, end = 13.dp)) {
                group.items.forEachIndexed { index, work ->
                    ConnectedWorkEntry(work, section, unit, places, heatFacts, grouped = true,
                        onProduction, onVaccination, onTransfer)
                    if (index != group.items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(colors.rule))
                }
            }
        }
    }
}

@Composable private fun ConnectedOverviewWorkRow(work: HomeUnitWorkItem, section: HomePlaceSection,
    places: HomePlaces?, onProduction: (String) -> Unit, onVaccination: (String) -> Unit,
    onTransfer: (String) -> Unit) {
    val units = section.units.map(FarmUnit::id).toSet()
    val title: String
    val fact: String
    val open: () -> Unit
    when (work) {
        is HomeUnitWorkItem.Production -> {
            title = stringResource(R.string.connected_overview_production_instance,
                taskKind(work.scoped.task.kind), work.scoped.task.batch.serial)
            fact = stringResource(R.string.connected_overview_targets,
                work.scoped.pigUnitIds.values.count { it in units })
            open = { onProduction(work.scoped.task.id) }
        }
        is HomeUnitWorkItem.Vaccination -> {
            title = stringResource(R.string.connected_overview_vaccination_instance,
                vaccinationName(work.scoped.task), work.scoped.task.planId ?: work.scoped.task.id)
            fact = stringResource(R.string.connected_vaccination_target_count,
                work.scoped.pigUnitIds.values.count { it in units })
            open = { onVaccination(work.scoped.task.id) }
        }
        is HomeUnitWorkItem.Transfer -> {
            title = stringResource(R.string.connected_overview_transfer_instance,
                work.scoped.task.batchSerial ?: work.scoped.task.id)
            fact = transferRoute(work.scoped, places)
            open = { onTransfer(work.scoped.task.id) }
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = open)
        .defaultMinSize(minHeight = 75.dp)
        .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontFamily = SentriType.sans, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fact, fontFamily = SentriType.sans, fontSize = 12.sp,
                color = SentriTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SentriIcon("chevron", null, Modifier.size(16.dp), SentriTheme.colors.muted)
    }
}

@Composable private fun ConnectedTransferRow(task: HomeScopedTransfer, unit: FarmUnit?,
    places: HomePlaces?, onClick: () -> Unit, modifier: Modifier) {
    SentriPanel(modifier.fillMaxWidth()) {
        ConnectedTransferContent(task, unit, places, onClick)
    }
}

@Composable private fun ConnectedTransferContent(task: HomeScopedTransfer, unit: FarmUnit?,
    places: HomePlaces?, onClick: () -> Unit) {
    val direction = when (unit?.let { task.direction(it.id) }) {
        HomeTransferDirection.OUT -> stringResource(R.string.connected_transfer_out)
        HomeTransferDirection.IN -> stringResource(R.string.connected_transfer_in)
        HomeTransferDirection.BOTH -> stringResource(R.string.connected_transfer_both)
        null -> null
    }
    val route = transferRoute(task, places)
    val detail = if (direction == null) route else stringResource(R.string.connected_transfer_direction_route,
        direction, route)
    SentriRow(stringResource(R.string.connected_transfer_title), detail, "transfer", onClick,
        trailing = stringResource(if (task.task.status == "IN_PROGRESS")
            R.string.connected_running else R.string.connected_scheduled))
}

@Composable internal fun transferRoute(task: HomeScopedTransfer, places: HomePlaces?): String {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val units = places?.sections?.flatMap(HomePlaceSection::units).orEmpty().associateBy(FarmUnit::id)
    fun names(ids: Set<String>): String = ids.mapNotNull(units::get)
        .joinToString("、") { if (chinese) it.nameCn else it.nameEn }
    val fromNames = names(task.fromUnitIds)
    val toNames = names(task.toUnitIds)
    val from = if (fromNames.isBlank()) stringResource(R.string.connected_transfer_origin_pending) else fromNames
    val to = if (toNames.isBlank()) stringResource(R.string.connected_transfer_destination_pending) else toNames
    return stringResource(R.string.connected_transfer_route, from, to)
}

private fun overviewProductionRank(kind: ProductionTaskKind): Int = when (kind) {
    ProductionTaskKind.ESTRUS -> 0
    ProductionTaskKind.MATING -> 1
    ProductionTaskKind.RE_ESTRUS -> 2
    ProductionTaskKind.PREGNANCY -> 3
    ProductionTaskKind.FARROWING -> 4
    ProductionTaskKind.PIGLET_PROCESSING -> 5
    ProductionTaskKind.LACTATION -> 6
    ProductionTaskKind.POSTPARTUM -> 7
    ProductionTaskKind.NURSERY -> 8
    ProductionTaskKind.FATTENING -> 9
}

@Composable private fun ConnectedTaskCard(task: HomeScopedTask, unit: FarmUnit?, onClick: () -> Unit,
    modifier: Modifier, heatFact: HomeHeatCardFact?) {
    SentriPanel(modifier.fillMaxWidth()) { ConnectedTaskRow(task, unit, onClick, heatFact) }
}

@Composable private fun ConnectedVaccinationCard(task: HomeScopedVaccination, unit: FarmUnit?,
    onClick: () -> Unit, modifier: Modifier) {
    SentriPanel(modifier.fillMaxWidth()) { ConnectedVaccinationRow(task, unit, onClick) }
}

@Composable private fun ConnectedVaccinationRow(task: HomeScopedVaccination, unit: FarmUnit?, onClick: () -> Unit) {
    val colors = SentriTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vaccinationName(task.task), fontFamily = SentriType.sans, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(stringResource(R.string.connected_vaccination_target_count, task.targetCount(unit?.id)),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
            if (unit != null) Text(stringResource(if (task.task.status == "IN_PROGRESS")
                R.string.connected_running else R.string.connected_scheduled),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.green)
        }
        SentriIcon("chevron", null, Modifier.size(17.dp), colors.muted)
    }
}

@Composable private fun vaccinationName(task: VaccinationTask): String {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val value = task.vaccine?.let { if (chinese) it.chinese else it.english }
    return value?.takeIf(String::isNotBlank) ?: stringResource(R.string.connected_vaccination_name_unknown)
}

@Composable private fun ConnectedTaskRow(task: HomeScopedTask, unit: FarmUnit?, onClick: () -> Unit,
    heatFact: HomeHeatCardFact? = null) {
    val colors = SentriTheme.colors
    val pigCount = if (unit == null) task.pigUnitIds.size else task.pigUnitIds.values.count { it == unit.id }
    val signs = if (task.task.kind == ProductionTaskKind.ESTRUS && unit != null)
        heatFact?.signs(unit.id) ?: 0 else 0
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(taskKind(task.task.kind), fontFamily = SentriType.sans, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(if (signs > 0) stringResource(R.string.connected_batch, task.task.batch.serial)
                else stringResource(R.string.connected_task_summary, task.task.batch.serial, pigCount),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (signs > 0) Text(stringResource(if (heatFact?.source == DataSource.CACHE)
                R.string.connected_heat_signs_cached else R.string.connected_heat_signs, signs),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.ink)
            if (unit != null) Text(stringResource(if (task.task.status == ProductionTaskStatus.RUNNING)
                R.string.connected_running else R.string.connected_scheduled),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.green)
        }
        SentriIcon("chevron", null, Modifier.size(17.dp), colors.muted)
    }
}

@Composable private fun StatusMessage(message: String, modifier: Modifier = Modifier) {
    SentriPanel(modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(16.dp), fontFamily = SentriType.sans, fontSize = 12.sp,
            color = SentriTheme.colors.muted)
    }
}

@Composable private fun sectionName(section: HomePlaceSection): String =
    if (LocalConfiguration.current.locales[0].language == "zh") section.nameCn else section.nameEn

@Composable internal fun unitName(unit: FarmUnit): String =
    if (LocalConfiguration.current.locales[0].language == "zh") unit.nameCn else unit.nameEn

@Composable private fun taskKind(kind: ProductionTaskKind): String = stringResource(when (kind) {
    ProductionTaskKind.ESTRUS -> R.string.home_task_heat
    ProductionTaskKind.MATING -> R.string.home_breeding
    ProductionTaskKind.RE_ESTRUS -> R.string.wb_return_heat
    ProductionTaskKind.PREGNANCY -> R.string.home_task_pregnancy
    ProductionTaskKind.FARROWING -> R.string.home_task_farrowing
    ProductionTaskKind.PIGLET_PROCESSING -> R.string.home_task_piglet
    ProductionTaskKind.POSTPARTUM -> R.string.connected_postpartum
    ProductionTaskKind.LACTATION -> R.string.connected_lactation
    ProductionTaskKind.NURSERY -> R.string.connected_nursery
    ProductionTaskKind.FATTENING -> R.string.connected_fattening
})
