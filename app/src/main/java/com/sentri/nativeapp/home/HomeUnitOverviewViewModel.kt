package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.inspection.UnitCheckInHistoryReads
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUnitOverviewState(
    val lease: ScopeLease? = null,
    val unitId: String? = null,
    val stock: CachedResult<HomeUnitStock>? = null,
    val penRoster: CachedResult<HomeUnitPenRoster>? = null,
    val maintenance: CachedResult<HomeUnitMaintenance>? = null,
    val lastCheckIn: CachedResult<UnitCheckInSummary?>? = null,
    val stockLoading: Boolean = false,
    val checkInLoading: Boolean = false,
    val stockFailed: Boolean = false,
    val penLoading: Boolean = false,
    val penFailed: Boolean = false,
    val checkInFailed: Boolean = false,
    val maintenanceLoading: Boolean = false,
    val maintenanceFailed: Boolean = false,
)

class HomeUnitOverviewViewModel(private val stockReads: HomeUnitStockReads,
    private val penReads: HomeUnitPenRosterReads,
    private val history: UnitCheckInHistoryReads, private val maintenanceReads: HomeUnitMaintenanceReads,
    private val gate: ScopeGate) : ViewModel() {
    private val mutable = MutableStateFlow(HomeUnitOverviewState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var penJob: Job? = null
    private var unitCode: String? = null

    fun open(lease: ScopeLease, unit: FarmUnit) {
        try { gate.check(lease) } catch (_: RequestContextChanged) { clear(); return }
        require(unit.farmId == lease.scope.farmId && unit.id.isNotBlank() && unit.locationCode.isNotBlank())
        if (state.value.lease == lease && state.value.unitId == unit.id && unitCode == unit.locationCode) return
        job?.cancel()
        penJob?.cancel()
        unitCode = unit.locationCode
        mutable.value = HomeUnitOverviewState(lease, unit.id, stockLoading = true,
            checkInLoading = true, maintenanceLoading = true)
        job = viewModelScope.launch {
            launch {
                try {
                    val result = stockReads.current(lease, unit.id)
                    gate.check(lease)
                    update(lease, unit.id) { it.copy(stock = result, stockLoading = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RequestContextChanged) { clear() }
                catch (_: Exception) { update(lease, unit.id) { it.copy(stockLoading = false, stockFailed = true) } }
            }
            launch {
                try {
                    val result = history.latest(lease, unit.locationCode)
                    gate.check(lease)
                    update(lease, unit.id) { it.copy(lastCheckIn = result, checkInLoading = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RequestContextChanged) { clear() }
                catch (_: Exception) { update(lease, unit.id) { it.copy(checkInLoading = false, checkInFailed = true) } }
            }
            launch { loadMaintenance(lease, unit.id) }
        }
    }

    fun refresh() {
        val lease = state.value.lease ?: return
        val id = state.value.unitId ?: return
        val code = unitCode ?: return
        job?.cancel()
        penJob?.cancel()
        mutable.value = HomeUnitOverviewState(lease, id, stockLoading = true,
            checkInLoading = true, maintenanceLoading = true)
        // The validated farm/unit identity is already held by the current state.
        job = viewModelScope.launch {
            launch {
                try { val result = stockReads.current(lease, id); gate.check(lease)
                    update(lease, id) { it.copy(stock = result, stockLoading = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RequestContextChanged) { clear() }
                catch (_: Exception) { update(lease, id) { it.copy(stockLoading = false, stockFailed = true) } }
            }
            launch {
                try { val result = history.latest(lease, code); gate.check(lease)
                    update(lease, id) { it.copy(lastCheckIn = result, checkInLoading = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RequestContextChanged) { clear() }
                catch (_: Exception) { update(lease, id) { it.copy(checkInLoading = false, checkInFailed = true) } }
            }
            launch { loadMaintenance(lease, id) }
        }
    }

    private suspend fun loadMaintenance(lease: ScopeLease, unitId: String) {
        try {
            val result = maintenanceReads.current(lease, unitId)
            gate.check(lease)
            update(lease, unitId) { it.copy(maintenance = result, maintenanceLoading = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RequestContextChanged) { clear() }
        catch (_: Exception) { update(lease, unitId) { it.copy(maintenanceLoading = false, maintenanceFailed = true) } }
    }

    fun openStock() {
        val lease = state.value.lease ?: return
        val id = state.value.unitId ?: return
        penJob?.cancel()
        mutable.value = state.value.copy(penRoster = null, penLoading = true, penFailed = false)
        penJob = viewModelScope.launch {
            try {
                val result = penReads.current(lease, id)
                gate.check(lease)
                update(lease, id) { it.copy(penRoster = result, penLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) { clear() }
            catch (_: Exception) { update(lease, id) { it.copy(penLoading = false, penFailed = true) } }
        }
    }

    fun clear() {
        job?.cancel()
        penJob?.cancel()
        unitCode = null
        mutable.value = HomeUnitOverviewState()
    }

    private fun update(lease: ScopeLease, unitId: String,
        transform: (HomeUnitOverviewState) -> HomeUnitOverviewState) {
        if (mutable.value.lease == lease && mutable.value.unitId == unitId)
            mutable.value = transform(mutable.value)
    }
}
