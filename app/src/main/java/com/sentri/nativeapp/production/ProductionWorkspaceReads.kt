package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.offline.CommandQueueStore
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias ProductionDetailRead = suspend (ScopeLease, String, Boolean) -> CachedResult<out ProductionTaskSnapshot?>
data class RelatedTaskUnavailable(val id: String, val failure: Exception)
data class RelatedPigUnavailable(val id: String, val failure: Exception)
data class ProductionWorkspace(val primaryTaskId: String, val metadata: CachedResult<TaskMetadata?>,
    val details: Map<String, CachedResult<out ProductionTaskSnapshot?>>, val projection: ProductionProjection,
    val unavailableRelatedTasks: List<RelatedTaskUnavailable>, val profiles: Map<String, CachedResult<PigProfile?>> = emptyMap(),
    val unavailableProfiles: List<RelatedPigUnavailable> = emptyList(),
    val relatedMetadata: Map<String, CachedResult<TaskMetadata>> = emptyMap(), val deathContext: PigDeathContext = PigDeathContext())

/** A view of independently timestamped reads and a current durable queue. Never writes a preview into snapshots. */
class ProductionWorkspaceReads(private val catalog: TaskCatalogReads, private val details: Map<ProductionTaskKind, ProductionDetailRead>,
    private val queue: CommandQueueStore, private val check: (ScopeLease) -> Unit, private val pigProfiles: PigProfileReads? = null) {
    suspend fun task(lease: ScopeLease, id: String, cacheOnly: Boolean = false, reflectedCommandIds: Set<String> = emptySet()): ProductionWorkspace = withContext(Dispatchers.IO) {
        check(lease)
        val common = catalog.metadata(lease, id, cacheOnly); check(lease)
        val metadata = common.value
        val snapshots = linkedMapOf<String, CachedResult<out ProductionTaskSnapshot?>>()
        val missing = mutableListOf<RelatedTaskUnavailable>()
        if (metadata != null) {
            val read = details[metadata.kind] ?: throw ApiFailure.Protocol()
            val primary = read(lease, id, cacheOnly); check(lease)
            val value = primary.value ?: throw ApiFailure.Protocol()
            if (value.id != id || value.farmId != lease.scope.farmId || value.batchId != metadata.batch.id || value.type != metadata.kind.name) throw ApiFailure.Protocol()
            snapshots[id] = primary
            // Batch membership is explicit. Fetch each typed sibling needed for cross-task local effects.
            for (related in metadata.batch.tasks.filter { it.id != id && it.status in setOf(ProductionTaskStatus.RUNNING, ProductionTaskStatus.SCHEDULED) }) {
                try {
                    val sibling = (details[related.kind] ?: throw ApiFailure.Protocol())(lease, related.id, cacheOnly); check(lease)
                    val task = sibling.value ?: throw ApiFailure.Protocol()
                    if (task.id != related.id || task.batchId != metadata.batch.id || task.farmId != lease.scope.farmId || task.type != related.kind.name) throw ApiFailure.Protocol()
                    snapshots[related.id] = sibling
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    check(lease)
                    // Missing/offline siblings are reported; authentication/business/protocol/context failures remain failures.
                    if (failure !is CacheMiss && failure !is ApiFailure.Network && failure !is com.sentri.nativeapp.auth.AuthUnavailable) throw failure
                    missing += RelatedTaskUnavailable(related.id, failure)
                }
            }
        }
        check(lease)
        val contextCommands = queue.list(lease.scope); check(lease)
        val relatedMetadata = linkedMapOf<String, CachedResult<TaskMetadata>>()
        metadata?.let { relatedMetadata[it.id] = CachedResult(it, common.source, common.fetchedAt, common.refreshFailure) }
        val taskIdsByPig = linkedMapOf<String, Set<String>>()
        val initialPigs = relatedPigIds(snapshots.values.mapNotNull { it.value }, metadata, contextCommands)
        val deathPigs = contextCommands.filter { it.record.status != CommandStatus.CANCELLED }.mapNotNull { it.input as? ReportPigDeathInput }
            .filter { it.taskId in snapshots.keys || it.targetPigIds.any(initialPigs::contains) }.flatMap { it.targetPigIds }.toSet()
        for (chunk in deathPigs.sorted().chunked(100)) {
            val entries = linkedMapOf<String, TaskMetadata>(); var offset = 0; var total: Int? = null; var complete = true
            try {
                do {
                    val page = catalog.page(lease, TaskCatalogRequest(statuses = setOf(ProductionTaskStatus.PLANNED, ProductionTaskStatus.SCHEDULED, ProductionTaskStatus.RUNNING),
                        offset = offset, limit = 200, pigIds = chunk.toSet()), cacheOnly); check(lease)
                    if (total != null && total != page.value.total) complete = false
                    if (total == null) total = page.value.total
                    if (page.value.tasks.size != minOf(200, (requireNotNull(total) - offset).coerceAtLeast(0))) complete = false
                    for (entry in page.value.tasks) {
                        if (entries.put(entry.id, entry) != null) complete = false
                        relatedMetadata[entry.id] = CachedResult(entry, page.source, page.fetchedAt, page.refreshFailure)
                        if (entry.id !in snapshots) {
                            try {
                                val detail = (details[entry.kind] ?: throw ApiFailure.Protocol())(lease, entry.id, cacheOnly); check(lease)
                                val value = detail.value ?: throw ApiFailure.Protocol()
                                if (value.id != entry.id || value.farmId != lease.scope.farmId || value.batchId != entry.batch.id || value.type != entry.kind.name) throw ApiFailure.Protocol()
                                snapshots[entry.id] = detail
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                check(lease)
                                if (failure !is CacheMiss && failure !is ApiFailure.Network && failure !is com.sentri.nativeapp.auth.AuthUnavailable) throw failure
                                missing += RelatedTaskUnavailable(entry.id, failure)
                            }
                        }
                    }
                    offset = Math.addExact(offset, page.value.tasks.size)
                    if (page.value.tasks.isEmpty()) break
                } while (offset < requireNotNull(total))
                if (entries.size != total) complete = false
                if (complete) for (pig in chunk) {
                    val expected = entries.values.filter { entry -> entry.pigs.any { it.id == pig } }.map { it.id }.toSet()
                    if (expected.all { taskId -> snapshots[taskId]?.value?.let { it.status in setOf("PLANNED", "SCHEDULED", "RUNNING") && pig in it.pigIds } == true })
                        taskIdsByPig[pig] = expected
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                check(lease)
                if (failure !is CacheMiss && failure !is ApiFailure.Network && failure !is com.sentri.nativeapp.auth.AuthUnavailable) throw failure
                // A missing membership page is not proof that these pigs have no other tasks.
                chunk.forEach { missing += RelatedTaskUnavailable("pig:$it", failure) }
            }
        }
        val taskValues = snapshots.values.mapNotNull { it.value }
        val profiles = linkedMapOf<String, CachedResult<PigProfile?>>()
        val unavailableProfiles = mutableListOf<RelatedPigUnavailable>()
        if (pigProfiles != null) {
            val ids = relatedPigIds(taskValues, metadata, contextCommands)
            // A complete exact-ID page fits in one request; stable chunks preserve cache identity across process restarts.
            for (chunk in ids.sorted().chunked(100)) {
                try {
                    val page = pigProfiles.page(lease, PigProfileRequest(filter = PigFilter(pageSize = 100), ids = chunk.toSet(), includeCount = false), cacheOnly)
                    check(lease)
                    val indexed = page.value.pigs.associateBy { it.id }
                    chunk.forEach { pigId -> profiles[pigId] = CachedResult(indexed[pigId], page.source, page.fetchedAt, page.refreshFailure) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    check(lease)
                    if (failure !is CacheMiss && failure !is ApiFailure.Network && failure !is com.sentri.nativeapp.auth.AuthUnavailable) throw failure
                    chunk.forEach { unavailableProfiles += RelatedPigUnavailable(it, failure) }
                }
            }
        }
        // Network reads can outlive an enqueue, claim or cancellation. Project the current statuses, not the context-discovery copy.
        val commands = queue.list(lease.scope); check(lease)
        val deathContext = PigDeathContext(taskIdsByPig, profiles.filterValues { it.value == null }.keys)
        val projection = ProductionProjector.project(lease.scope, taskValues, relatedMetadata.values.map { it.value }, commands, reflectedCommandIds, profiles.values.mapNotNull { it.value }, deathContext)
        check(lease)
        ProductionWorkspace(id, common, snapshots.toMap(), projection, missing.toList(), profiles.toMap(), unavailableProfiles.toList(), relatedMetadata.toMap(), deathContext)
    }

    private fun relatedPigIds(tasks: List<ProductionTaskSnapshot>, metadata: TaskMetadata?, commands: List<QueuedCommand>): Set<String> {
        val ids = tasks.flatMap { it.pigIds }.toMutableSet()
        ids += metadata?.pigs.orEmpty().map { it.id }
        ids += metadata?.matingMaterials.orEmpty().mapNotNull { it.boarId }
        tasks.filterIsInstance<MatingTask>().flatMap { it.pigs }.flatMap { it.records }.forEach { record ->
            listOfNotNull(record.boar?.boarId, record.semen?.boarId).forEach(ids::add)
        }
        val taskIds = tasks.map { it.id }.toSet() + listOfNotNull(metadata?.id)
        val selected = mutableSetOf<String>()
        val pending = commands.filter { it.record.status != CommandStatus.CANCELLED }
        // A batch role change or explicit prerequisite can introduce more pigs; include its complete atomic context.
        do {
            val previous = selected.size
            for (command in pending) if (command.record.id in selected || command.input.taskId in taskIds ||
                command.input.targetPigIds.any(ids::contains) || pending.any { it.record.id in selected && command.record.id in it.record.dependsOn }) {
                selected += command.record.id
                ids += command.input.targetPigIds
                (command.input as? MateWithBoarInput)?.boarId?.let(ids::add)
            }
        } while (selected.size != previous)
        return ids
    }
}
