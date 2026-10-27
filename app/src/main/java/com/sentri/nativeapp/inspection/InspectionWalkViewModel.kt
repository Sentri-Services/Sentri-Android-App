package com.sentri.nativeapp.inspection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.PigFilter
import com.sentri.nativeapp.pigs.PigLifecycle
import com.sentri.nativeapp.pigs.PigProfileOrder
import com.sentri.nativeapp.pigs.PigProfile
import com.sentri.nativeapp.pigs.PigProfilePage
import com.sentri.nativeapp.pigs.PigProfileReads
import com.sentri.nativeapp.pigs.PigProfileRequest
import com.sentri.nativeapp.pigs.UnitPigProfileRoster
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeUnitPenRosterReads
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.production.ProductionBatch
import com.sentri.nativeapp.offline.CommandStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class InspectionLens { ALL, HEALTH }
internal fun hasRecordedCondition(pig: PigProfile): Boolean =
    pig.health?.let { it.diseases.isNotEmpty() || it.symptoms.isNotEmpty() } == true

internal fun matchesWalkFilters(pig: PigProfile, stage: String?, condition: String?, batch: String? = null): Boolean {
    if (stage != null && pig.breederProductionStatus != stage) return false
    if (batch != null && (pig.currentBatchId != batch || pig.batch?.id != batch ||
            pig.batch?.farmId != pig.farmId)) return false
    if (condition == null) return true
    return when {
        condition.startsWith("d:") -> pig.health?.diseases.orEmpty().any { "d:${it.id}" == condition }
        condition.startsWith("s:") -> pig.health?.symptoms.orEmpty().any { "s:${it.id}" == condition }
        else -> false
    }
}

internal fun visibleWalkPigs(state: InspectionWalkState): List<PigProfile>? {
    val complete = state.lens == InspectionLens.HEALTH || state.stageFilter != null ||
        state.conditionFilter != null || state.batchFilter != null
    val pigs = if (complete) state.completeRoster?.value?.pigs else state.page?.value?.pigs
    return pigs?.filter { pig ->
        matchesWalkFilters(pig, state.stageFilter, state.conditionFilter, state.batchFilter) &&
            (state.lens != InspectionLens.HEALTH || hasRecordedCondition(pig))
    }
}

data class InspectionWalkState(
    val lease: ScopeLease? = null,
    val unit: FarmUnit? = null,
    val page: CachedResult<PigProfilePage>? = null,
    val lens: InspectionLens = InspectionLens.ALL,
    val stageFilter: String? = null,
    val conditionFilter: String? = null,
    val batchFilter: String? = null,
    val completeRoster: CachedResult<UnitPigProfileRoster>? = null,
    val completeRosterLoading: Boolean = false,
    val completeRosterFailed: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val checkInStatus: CommandStatus? = null,
    val localCheckIn: LocalCheckInReceipt? = null,
    val earlierCheckInsWaiting: Int = 0,
    val earlierCheckInsNeedReview: Int = 0,
    val outstandingCheckIns: List<LocalCheckInReceipt> = emptyList(),
    val latestCheckIn: CachedResult<UnitCheckInSummary?>? = null,
    val latestCheckInLoading: Boolean = false,
    val latestCheckInFailed: Boolean = false,
    val recentCheckIns: CachedResult<RecentUnitCheckInsPage>? = null,
    val recentCheckInsLoading: Boolean = false,
    val recentCheckInsFailed: Boolean = false,
    val checkInDetailId: String? = null,
    val checkInDetail: CachedResult<UnitInspectionRecord?>? = null,
    val checkInDetailLoading: Boolean = false,
    val checkInDetailFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val searchQuery: String = "",
    val searchPage: CachedResult<PigProfilePage>? = null,
    val searchLoading: Boolean = false,
    val searchFailed: Boolean = false,
    val pigDetailId: String? = null,
    val pigDetail: CachedResult<PigProfile?>? = null,
    val pigDetailLoading: Boolean = false,
    val pigDetailFailed: Boolean = false,
    val catalog: CachedResult<HealthReferenceCatalog>? = null,
    val catalogLoading: Boolean = false,
    val catalogFailed: Boolean = false,
    val penRoster: CachedResult<HomeUnitPenRoster>? = null,
    val penRosterLoading: Boolean = false,
    val penRosterFailed: Boolean = false,
    val penCode: String? = null,
    val penPage: CachedResult<PigProfilePage>? = null,
    val penLoading: Boolean = false,
    val penFailed: Boolean = false,
    val penDevices: CachedResult<List<DeviceInspection>>? = null,
    val penDevicesLoading: Boolean = false,
    val penDevicesFailed: Boolean = false,
    val faultDrafts: Map<String, DeviceFaultDraft> = emptyMap(),
    val faultDraftsLoading: Boolean = false,
    val faultDraftsLoadFailed: Boolean = false,
    val faultDraftSavedPens: Set<String> = emptySet(),
    val faultDraftSavingPens: Set<String> = emptySet(),
    val faultDraftSaveFailedPens: Set<String> = emptySet(),
    val penNoteDrafts: Map<String, PenNoteEditor> = emptyMap(),
    val penInventory: CachedResult<LocationInventorySnapshot>? = null,
    val penInventoryLoading: Boolean = false,
    val penInventoryFailed: Boolean = false,
    val penBatches: Map<String, CachedResult<ProductionBatch?>> = emptyMap(),
    val penBatchesLoading: Boolean = false,
    val penBatchesFailed: Boolean = false,
    val selectedPigs: Map<String, PigProfile> = emptyMap(),
    val treatmentValidationLoading: Boolean = false,
    val treatmentValidation: TreatmentSelectionReview? = null,
    val treatmentValidationProblem: TreatmentSelectionProblem? = null,
    val scannedPigs: List<ScannedPig> = emptyList(),
    val scanSerial: Long = 0,
    val scanPending: Int = 0,
    val scanProblem: ScanProblem? = null,
)

enum class TreatmentSelectionProblem { OFFLINE, FAILED }
enum class TreatmentExclusionReason { MISSING, MOVED, INACTIVE }
data class TreatmentSelectionExclusion(val pig: PigProfile, val reason: TreatmentExclusionReason)
data class TreatmentSelectionReview(val eligible: List<PigProfile>,
    val excluded: List<TreatmentSelectionExclusion>)

/** A missing server row is different from a pig that moved or left the active herd. */
internal fun reviewTreatmentSelection(selected: List<PigProfile>, latest: Map<String, PigProfile>,
    unit: FarmUnit): TreatmentSelectionReview {
    val eligible = mutableListOf<PigProfile>()
    val excluded = mutableListOf<TreatmentSelectionExclusion>()
    selected.forEach { snapshot ->
        val current = latest[snapshot.id]
        when {
            current == null -> excluded += TreatmentSelectionExclusion(snapshot, TreatmentExclusionReason.MISSING)
            current.lifecycle != "ACTIVE" -> excluded += TreatmentSelectionExclusion(snapshot, TreatmentExclusionReason.INACTIVE)
            current.farmId != unit.farmId || current.location?.unitId != unit.id ->
                excluded += TreatmentSelectionExclusion(snapshot, TreatmentExclusionReason.MOVED)
            else -> eligible += current
        }
    }
    return TreatmentSelectionReview(eligible, excluded)
}

/** One complete server page at a time: offline pages from different moments are never merged into a roster count. */
class InspectionWalkViewModel(private val profiles: PigProfileReads, private val gate: ScopeGate,
    private val checkIn: InspectionCheckInService? = null,
    private val onQueueChanged: () -> Unit = {},
    private val references: HealthReferenceReads? = null,
    private val history: UnitCheckInHistoryReads? = null,
    private val devices: DeviceInspectionReads? = null,
    private val recentCheckIns: RecentUnitCheckInsReads? = null,
    private val penRosters: HomeUnitPenRosterReads? = null,
    private val unitInspections: UnitInspectionReads? = null,
    private val locationInventory: LocationInventoryReads? = null,
    private val faultDraftStore: DeviceFaultDraftStore? = null,
    private val penNoteDraftStore: PenNoteDraftStore? = null,
    private val readBatch: (suspend (ScopeLease, String) -> CachedResult<ProductionBatch?>)? = null) : ViewModel() {
    private val mutable = MutableStateFlow(InspectionWalkState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var searchJob: Job? = null
    private var pigJob: Job? = null
    private var treatmentValidationJob: Job? = null
    private var catalogJob: Job? = null
    private var penJob: Job? = null
    private var historyJob: Job? = null
    private var deviceJob: Job? = null
    private var recentJob: Job? = null
    private var penRosterJob: Job? = null
    private var penInventoryJob: Job? = null
    private var detailJob: Job? = null
    private var checkInOverviewJob: Job? = null
    private var completeRosterJob: Job? = null
    private var faultDraftLoadJob: Job? = null
    private var faultDraftSession = Any()
    private var penNoteSession = Any()
    private val penNoteLoads = mutableMapOf<String, Job>()
    private var scanSupervisor = SupervisorJob(viewModelScope.coroutineContext[Job])

    fun open(lease: ScopeLease, unit: FarmUnit) {
        try { gate.check(lease) } catch (_: RequestContextChanged) { clear(); return }
        if (state.value.lease == lease && state.value.unit?.id == unit.id) return
        job?.cancel()
        searchJob?.cancel()
        pigJob?.cancel()
        treatmentValidationJob?.cancel()
        catalogJob?.cancel()
        penJob?.cancel()
        historyJob?.cancel()
        deviceJob?.cancel()
        recentJob?.cancel()
        penRosterJob?.cancel()
        penInventoryJob?.cancel()
        detailJob?.cancel()
        checkInOverviewJob?.cancel()
        completeRosterJob?.cancel()
        scanSupervisor.cancel()
        scanSupervisor = SupervisorJob(viewModelScope.coroutineContext[Job])
        faultDraftSession = Any()
        penNoteSession = Any()
        penNoteLoads.values.forEach { it.cancel() }
        penNoteLoads.clear()
        mutable.value = InspectionWalkState(lease, unit)
        loadFaultDrafts()
        load(0)
        loadLatestCheckIn()
        refreshCheckIn()
    }

    fun clear() {
        penNoteSession = Any()
        penNoteLoads.values.forEach { it.cancel() }
        penNoteLoads.clear()
        faultDraftSession = Any()
        faultDraftLoadJob?.cancel()
        job?.cancel()
        searchJob?.cancel()
        pigJob?.cancel()
        treatmentValidationJob?.cancel()
        catalogJob?.cancel()
        penJob?.cancel()
        historyJob?.cancel()
        deviceJob?.cancel()
        recentJob?.cancel()
        penRosterJob?.cancel()
        penInventoryJob?.cancel()
        detailJob?.cancel()
        checkInOverviewJob?.cancel()
        completeRosterJob?.cancel()
        scanSupervisor.cancel()
        scanSupervisor = SupervisorJob(viewModelScope.coroutineContext[Job])
        mutable.value = InspectionWalkState()
    }

    fun refresh() {
        load(state.value.page?.value?.request?.offset ?: 0)
        if (state.value.lens == InspectionLens.HEALTH || state.value.stageFilter != null ||
            state.value.conditionFilter != null || state.value.batchFilter != null) loadCompleteRoster()
        if (state.value.penRoster != null || state.value.penRosterLoading || state.value.penRosterFailed)
            loadPenRoster()
        loadLatestCheckIn()
    }

    fun selectLens(lens: InspectionLens) {
        if (state.value.lens == lens) return
        mutable.value = state.value.copy(lens = lens)
        if (lens == InspectionLens.HEALTH) loadCompleteRoster()
    }

    fun retryHealthLens() = loadCompleteRoster()

    fun prepareFilters() {
        if (state.value.completeRoster == null && !state.value.completeRosterLoading) loadCompleteRoster()
    }

    fun applyFilters(stage: String?, condition: String?, batch: String? = null) {
        if ((stage != null || condition != null || batch != null) && state.value.completeRoster == null) return
        mutable.value = state.value.copy(stageFilter = stage, conditionFilter = condition, batchFilter = batch)
    }

    private fun loadCompleteRoster() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        completeRosterJob?.cancel()
        mutable.value = state.value.copy(completeRoster = null,
            completeRosterLoading = true, completeRosterFailed = false)
        completeRosterJob = viewModelScope.launch {
            try {
                val result = profiles.completeUnitRoster(lease, unit.id)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(completeRoster = result,
                        completeRosterLoading = false, completeRosterFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(completeRosterLoading = false,
                        completeRosterFailed = true)
            }
        }
    }

    fun openPenMap() = loadPenRoster()

    private fun loadPenRoster() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val reader = penRosters ?: return
        penRosterJob?.cancel()
        mutable.value = state.value.copy(penRosterLoading = true, penRosterFailed = false)
        penRosterJob = viewModelScope.launch {
            try {
                val result = reader.current(lease, unit.id)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id) {
                    mutable.value = state.value.copy(penRoster = result, penRosterLoading = false)
                    if (state.value.penCode in result.value.registeredPens.orEmpty().map { it.code })
                        loadPenInventory()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(penRosterLoading = false, penRosterFailed = true)
            }
        }
    }

    fun openCheckInHistory() {
        refreshCheckIn()
        loadRecentCheckIns(0)
    }
    fun openCheckInDetail(id: String) {
        if (state.value.recentCheckIns?.value?.entries?.none { it.id == id } != false) return
        loadCheckInDetail(id)
    }
    fun refreshCheckInDetail() { state.value.checkInDetailId?.let(::loadCheckInDetail) }
    fun closeCheckInDetail() {
        detailJob?.cancel()
        mutable.value = state.value.copy(checkInDetailId = null, checkInDetail = null,
            checkInDetailLoading = false, checkInDetailFailed = false)
    }
    private fun loadCheckInDetail(id: String) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val reader = unitInspections ?: return
        detailJob?.cancel()
        mutable.value = state.value.copy(checkInDetailId = id, checkInDetail = null,
            checkInDetailLoading = true, checkInDetailFailed = false)
        detailJob = viewModelScope.launch {
            try {
                val result = reader.byId(lease, unit.locationCode, id)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.checkInDetailId == id)
                    mutable.value = state.value.copy(checkInDetail = result,
                        checkInDetailLoading = false, checkInDetailFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.checkInDetailId == id)
                    mutable.value = state.value.copy(checkInDetailLoading = false, checkInDetailFailed = true)
            }
        }
    }
    fun refreshCheckInHistory() {
        refreshCheckIn()
        loadRecentCheckIns(state.value.recentCheckIns?.value?.offset ?: 0)
    }
    fun nextCheckInHistory() {
        val page = state.value.recentCheckIns?.value ?: return
        if (page.hasMore) loadRecentCheckIns(page.offset + RecentUnitCheckInsReads.PAGE_SIZE)
    }
    fun previousCheckInHistory() {
        val page = state.value.recentCheckIns?.value ?: return
        if (page.offset > 0) loadRecentCheckIns(page.offset - RecentUnitCheckInsReads.PAGE_SIZE)
    }

    private fun loadRecentCheckIns(offset: Int) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val reader = recentCheckIns ?: return
        recentJob?.cancel()
        mutable.value = state.value.copy(recentCheckInsLoading = true, recentCheckInsFailed = false)
        recentJob = viewModelScope.launch {
            try {
                val result = reader.page(lease, unit.locationCode, offset)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(recentCheckIns = result,
                        recentCheckInsLoading = false, recentCheckInsFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(recentCheckInsLoading = false, recentCheckInsFailed = true)
            }
        }
    }

    private fun loadLatestCheckIn() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val reader = history ?: return
        historyJob?.cancel()
        mutable.value = state.value.copy(latestCheckInLoading = true, latestCheckInFailed = false)
        historyJob = viewModelScope.launch {
            try {
                val result = reader.latest(lease, unit.locationCode)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(latestCheckIn = result,
                        latestCheckInLoading = false, latestCheckInFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(latestCheckInLoading = false, latestCheckInFailed = true)
            }
        }
    }

    fun openPigDetail(pigId: String) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val healthVisible = visibleWalkPigs(state.value).orEmpty().any { it.id == pigId }
        if (state.value.page?.value?.pigs.orEmpty().none { it.id == pigId } &&
            state.value.searchPage?.value?.pigs.orEmpty().none { it.id == pigId } &&
            state.value.penPage?.value?.pigs.orEmpty().none { it.id == pigId } &&
            state.value.scannedPigs.none { it.pig.id == pigId } && !healthVisible) return
        pigJob?.cancel()
        mutable.value = state.value.copy(pigDetailId = pigId, pigDetail = null,
            pigDetailLoading = true, pigDetailFailed = false)
        pigJob = viewModelScope.launch {
            try {
                val result = profiles.detail(lease, pigId)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.pigDetailId == pigId)
                    mutable.value = state.value.copy(pigDetail = result, pigDetailLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.pigDetailId == pigId)
                    mutable.value = state.value.copy(pigDetailLoading = false, pigDetailFailed = true)
            }
        }
    }

    fun openCatalog() {
        val lease = state.value.lease ?: return
        val reader = references ?: return
        catalogJob?.cancel()
        mutable.value = state.value.copy(catalogLoading = true, catalogFailed = false)
        catalogJob = viewModelScope.launch {
            try {
                val result = reader.catalog(lease)
                gate.check(lease)
                if (state.value.lease == lease)
                    mutable.value = state.value.copy(catalog = result, catalogLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease)
                    mutable.value = state.value.copy(catalog = null, catalogLoading = false, catalogFailed = true)
            }
        }
    }

    fun updateFaultDraft(lease: ScopeLease, penCode: String, draft: DeviceFaultDraft) {
        val current = state.value
        if (current.lease != lease || current.unit?.farmId != lease.scope.farmId ||
            current.penCode != penCode || penCode.isBlank() || current.faultDraftsLoading ||
            current.faultDraftsLoadFailed || penCode in current.faultDraftSavingPens) return
        try { gate.check(lease) } catch (_: RequestContextChanged) { return }
        require(draft.photoUris.size <= 12 && draft.photoUris.distinct().size == draft.photoUris.size)
        require(draft.type == null || draft.type in setOf("Feed", "Water", "Other"))
        mutable.value = current.copy(faultDrafts = if (draft.empty) current.faultDrafts - penCode
            else current.faultDrafts + (penCode to draft),
            faultDraftSavedPens = current.faultDraftSavedPens - penCode,
            faultDraftSaveFailedPens = current.faultDraftSaveFailedPens - penCode)
        if (draft.empty) saveFaultDraft(lease, penCode)
    }

    fun loadFaultDrafts() {
        val store = faultDraftStore ?: return
        val session = faultDraftSession
        val current = state.value
        val lease = current.lease ?: return
        val unit = current.unit ?: return
        if (unit.farmId != lease.scope.farmId) return
        faultDraftLoadJob?.cancel()
        mutable.value = current.copy(faultDraftsLoading = true, faultDraftsLoadFailed = false)
        faultDraftLoadJob = viewModelScope.launch {
            try {
                gate.check(lease)
                val drafts = store.load(lease.scope, unit.id)
                gate.check(lease)
                if (faultDraftSession === session && state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(faultDrafts = drafts.drafts,
                        faultDraftSavedPens = drafts.drafts.keys - drafts.unavailablePhotoPens,
                        faultDraftSaveFailedPens = drafts.unavailablePhotoPens, faultDraftsLoading = false, faultDraftsLoadFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (faultDraftSession === session && state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(faultDraftsLoading = false, faultDraftsLoadFailed = true)
            }
        }
    }

    fun saveFaultDraft(lease: ScopeLease, penCode: String) {
        val store = faultDraftStore ?: return
        val session = faultDraftSession
        val current = state.value
        val unit = current.unit ?: return
        if (current.lease != lease || unit.farmId != lease.scope.farmId || current.penCode != penCode ||
            current.faultDraftsLoading || current.faultDraftsLoadFailed || penCode in current.faultDraftSavingPens) return
        try { gate.check(lease) } catch (_: RequestContextChanged) { return }
        val draft = current.faultDrafts[penCode] ?: DeviceFaultDraft()
        mutable.value = current.copy(faultDraftSavingPens = current.faultDraftSavingPens + penCode,
            faultDraftSaveFailedPens = current.faultDraftSaveFailedPens - penCode)
        viewModelScope.launch {
            try {
                gate.check(lease)
                val saved = store.save(lease.scope, unit.id, penCode, draft)
                gate.check(lease)
                if (faultDraftSession === session && state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(
                        faultDrafts = if (saved.empty) state.value.faultDrafts - penCode
                            else state.value.faultDrafts + (penCode to saved),
                        faultDraftSavingPens = state.value.faultDraftSavingPens - penCode,
                        faultDraftSavedPens = if (saved.empty) state.value.faultDraftSavedPens - penCode
                            else state.value.faultDraftSavedPens + penCode)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (faultDraftSession === session && state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(faultDraftSavingPens = state.value.faultDraftSavingPens - penCode,
                        faultDraftSaveFailedPens = state.value.faultDraftSaveFailedPens + penCode)
            }
        }
    }

    fun openPenNoteDraft(lease: ScopeLease, penCode: String) {
        val store = penNoteDraftStore ?: return
        val unit = checkedPen(lease, penCode) ?: return
        val editor = state.value.penNoteDrafts[penCode] ?: PenNoteEditor()
        if (editor.loaded || editor.loading || editor.saving) return
        val session = penNoteSession
        setPenNoteEditor(penCode, editor.copy(loading = true, loadFailed = false))
        penNoteLoads[penCode]?.cancel()
        penNoteLoads[penCode] = viewModelScope.launch {
            try {
                val draft = store.read(lease, unit.id, penCode)
                gate.check(lease)
                if (session === penNoteSession && state.value.lease == lease && state.value.unit?.id == unit.id)
                    setPenNoteEditor(penCode, PenNoteEditor(draft?.text.orEmpty(), draft, loaded = true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session === penNoteSession && state.value.lease == lease && state.value.unit?.id == unit.id)
                    setPenNoteEditor(penCode, editor.copy(loadFailed = true))
            }
        }
    }

    fun updatePenNoteDraft(lease: ScopeLease, penCode: String, text: String) {
        checkedPen(lease, penCode) ?: return
        val editor = state.value.penNoteDrafts[penCode] ?: return
        if (!editor.loaded || editor.loading || editor.saving) return
        setPenNoteEditor(penCode, editor.copy(text = text, saveFailed = false))
    }

    fun savePenNoteDraft(lease: ScopeLease, penCode: String) = writePenNoteDraft(lease, penCode, clear = false)
    fun clearPenNoteDraft(lease: ScopeLease, penCode: String) = writePenNoteDraft(lease, penCode, clear = true)

    private fun writePenNoteDraft(lease: ScopeLease, penCode: String, clear: Boolean) {
        val store = penNoteDraftStore ?: return
        val unit = checkedPen(lease, penCode) ?: return
        val editor = state.value.penNoteDrafts[penCode] ?: return
        if (editor.loading || editor.saving || (!clear && (!editor.loaded || editor.text.isBlank()))) return
        val session = penNoteSession
        setPenNoteEditor(penCode, editor.copy(saving = true, saveFailed = false))
        viewModelScope.launch {
            try {
                val draft = store.write(lease, unit.id, penCode, if (clear) null else editor.text)
                gate.check(lease)
                if (session === penNoteSession && state.value.lease == lease && state.value.unit?.id == unit.id)
                    setPenNoteEditor(penCode, PenNoteEditor(draft?.text.orEmpty(), draft, loaded = true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session === penNoteSession && state.value.lease == lease && state.value.unit?.id == unit.id)
                    setPenNoteEditor(penCode, editor.copy(saveFailed = true))
            }
        }
    }

    private fun checkedPen(lease: ScopeLease, penCode: String): FarmUnit? {
        val current = state.value
        if (current.lease != lease || current.unit?.farmId != lease.scope.farmId ||
            current.penCode != penCode || penCode.isBlank()) return null
        return try { gate.check(lease); current.unit } catch (_: RequestContextChanged) { null }
    }

    private fun setPenNoteEditor(penCode: String, editor: PenNoteEditor) {
        mutable.value = state.value.copy(penNoteDrafts = state.value.penNoteDrafts + (penCode to editor))
    }

    fun openPen(penCode: String) {
        val healthVisible = visibleWalkPigs(state.value).orEmpty().any {
            it.location?.penLocation == penCode && it.location.unitId == state.value.unit?.id
        }
        if (penCode.isBlank() || state.value.page?.value?.pigs.orEmpty().none {
            it.location?.penLocation == penCode && it.location.unitId == state.value.unit?.id
        } && state.value.penRoster?.value?.pens.orEmpty().none { it.code == penCode } &&
            state.value.penRoster?.value?.registeredPens.orEmpty().none { it.code == penCode } &&
            !healthVisible) return
        showPen(penCode)
    }

    /** The Home stock reader validates the complete roster and its registered pen codes. */
    fun openPenFromStock(lease: ScopeLease, penCode: String,
        roster: com.sentri.nativeapp.home.HomeUnitPenRoster): Boolean {
        val current = state.value
        if (current.lease != lease || current.unit?.farmId != lease.scope.farmId) return false
        try { gate.check(lease) } catch (_: RequestContextChanged) { return false }
        if (penCode.isBlank() || current.unit?.id != roster.unitId ||
            roster.registeredPens?.any { it.code == penCode } != true) return false
        showPen(penCode)
        return true
    }

    /** Home maintenance already verified this open fault and its registered pen for the exact unit. */
    fun openFaultFromMaintenance(issue: DeviceInspection,
        maintenance: com.sentri.nativeapp.home.HomeUnitMaintenance): Boolean {
        val current = state.value
        if (issue.id.isBlank() || issue.penCode.isBlank() || issue.status != "Fault" ||
            current.lease == null || current.unit?.id != maintenance.unitId ||
            issue.farmId != current.lease.scope.farmId || issue.penCode !in maintenance.penCodes ||
            maintenance.records.none { it.id == issue.id && it == issue } || maintenance.records.any {
                it.farmId != current.lease.scope.farmId
            }) return false
        showPen(issue.penCode)
        return true
    }

    private fun showPen(penCode: String) {
        penJob?.cancel()
        penInventoryJob?.cancel()
        mutable.value = state.value.copy(penCode = penCode, penPage = null,
            penLoading = false, penFailed = false, penDevices = null,
            penDevicesLoading = false, penDevicesFailed = false,
            penInventory = null, penInventoryLoading = false, penInventoryFailed = false,
            penBatches = emptyMap(), penBatchesLoading = false, penBatchesFailed = false)
        loadPen(0)
        loadPenDevices()
        if (state.value.penRoster?.value?.registeredPens?.any { it.code == penCode } == true)
            loadPenInventory()
        else loadPenRoster()
    }

    fun closePen() {
        penJob?.cancel()
        deviceJob?.cancel()
        penInventoryJob?.cancel()
        mutable.value = state.value.copy(penCode = null, penPage = null,
            penLoading = false, penFailed = false, penDevices = null,
            penDevicesLoading = false, penDevicesFailed = false,
            penInventory = null, penInventoryLoading = false, penInventoryFailed = false,
            penBatches = emptyMap(), penBatchesLoading = false, penBatchesFailed = false)
    }

    fun refreshPenInventory() = loadPenInventory()

    private fun loadPenInventory() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val penCode = state.value.penCode ?: return
        val reader = locationInventory ?: return
        if (state.value.penRoster?.value?.registeredPens?.none { it.code == penCode } != false) return
        penInventoryJob?.cancel()
        mutable.value = state.value.copy(penInventoryLoading = true, penInventoryFailed = false,
            penBatches = emptyMap(), penBatchesLoading = false, penBatchesFailed = false)
        penInventoryJob = viewModelScope.launch {
            try {
                val result = reader.current(lease, unit.id, penCode)
                currentCoroutineContext().ensureActive()
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id &&
                    state.value.penCode == penCode &&
                    state.value.penRoster?.value?.registeredPens?.any { it.code == penCode } == true) {
                    mutable.value = state.value.copy(penInventory = result,
                        penInventoryLoading = false, penInventoryFailed = false)
                    val batchReader = readBatch ?: return@launch
                    val ids = result.value.nonIdentityByBatch.keys.filterNotNull().sorted()
                    mutable.value = state.value.copy(penBatchesLoading = ids.isNotEmpty())
                    val batches = mutableMapOf<String, CachedResult<ProductionBatch?>>()
                    var failed = false
                    for (id in ids) {
                        try {
                            val batch = batchReader(lease, id)
                            currentCoroutineContext().ensureActive()
                            gate.check(lease)
                            batch.value?.let {
                                require(it.id == id && it.farmId == lease.scope.farmId && it.serial.isNotBlank())
                            }
                            batches[id] = batch
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (changed: RequestContextChanged) { throw changed }
                        catch (_: Exception) { failed = true }
                    }
                    gate.check(lease)
                    if (state.value.lease == lease && state.value.unit?.id == unit.id &&
                        state.value.penCode == penCode)
                        mutable.value = state.value.copy(penBatches = batches,
                            penBatchesLoading = false, penBatchesFailed = failed)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.penCode == penCode)
                    mutable.value = state.value.copy(penInventoryLoading = false, penInventoryFailed = true)
            }
        }
    }

    /** Selection is transient and unit-scoped; it records intent, never an inspection result. */
    fun togglePigSelection(pigId: String) {
        val current = state.value
        val next = InspectionSelection.togglePig(current.selectedPigs,
            current.penPage?.value?.pigs.orEmpty() + current.page?.value?.pigs.orEmpty() +
                current.completeRoster?.value?.pigs.orEmpty() +
                current.searchPage?.value?.pigs.orEmpty() + current.scannedPigs.map(ScannedPig::pig), pigId,
            current.unit?.id ?: return, current.lease?.scope?.farmId ?: return)
        treatmentValidationJob?.cancel()
        mutable.value = current.copy(selectedPigs = next, treatmentValidationLoading = false,
            treatmentValidation = null, treatmentValidationProblem = null)
    }

    fun toggleVisiblePenSelection(penCode: String) {
        val current = state.value
        treatmentValidationJob?.cancel()
        mutable.value = current.copy(selectedPigs = InspectionSelection.togglePen(current.selectedPigs,
            visibleWalkPigs(current).orEmpty(), penCode,
            current.unit?.id ?: return, current.lease?.scope?.farmId ?: return),
            treatmentValidationLoading = false, treatmentValidation = null,
            treatmentValidationProblem = null)
    }

    fun clearSelection() {
        treatmentValidationJob?.cancel()
        mutable.value = state.value.copy(selectedPigs = emptyMap(), treatmentValidationLoading = false,
            treatmentValidation = null, treatmentValidationProblem = null)
    }

    /** Read every selected identity from the server before offering a multi-pig treatment preview. */
    fun validateTreatmentSelection() {
        val current = state.value
        val lease = current.lease ?: return
        val unit = current.unit ?: return
        val selected = current.selectedPigs.values.toList().takeIf { it.isNotEmpty() } ?: return
        val ids = selected.map(PigProfile::id)
        treatmentValidationJob?.cancel()
        mutable.value = current.copy(treatmentValidationLoading = true, treatmentValidation = null,
            treatmentValidationProblem = null)
        treatmentValidationJob = viewModelScope.launch {
            try {
                val latest = mutableMapOf<String, PigProfile>()
                ids.chunked(20).forEach { batch ->
                    val request = PigProfileRequest(filter = PigFilter(pageSize = batch.size),
                        ids = batch.toSet(), includeCount = false)
                    val result = profiles.page(lease, request)
                    if (result.source != DataSource.NETWORK) {
                        if (state.value.lease == lease && state.value.unit?.id == unit.id &&
                            state.value.selectedPigs.keys.toList() == ids)
                            mutable.value = state.value.copy(treatmentValidationLoading = false,
                                treatmentValidationProblem = TreatmentSelectionProblem.OFFLINE)
                        return@launch
                    }
                    result.value.pigs.forEach { latest[it.id] = it }
                }
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id &&
                    state.value.selectedPigs.keys.toList() == ids)
                    mutable.value = state.value.copy(treatmentValidationLoading = false,
                        treatmentValidation = reviewTreatmentSelection(selected, latest, unit))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (failure: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id &&
                    state.value.selectedPigs.keys.toList() == ids)
                    mutable.value = state.value.copy(treatmentValidationLoading = false,
                        treatmentValidationProblem = if (failure is ApiFailure.Network)
                            TreatmentSelectionProblem.OFFLINE else TreatmentSelectionProblem.FAILED)
            }
        }
    }

    fun closeTreatmentValidation() {
        treatmentValidationJob?.cancel()
        mutable.value = state.value.copy(treatmentValidationLoading = false,
            treatmentValidation = null, treatmentValidationProblem = null)
    }

    /** Scan results remain in this walk across visits to the scanner page; each code resolves exactly in this farm. */
    fun recordScan(code: String) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        if (code.isBlank()) return
        val sequence = state.value.scanSerial + 1
        mutable.value = state.value.copy(scanSerial = sequence,
            scanPending = state.value.scanPending + 1, scanProblem = null)
        viewModelScope.launch(scanSupervisor) {
            try {
                val result = profiles.exactEarCode(lease, code)
                gate.check(lease)
                if (state.value.lease != lease || state.value.unit?.id != unit.id) return@launch
                when (result.value.size) {
                    1 -> {
                        val pig = result.value.single()
                        mutable.value = state.value.copy(scannedPigs = InspectionScanResults.add(
                            state.value.scannedPigs, pig, result.source, sequence),
                            scanProblem = if (state.value.scanSerial == sequence) null else state.value.scanProblem)
                    }
                    else -> if (state.value.scanSerial == sequence)
                        mutable.value = state.value.copy(scanProblem = if (result.value.isEmpty())
                            if (result.source == com.sentri.nativeapp.pigs.DataSource.CACHE)
                                ScanProblem.NO_CACHED_MATCH else ScanProblem.NO_MATCH
                            else ScanProblem.AMBIGUOUS)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) {
                if (state.value.lease == lease) clear()
            }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.scanSerial == sequence)
                    mutable.value = state.value.copy(scanProblem = ScanProblem.READ_FAILED)
            } finally {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(scanPending = (state.value.scanPending - 1).coerceAtLeast(0))
            }
        }
    }

    fun nextPenPage() {
        val page = state.value.penPage?.value ?: return
        if (!state.value.penLoading && page.total?.let { page.request.offset + page.pigs.size < it } == true)
            loadPen(page.request.offset + page.request.filter.pageSize)
    }

    fun previousPenPage() {
        val page = state.value.penPage?.value ?: return
        if (!state.value.penLoading && page.request.offset > 0)
            loadPen((page.request.offset - page.request.filter.pageSize).coerceAtLeast(0))
    }

    private fun loadPen(offset: Int) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val penCode = state.value.penCode ?: return
        penJob?.cancel()
        mutable.value = state.value.copy(penLoading = true, penFailed = false)
        penJob = viewModelScope.launch {
            try {
                val request = PigProfileRequest(PigFilter(penLocation = penCode, pageSize = 100),
                    lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY,
                    offset = offset, unitId = unit.id)
                val result = profiles.page(lease, request)
                gate.check(lease)
                require(result.value.pigs.isNotEmpty() || offset >= (result.value.total ?: 0)) {
                    "Incomplete pen roster page"
                }
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.penCode == penCode)
                    mutable.value = state.value.copy(penPage = result, penLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.penCode == penCode)
                    mutable.value = state.value.copy(penLoading = false, penFailed = true)
            }
        }
    }

    fun refreshPenDevices() = loadPenDevices()

    private fun loadPenDevices() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val penCode = state.value.penCode ?: return
        val reader = devices ?: return
        deviceJob?.cancel()
        mutable.value = state.value.copy(penDevicesLoading = true, penDevicesFailed = false)
        deviceJob = viewModelScope.launch {
            try {
                val result = reader.forPen(lease, penCode)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.penCode == penCode)
                    mutable.value = state.value.copy(penDevices = result, penDevicesLoading = false,
                        penDevicesFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.penCode == penCode)
                    mutable.value = state.value.copy(penDevicesLoading = false, penDevicesFailed = true)
            }
        }
    }

    fun findPig(query: String, offset: Int = 0) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val normalized = query.trim()
        searchJob?.cancel()
        if (normalized.isEmpty()) {
            mutable.value = state.value.copy(searchQuery = "", searchPage = null,
                searchLoading = false, searchFailed = false)
            return
        }
        mutable.value = state.value.copy(searchQuery = normalized, searchPage = null,
            searchLoading = true, searchFailed = false)
        searchJob = viewModelScope.launch {
            try {
                val request = PigProfileRequest(PigFilter(search = normalized, pageSize = 30),
                    lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY,
                    offset = offset)
                val result = profiles.page(lease, request)
                gate.check(lease)
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.searchQuery == normalized)
                    mutable.value = state.value.copy(searchPage = result, searchLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id && state.value.searchQuery == normalized)
                    mutable.value = state.value.copy(searchLoading = false, searchFailed = true)
            }
        }
    }

    fun nextSearchPage() {
        val page = state.value.searchPage?.value ?: return
        if (!state.value.searchLoading && page.total?.let { page.request.offset + page.pigs.size < it } == true) {
            findPig(state.value.searchQuery, page.request.offset + page.request.filter.pageSize)
        }
    }

    fun previousSearchPage() {
        val page = state.value.searchPage?.value ?: return
        if (!state.value.searchLoading && page.request.offset > 0) {
            findPig(state.value.searchQuery,
                (page.request.offset - page.request.filter.pageSize).coerceAtLeast(0))
        }
    }

    fun refreshCheckIn() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val service = checkIn ?: return
        checkInOverviewJob?.cancel()
        checkInOverviewJob = viewModelScope.launch {
            try {
                val overview = service.localOverview(lease, unit)
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(checkInStatus = overview.latest?.status,
                        localCheckIn = overview.latest,
                        earlierCheckInsWaiting = overview.earlierWaitingCount,
                        earlierCheckInsNeedReview = overview.earlierNeedsReviewCount,
                        outstandingCheckIns = overview.outstanding)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Status read cannot replace the roster. */ }
        }
    }

    fun saveCheckIn() {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        val service = checkIn ?: return
        if (state.value.saving) return
        checkInOverviewJob?.cancel()
        mutable.value = state.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                val saved = service.save(lease, unit)
                val overview = try { service.localOverview(lease, unit) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                onQueueChanged()
                if (state.value.lease == lease && state.value.unit?.id == unit.id) {
                    val previous = state.value.localCheckIn?.status
                    mutable.value = state.value.copy(saving = false, checkInStatus = CommandStatus.QUEUED,
                        localCheckIn = LocalCheckInReceipt(saved.record.operationId,
                            saved.record.createdAtMillis, CommandStatus.QUEUED),
                        earlierCheckInsWaiting = overview?.earlierWaitingCount ?: state.value.earlierCheckInsWaiting +
                            if (previous == CommandStatus.QUEUED || previous == CommandStatus.IN_FLIGHT) 1 else 0,
                        earlierCheckInsNeedReview = overview?.earlierNeedsReviewCount ?: state.value.earlierCheckInsNeedReview +
                            if (previous == CommandStatus.UNCERTAIN || previous == CommandStatus.FAILED ||
                                previous == CommandStatus.CONFLICTED) 1 else 0,
                        outstandingCheckIns = overview?.outstanding ?: listOf(
                            LocalCheckInReceipt(saved.record.operationId, saved.record.createdAtMillis,
                                CommandStatus.QUEUED)) + state.value.outstandingCheckIns)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(saving = false, saveFailed = true)
                return@launch
            }
            try { service.sync(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The durable command remains queued or uncertain. */ }
            onQueueChanged()
            refreshCheckIn()
            loadLatestCheckIn()
        }
    }

    fun next() {
        val page = state.value.page?.value ?: return
        if (!state.value.loading && page.total?.let { page.request.offset + page.pigs.size < it } == true)
            load(page.request.offset + page.request.filter.pageSize)
    }

    fun previous() {
        val page = state.value.page?.value ?: return
        if (!state.value.loading && page.request.offset > 0)
            load((page.request.offset - page.request.filter.pageSize).coerceAtLeast(0))
    }

    private fun load(offset: Int) {
        val lease = state.value.lease ?: return
        val unit = state.value.unit ?: return
        job?.cancel()
        mutable.value = state.value.copy(loading = true, failed = false)
        job = viewModelScope.launch {
            try {
                val request = PigProfileRequest(PigFilter(pageSize = 100),
                    lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY,
                    offset = offset, unitId = unit.id)
                val result = profiles.page(lease, request)
                gate.check(lease)
                require(result.value.pigs.isNotEmpty() || offset >= (result.value.total ?: 0)) {
                    "Incomplete inspection roster page"
                }
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(page = result, loading = false, failed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (state.value.lease == lease && state.value.unit?.id == unit.id)
                    mutable.value = state.value.copy(loading = false, failed = true)
            }
        }
    }
}

internal object InspectionSelection {
    fun togglePig(selected: Map<String, PigProfile>, visible: List<PigProfile>, pigId: String,
        unitId: String, farmId: String): Map<String, PigProfile> {
        if (pigId in selected) return selected - pigId
        val pig = visible.firstOrNull { it.id == pigId } ?: return selected
        if (pig.location?.unitId != unitId || pig.farmId != farmId) return selected
        return selected + (pigId to pig)
    }

    fun togglePen(selected: Map<String, PigProfile>, visible: List<PigProfile>, penCode: String,
        unitId: String, farmId: String): Map<String, PigProfile> {
        if (penCode.isBlank()) return selected
        val pigs = visible.filter { it.location?.penLocation == penCode &&
            it.location.unitId == unitId && it.farmId == farmId }
        if (pigs.isEmpty()) return selected
        return if (pigs.all { it.id in selected }) selected - pigs.map { it.id }.toSet()
            else selected + pigs.associateBy { it.id }
    }
}
