package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.offline.CommandQueueStore
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.pigs.CacheInvalid
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.vaccination.VaccinationTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HomeReadIssue { NETWORK, AUTHENTICATION, PERMISSION, NO_SNAPSHOT, INVALID_DATA }

data class HomeSavedWork(val waiting: Int, val uploading: Int, val attention: Int) {
    val total: Int get() = waiting + uploading + attention
}

data class HomeLiveState(
    val account: AccountContext? = null,
    val lease: ScopeLease? = null,
    val places: CachedResult<HomePlaces>? = null,
    val production: CachedResult<List<TaskMetadata>>? = null,
    val vaccination: CachedResult<List<VaccinationTask>>? = null,
    val transfer: CachedResult<List<HomeTransferTask>>? = null,
    val placesLoading: Boolean = false,
    val productionLoading: Boolean = false,
    val vaccinationLoading: Boolean = false,
    val transferLoading: Boolean = false,
    val placesIssue: HomeReadIssue? = null,
    val productionIssue: HomeReadIssue? = null,
    val vaccinationIssue: HomeReadIssue? = null,
    val transferIssue: HomeReadIssue? = null,
    val savedWork: HomeSavedWork? = null,
)

class HomeLiveViewModel(
    private val places: HomePlaceReads,
    private val production: HomeProductionReads,
    private val gate: ScopeGate,
    private val queue: CommandQueueStore,
    private val vaccination: HomeVaccinationReads? = null,
    private val transfer: HomeTransferReads? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeLiveState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    fun open(account: AccountContext) {
        val lease = try { gate.lease(account) } catch (_: RequestContextChanged) { clear(); return }
        if (state.value.lease == lease) return
        loadJob?.cancel()
        mutableState.value = HomeLiveState(account = account, lease = lease)
        refresh()
    }

    fun clear() {
        loadJob?.cancel()
        mutableState.value = HomeLiveState()
    }

    fun refresh() {
        val lease = state.value.lease ?: return
        loadJob?.cancel()
        mutableState.value = state.value.copy(placesLoading = true, productionLoading = true,
            vaccinationLoading = vaccination != null, transferLoading = transfer != null,
            placesIssue = null, productionIssue = null, vaccinationIssue = null, transferIssue = null)
        loadJob = viewModelScope.launch {
            try {
                val result = places.load(lease)
                update(lease) { it.copy(places = result, placesIssue = null, placesLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                update(lease) { it.copy(placesIssue = issue(error), placesLoading = false) }
            }
            try {
                val result = production.load(lease)
                update(lease) { it.copy(production = result, productionIssue = null, productionLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                update(lease) { it.copy(productionIssue = issue(error), productionLoading = false) }
            }
            if (vaccination != null) try {
                val result = vaccination.load(lease)
                update(lease) { it.copy(vaccination = result, vaccinationIssue = null,
                    vaccinationLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val problem = issue(error)
                update(lease) { it.copy(vaccination = if (problem == HomeReadIssue.INVALID_DATA) null else it.vaccination,
                    vaccinationIssue = problem, vaccinationLoading = false) }
            }
            if (transfer != null) try {
                val result = transfer.load(lease)
                update(lease) { it.copy(transfer = result, transferIssue = null, transferLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                update(lease) { it.copy(transferIssue = issue(error), transferLoading = false) }
            }
            try {
                val saved = readSavedWork(lease)
                update(lease) { it.copy(savedWork = saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A badge read cannot replace Home data or imply an empty queue. */ }
        }
    }

    fun refreshSavedWork() {
        val lease = state.value.lease ?: return
        viewModelScope.launch {
            try { val saved = readSavedWork(lease); update(lease) { it.copy(savedWork = saved) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Preserve the last known status instead of showing a false zero. */ }
        }
    }

    private suspend fun readSavedWork(lease: ScopeLease): HomeSavedWork {
        val records = withContext(Dispatchers.IO) { gate.check(lease); queue.list(lease.scope) }
        return HomeSavedWork(
            records.count { it.record.status == CommandStatus.QUEUED },
            records.count { it.record.status == CommandStatus.IN_FLIGHT },
            records.count { it.record.status in setOf(CommandStatus.UNCERTAIN, CommandStatus.FAILED, CommandStatus.CONFLICTED) },
        )
    }

    private fun update(lease: ScopeLease, transform: (HomeLiveState) -> HomeLiveState) {
        if (mutableState.value.lease != lease) return
        gate.check(lease)
        mutableState.value = transform(mutableState.value)
    }

    private fun issue(error: Exception): HomeReadIssue = when (error) {
        is ApiFailure.Network, is AuthUnavailable -> HomeReadIssue.NETWORK
        is ApiFailure.Authentication -> HomeReadIssue.AUTHENTICATION
        is ApiFailure.Business -> HomeReadIssue.PERMISSION
        is CacheMiss -> HomeReadIssue.NO_SNAPSHOT
        is CacheInvalid, is ApiFailure.Protocol -> HomeReadIssue.INVALID_DATA
        else -> HomeReadIssue.INVALID_DATA
    }
}
