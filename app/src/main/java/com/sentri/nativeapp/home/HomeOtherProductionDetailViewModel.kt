package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val OTHER_PRODUCTION_KINDS = setOf(
    ProductionTaskKind.FARROWING, ProductionTaskKind.PIGLET_PROCESSING,
    ProductionTaskKind.POSTPARTUM, ProductionTaskKind.LACTATION,
    ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING,
)

data class HomeOtherProductionDetailState(
    val lease: ScopeLease? = null,
    val taskId: String? = null,
    val kind: ProductionTaskKind? = null,
    val result: CachedResult<out ProductionTaskSnapshot?>? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Typed detail reads remain separate from the Home catalog and retain their own offline snapshots. */
class HomeOtherProductionDetailViewModel(
    private val read: suspend (ScopeLease, ProductionTaskKind, String) -> CachedResult<out ProductionTaskSnapshot?>,
    private val gate: ScopeGate,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeOtherProductionDetailState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun open(lease: ScopeLease, kind: ProductionTaskKind, taskId: String) {
        require(kind in OTHER_PRODUCTION_KINDS && taskId.isNotBlank())
        try { gate.check(lease) } catch (_: RequestContextChanged) { clear(); return }
        job?.cancel()
        mutable.value = HomeOtherProductionDetailState(lease, taskId, kind, loading = true)
        job = viewModelScope.launch {
            try {
                val result = read(lease, kind, taskId)
                gate.check(lease)
                if (mutable.value.lease == lease && mutable.value.taskId == taskId && mutable.value.kind == kind)
                    mutable.value = mutable.value.copy(result = result, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) { clear() }
            catch (_: Exception) {
                if (mutable.value.lease == lease && mutable.value.taskId == taskId && mutable.value.kind == kind)
                    mutable.value = mutable.value.copy(loading = false, failed = true)
            }
        }
    }

    fun refresh() {
        val current = mutable.value
        if (current.lease != null && current.kind != null && current.taskId != null)
            open(current.lease, current.kind, current.taskId)
    }

    fun clear() {
        job?.cancel()
        mutable.value = HomeOtherProductionDetailState()
    }
}
