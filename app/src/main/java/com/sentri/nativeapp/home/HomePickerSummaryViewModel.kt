package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.inspection.UnitCheckInHistoryReads
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomePickerUnitSummary(
    val stock: CachedResult<HomeUnitStock>? = null,
    val lastCheckIn: CachedResult<UnitCheckInSummary?>? = null,
    val stockLoading: Boolean = true,
    val checkInLoading: Boolean = true,
    val stockFailed: Boolean = false,
    val checkInFailed: Boolean = false,
)

data class HomePickerSummaryState(
    val lease: ScopeLease? = null,
    val units: Map<String, HomePickerUnitSummary> = emptyMap(),
)

/** Reads selector-only facts on demand. Four units at a time bound request pressure. */
class HomePickerSummaryViewModel(
    private val stock: HomeUnitStockReads,
    private val checkIns: UnitCheckInHistoryReads,
    private val check: (ScopeLease) -> Unit,
) : ViewModel() {
    private data class RequestKey(val lease: ScopeLease, val units: List<Pair<String, String>>,
        val placesFetchedAt: Long)

    private val mutable = MutableStateFlow(HomePickerSummaryState())
    val state = mutable.asStateFlow()
    private var key: RequestKey? = null
    private var job: Job? = null
    private var generation = 0L
    private var currentUnits: List<FarmUnit> = emptyList()

    fun open(lease: ScopeLease, units: List<FarmUnit>, placesFetchedAt: Long) {
        try { check(lease) } catch (_: RequestContextChanged) { clear(); return }
        require(units.all { it.farmId == lease.scope.farmId && it.id.isNotBlank() &&
            it.locationCode.isNotBlank() })
        require(units.map(FarmUnit::id).distinct().size == units.size)
        val requested = RequestKey(lease, units.map { it.id to it.locationCode }, placesFetchedAt)
        if (requested == key) return
        job?.cancel()
        key = requested
        currentUnits = units.toList()
        val run = ++generation
        mutable.value = HomePickerSummaryState(lease,
            units.associate { it.id to HomePickerUnitSummary() })
        job = viewModelScope.launch {
            val permits = Semaphore(4)
            units.map { unit ->
                launch {
                    permits.withPermit {
                        try {
                            val result = stock.current(lease, unit.id)
                            check(lease)
                            update(run, requested, unit.id) { it.copy(stock = result,
                                stockLoading = false) }
                        } catch (_: RequestContextChanged) {
                            clearIfCurrent(run, requested)
                            return@withPermit
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            update(run, requested, unit.id) { it.copy(stockLoading = false,
                                stockFailed = true) }
                        }
                        try {
                            val result = checkIns.latest(lease, unit.locationCode)
                            check(lease)
                            update(run, requested, unit.id) { it.copy(lastCheckIn = result,
                                checkInLoading = false) }
                        } catch (_: RequestContextChanged) {
                            clearIfCurrent(run, requested)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            update(run, requested, unit.id) { it.copy(checkInLoading = false,
                                checkInFailed = true) }
                        }
                    }
                }
            }.joinAll()
        }
    }

    fun retry() {
        val previous = key ?: return
        val pending = currentUnits
        key = null
        open(previous.lease, pending, previous.placesFetchedAt)
    }

    /** Preserve the outgoing page's facts while ensuring the next visit reads again. */
    fun close() {
        generation++
        key = null
        currentUnits = emptyList()
        job?.cancel()
        job = null
    }

    fun clear() {
        close()
        mutable.value = HomePickerSummaryState()
    }

    private fun update(run: Long, requested: RequestKey, unitId: String,
        transform: (HomePickerUnitSummary) -> HomePickerUnitSummary) {
        if (run != generation || key != requested || mutable.value.lease != requested.lease) return
        val current = mutable.value.units[unitId] ?: return
        mutable.value = mutable.value.copy(units = mutable.value.units + (unitId to transform(current)))
    }

    private fun clearIfCurrent(run: Long, requested: RequestKey) {
        if (run == generation && key == requested) clear()
    }
}
