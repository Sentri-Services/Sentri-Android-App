package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.offline.CommandQueueStore
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SamplingWorkspace(val detail: CachedResult<SamplingTask?>, val projection: SamplingProjection?)

class SamplingWorkspaceReads(private val reads: SamplingReads,
    private val queue: CommandQueueStore, private val check: (ScopeLease) -> Unit) {
    suspend fun task(lease: ScopeLease, id: String, cacheOnly: Boolean = false,
        reflectedCommandIds: Set<String> = emptySet()): SamplingWorkspace = withContext(Dispatchers.IO) {
        val detail = reads.detail(lease, id, cacheOnly); check(lease)
        val saved = detail.value ?: return@withContext SamplingWorkspace(detail, null)
        val commands = queue.list(lease.scope); check(lease)
        SamplingWorkspace(detail, SamplingProjector.project(lease.scope, saved, commands, reflectedCommandIds))
    }
}
