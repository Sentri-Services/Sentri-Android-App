package com.sentri.nativeapp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.production.EstrusStatus
import com.sentri.nativeapp.production.EstrusTask
import com.sentri.nativeapp.production.ProductionTaskKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A recorded sign is a task result, never a derived count of work due today. */
data class HomeHeatCardFact(val signsByUnit: Map<String, Int>, val source: DataSource) {
    fun signs(unitId: String) = signsByUnit[unitId] ?: 0
}

data class HomeTaskCardFactsState(val lease: ScopeLease? = null, val unitId: String? = null,
    val heat: Map<String, HomeHeatCardFact> = emptyMap())

/** Fetch only task types with a verified card fact, and reject details that disagree with Home. */
class HomeTaskCardFactsViewModel(
    private val readHeat: suspend (ScopeLease, String) -> CachedResult<EstrusTask?>,
    private val check: (ScopeLease) -> Unit,
) : ViewModel() {
    private data class Key(val lease: ScopeLease, val unitId: String, val taskIds: List<String>,
        val catalogFetchedAt: Long, val placesFetchedAt: Long)

    private val mutable = MutableStateFlow(HomeTaskCardFactsState())
    val state = mutable.asStateFlow()
    private var key: Key? = null
    private var generation = 0L
    private var job: Job? = null

    fun open(lease: ScopeLease, unitId: String, tasks: List<HomeScopedTask>, places: HomePlaces,
        catalogFetchedAt: Long, placesFetchedAt: Long) {
        try { check(lease) } catch (_: RequestContextChanged) { clear(); return }
        require(unitId.isNotBlank() && tasks.all {
            it.task.kind == ProductionTaskKind.ESTRUS && it.task.farmId == lease.scope.farmId &&
                unitId in it.unitIds
        })
        require(tasks.map { it.task.id }.distinct().size == tasks.size)
        val requested = Key(lease, unitId, tasks.map { it.task.id }, catalogFetchedAt, placesFetchedAt)
        if (requested == key) return
        job?.cancel()
        key = requested
        val run = ++generation
        mutable.value = HomeTaskCardFactsState(lease, unitId)
        job = viewModelScope.launch {
            val permits = Semaphore(4)
            tasks.map { task -> launch {
                permits.withPermit {
                    try {
                        val result = readHeat(lease, task.task.id)
                        check(lease)
                        val detail = result.value ?: return@withPermit
                        if (detail.status != task.task.status.name) return@withPermit
                        val scoped = consistentEstrusDetail(task, detail, places,
                            System.currentTimeMillis()) ?: return@withPermit
                        val signs = detail.pigs.asSequence().filter {
                            !it.disabled && it.checkStatus == EstrusStatus.SIGNS_OF_ESTRUS
                        }.groupingBy { scoped.pigUnitIds.getValue(it.id) }.eachCount()
                        if (run == generation && key == requested && mutable.value.lease == lease)
                            mutable.value = mutable.value.copy(heat = mutable.value.heat +
                                (task.task.id to HomeHeatCardFact(signs, result.source)))
                    } catch (_: RequestContextChanged) {
                        if (run == generation && key == requested) clear()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The catalog card stays available without an unverified fact. */ }
                }
            } }.joinAll()
        }
    }

    fun clear() {
        generation++
        key = null
        job?.cancel()
        job = null
        mutable.value = HomeTaskCardFactsState()
    }
}
