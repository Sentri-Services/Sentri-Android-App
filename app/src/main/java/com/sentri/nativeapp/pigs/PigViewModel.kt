package com.sentri.nativeapp.pigs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.auth.SessionExpired
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PigFailure { NETWORK, AUTHENTICATION, BUSINESS, PROTOCOL, CACHE_EMPTY, CACHE_INVALID, UNKNOWN }
data class PigUiState(
    val lease: ScopeLease? = null, val locations: CachedResult<Locations>? = null,
    val filter: PigFilter = PigFilter(), val page: CachedResult<PigPage>? = null,
    val detailId: String? = null, val detail: CachedResult<PigDetail?>? = null,
    val cacheOnly: Boolean = false, val loading: Boolean = false, val failure: PigFailure? = null,
    val requestedOffset: Int = 0,
)
class PigViewModel(private val reads: PigReads, private val gate: ScopeGate, auth: AuthRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PigUiState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var sequence = 0L
    init {
        viewModelScope.launch {
            auth.session.collect {
                state.value.lease?.let { lease ->
                    try { gate.check(lease) } catch (_: CancellationException) {
                        sequence++; active?.cancel(); mutable.value = PigUiState()
                    }
                }
            }
        }
    }
    fun open(account: AccountContext) {
        val lease = gate.lease(account)
        if (state.value.lease == lease) return
        sequence++; active?.cancel(); mutable.value = PigUiState(lease = lease)
        task { current ->
            val position = reads.restore(current)
            checkCurrent(current)
            if (position != null) mutable.update { it.copy(filter = position.filter, cacheOnly = position.cacheOnly) }
            loadLocations(current, position?.offset ?: 0)
            position?.detailId?.let { id ->
                mutable.update { it.copy(detailId = id) }
                val detail = reads.detail(current, id, state.value.cacheOnly); checkCurrent(current)
                mutable.update { it.copy(detail = detail) }
                remember(current)
            }
        }
    }
    private suspend fun checkCurrent(lease: ScopeLease) {
        currentCoroutineContext().ensureActive()
        gate.check(lease)
    }
    private fun task(block: suspend (ScopeLease) -> Unit) {
        val lease = state.value.lease ?: return
        active?.cancel(); val request = ++sequence
        mutable.update { it.copy(loading = true, failure = null) }
        active = viewModelScope.launch {
            try { checkCurrent(lease); block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == sequence) mutable.update { it.copy(failure = failure(error)) }
            } finally { if (request == sequence) mutable.update { it.copy(loading = false) } }
        }
    }
    fun refresh() {
        if (state.value.detailId != null) { loadDetail(state.value.detailId!!); return }
        task { loadLocations(it, 0) }
    }
    private suspend fun loadLocations(lease: ScopeLease, offset: Int) {
        val locations = reads.locations(lease, state.value.cacheOnly); checkCurrent(lease)
        val current = state.value.filter
        val code = current.unitCode.takeIf { value -> locations.value.units.any { it.locationCode == value } }
            ?: locations.value.units.firstOrNull()?.locationCode.orEmpty()
        val filter = current.copy(unitCode = code, penLocation = current.penLocation.takeIf { value -> locations.value.pens.any { pen -> pen.locationCode == value && locations.value.units.any { unit -> unit.id == pen.unitId && unit.locationCode == code } } })
        mutable.update { it.copy(locations = locations, filter = filter) }
        if (code.isNotEmpty()) fetchPage(lease, filter, if (filter == current) offset else 0)
        else mutable.update { it.copy(page = null) }
    }
    fun applyFilter(unitCode: String, penLocation: String?, search: String) {
        val lease = state.value.lease ?: return
        gate.check(lease)
        require(state.value.locations?.value?.units?.any { it.locationCode == unitCode } == true)
        val filter = state.value.filter.copy(unitCode = unitCode, penLocation = penLocation, search = search)
        if (penLocation != null) require(state.value.locations?.value?.pens?.any { it.locationCode == penLocation && state.value.locations?.value?.units?.any { unit -> unit.id == it.unitId && unit.locationCode == unitCode } == true } == true)
        mutable.update { it.copy(filter = filter, page = null, detail = null, detailId = null, requestedOffset = 0) }
        task { fetchPage(it, filter, 0) }
    }
    private suspend fun fetchPage(lease: ScopeLease, filter: PigFilter, offset: Int) {
        mutable.update { it.copy(requestedOffset = offset) }
        val page = reads.page(lease, filter, offset, state.value.cacheOnly); checkCurrent(lease)
        mutable.update { it.copy(page = page) }
        remember(lease)
    }
    fun next() { val page = state.value.page?.value ?: return; if (page.hasNext && !state.value.loading) task { fetchPage(it, state.value.filter, page.offset + page.limit) } }
    fun previous() { val page = state.value.page?.value ?: return; if (page.offset > 0 && !state.value.loading) task { fetchPage(it, state.value.filter, (page.offset - page.limit).coerceAtLeast(0)) } }
    fun retry() {
        if (state.value.locations == null) { refresh(); return }
        state.value.detailId?.let { loadDetail(it) } ?: task { fetchPage(it, state.value.filter, state.value.requestedOffset) }
    }
    fun cacheOnly(value: Boolean) { mutable.update { it.copy(cacheOnly = value) }; refresh() }
    fun loadDetail(id: String) {
        mutable.update { it.copy(detailId = id, detail = null) }
        task { lease -> val detail = reads.detail(lease, id, state.value.cacheOnly); checkCurrent(lease); mutable.update { it.copy(detail = detail) }; remember(lease) }
    }
    fun closeDetail() {
        sequence++; active?.cancel(); mutable.update { it.copy(detailId = null, detail = null, loading = false, failure = null) }
        task { remember(it) }
    }
    private suspend fun remember(lease: ScopeLease) {
        val current = state.value
        reads.remember(lease, BrowsePosition(current.filter, current.page?.value?.offset ?: 0, current.detailId, current.cacheOnly))
    }
    fun clearCache() { task { lease -> reads.clear(lease); mutable.update { it.copy(locations = null, page = null, detail = null, detailId = null) } } }
    private fun failure(error: Exception) = when (error) {
        is ApiFailure.Network, is AuthUnavailable -> PigFailure.NETWORK
        is SessionExpired, is ApiFailure.Authentication -> PigFailure.AUTHENTICATION
        is ApiFailure.Business -> PigFailure.BUSINESS
        is ApiFailure.Protocol -> PigFailure.PROTOCOL
        is CacheMiss -> PigFailure.CACHE_EMPTY
        is CacheInvalid -> PigFailure.CACHE_INVALID
        else -> PigFailure.UNKNOWN
    }
}
