package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.offline.CommandQueueStore
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TherapyWorkspace(val context: CachedResult<TherapyPrescriptionContext?>,
    val pages: List<CachedResult<TherapyPage>>, val projection: TherapyProjection?)

/** Loads complete prescription membership before replaying the durable queue. */
class TherapyWorkspaceReads(private val reads: TherapyReads, private val queue: CommandQueueStore,
    private val check: (ScopeLease) -> Unit) {
    suspend fun prescription(lease: ScopeLease, id: String, cacheOnly: Boolean = false,
        reflectedCommandIds: Set<String> = emptySet()): TherapyWorkspace = withContext(Dispatchers.IO) {
        require(id.isNotBlank()); check(lease)
        val context = reads.context(lease, id, cacheOnly); check(lease)
        val membership = context.value ?: return@withContext TherapyWorkspace(context, emptyList(), null)
        val pages = mutableListOf<CachedResult<TherapyPage>>()
        val rows = mutableListOf<TherapyRow>()
        var offset = 0
        var more: Boolean
        do {
            val request = TherapyPageRequest(prescriptionId = id, order = TherapyReadOrder.ID_ASC, offset = offset, limit = 200)
            val page = reads.page(lease, request, cacheOnly); check(lease)
            pages += page; rows += page.value.rows
            if (rows.size > membership.therapyStatuses.size || page.value.hasMore && page.value.rows.isEmpty()) throw ApiFailure.Protocol()
            offset = Math.addExact(offset, page.value.rows.size)
            more = page.value.hasMore
        } while (more)
        if (rows.map { it.id }.toSet() != membership.therapyStatuses.keys || rows.size != membership.therapyStatuses.size)
            throw ApiFailure.Protocol()
        check(lease)
        val commands = queue.list(lease.scope); check(lease)
        val projection = try { TherapyProjector.project(lease.scope, membership, rows, commands, reflectedCommandIds) }
        catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        TherapyWorkspace(context, pages.toList(), projection)
    }
}
