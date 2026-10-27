package com.sentri.nativeapp.production

import com.sentri.nativeapp.pigs.*

class ProductionAuxiliaryReads(private val remote: ProductionAuxiliaryRemote, cache: SnapshotCache, check: (ScopeLease) -> Unit,
    now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    private fun batch(value: ProductionBatch, lease: ScopeLease) {
        require(value.farmId == lease.scope.farmId && value.id.isNotBlank() && value.lineId.isNotBlank())
        require(value.line == null || value.line.id == value.lineId && value.line.farmId == value.farmId)
        require(value.tasks.all { it.id.isNotBlank() } && value.tasks.map { it.id }.distinct().size == value.tasks.size)
    }
    suspend fun batches(lease: ScopeLease, request: BatchRequest, cacheOnly: Boolean = false) =
        snapshots.read(lease, "production_batch_page", ProductionAuxiliaryCodecs.batchKey(request), cacheOnly,
            { remote.batches(lease, request) }, ProductionAuxiliaryCodecs::encodeBatches, ProductionAuxiliaryCodecs::decodeBatches) {
            require(it.request == request && it.batches.size <= request.limit && (!it.hasMore || it.batches.size == request.limit))
            require(it.batches.map { row -> row.id }.distinct().size == it.batches.size)
            it.batches.forEach { row -> batch(row, lease); require(request.accepts(row)) }
        }
    suspend fun batch(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<ProductionBatch?> {
        require(id.isNotBlank())
        return snapshots.read(lease, "production_batch_detail", id, cacheOnly, { remote.batch(lease, id) },
            ProductionAuxiliaryCodecs::encodeBatch, ProductionAuxiliaryCodecs::decodeBatch) { it?.let { row -> batch(row, lease); require(row.id == id) } }
    }
    suspend fun rechecks(lease: ScopeLease, request: RecheckRequest, cacheOnly: Boolean = false) =
        snapshots.read(lease, "production_recheck_page", ProductionAuxiliaryCodecs.recheckKey(request), cacheOnly,
            { remote.rechecks(lease, request) }, ProductionAuxiliaryCodecs::encodeRechecks, ProductionAuxiliaryCodecs::decodeRechecks) {
            require(it.request == request && it.pigs.size <= request.limit && (!it.hasMore || it.pigs.size == request.limit))
            require(it.pigs.map { row -> row.taskId to row.pigId }.distinct().size == it.pigs.size)
            it.pigs.forEach { row -> require(row.farmId == lease.scope.farmId && listOf(row.taskId, row.batchId, row.pigId).all(String::isNotBlank)); require(request.accepts(row)) }
        }
}
