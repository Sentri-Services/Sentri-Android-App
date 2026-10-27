package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.vaccination.VaccinationTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeVaccinationDetailState(val lease: ScopeLease? = null, val taskId: String? = null,
    val result: CachedResult<VaccinationTask?>? = null, val loading: Boolean = false,
    val failed: Boolean = false)

/** A detail read is independent of the Home catalog, so changes in targets can be detected. */
class HomeVaccinationDetailViewModel(
    private val read: suspend (ScopeLease, String) -> CachedResult<VaccinationTask?>,
    private val gate: ScopeGate,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeVaccinationDetailState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun open(lease: ScopeLease, taskId: String) {
        require(taskId.isNotBlank())
        try { gate.check(lease) } catch (_: RequestContextChanged) { clear(); return }
        job?.cancel()
        mutable.value = HomeVaccinationDetailState(lease, taskId, loading = true)
        job = viewModelScope.launch {
            try {
                val result = read(lease, taskId)
                gate.check(lease)
                if (mutable.value.lease == lease && mutable.value.taskId == taskId)
                    mutable.value = mutable.value.copy(result = result, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RequestContextChanged) { clear() }
            catch (_: Exception) {
                if (mutable.value.lease == lease && mutable.value.taskId == taskId)
                    mutable.value = mutable.value.copy(loading = false, failed = true)
            }
        }
    }

    fun refresh() {
        val current = mutable.value
        if (current.lease != null && current.taskId != null) open(current.lease, current.taskId)
    }

    fun clear() {
        job?.cancel()
        mutable.value = HomeVaccinationDetailState()
    }
}
