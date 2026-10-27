package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.offline.CommandQueueStore
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VaccinationWorkspace(val detail: CachedResult<VaccinationTask?>,
    val projection: VaccinationProjection?)

class VaccinationWorkspaceReads(private val reads: VaccinationReads,
    private val queue: CommandQueueStore, private val check: (ScopeLease) -> Unit) {
    suspend fun task(lease: ScopeLease, id: String, cacheOnly: Boolean = false,
        reflectedCommandIds: Set<String> = emptySet()): VaccinationWorkspace = withContext(Dispatchers.IO) {
        val detail = reads.detail(lease, id, cacheOnly); check(lease)
        val saved = detail.value ?: return@withContext VaccinationWorkspace(detail, null)
        val commands = queue.list(lease.scope); check(lease)
        VaccinationWorkspace(detail, VaccinationProjector.project(lease.scope, saved, commands, reflectedCommandIds))
    }
}
