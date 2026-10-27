package com.sentri.nativeapp.production

import com.sentri.nativeapp.pigs.ScopeLease

enum class ProductionBatchStatus { COMPLETED, PLANNING, SKIPPED, STARTED }
enum class ProductionLineStatus { PAUSED, PLANNING, RUNNING }
data class BatchLine(val id: String, val farmId: String, val name: String, val status: ProductionLineStatus)
data class BatchTaskReference(val id: String, val kind: ProductionTaskKind, val status: ProductionTaskStatus, val plan: TaskPlan?)
data class ProductionBatch(val id: String, val farmId: String, val serial: String, val status: ProductionBatchStatus,
    val lineId: String, val line: BatchLine?, val tasks: List<BatchTaskReference>)
data class BatchRequest(val statuses: Set<ProductionBatchStatus> = emptySet(), val lineId: String? = null,
    val excludeDefault: Boolean = false, val offset: Int = 0, val limit: Int = 50) {
    init { require(lineId == null || lineId.isNotBlank()); require(offset >= 0 && limit in 1..200) }
    fun accepts(value: ProductionBatch) = (statuses.isEmpty() || value.status in statuses) &&
        (lineId == null || value.lineId == lineId) && (!excludeDefault || !value.id.startsWith("LINE"))
    companion object {
        fun active() = BatchRequest(setOf(ProductionBatchStatus.PLANNING, ProductionBatchStatus.STARTED), excludeDefault = true)
        fun unfinished() = BatchRequest(ProductionBatchStatus.entries.toSet() - ProductionBatchStatus.COMPLETED, excludeDefault = true)
    }
}
/** One extra row proves hasMore; no guessed total or dependency on aggregate permissions. */
data class BatchPage(val request: BatchRequest, val batches: List<ProductionBatch>, val hasMore: Boolean)
data class NamedProductionLocation(val code: String, val chinese: String, val english: String)
data class RecheckLocation(val basic: TaskPigLocation, val zone: NamedProductionLocation?, val unit: NamedProductionLocation?,
    val section: NamedProductionLocation?, val rowCode: String?, val rowNumber: String?, val penNumber: Int)
data class RecheckPig(val taskId: String, val batchId: String, val farmId: String, val pigId: String,
    val earTag: String?, val earNotch: String?, val location: RecheckLocation?, val result: ReEstrusResult)
data class RecheckRequest(val taskId: String? = null, val batchId: String? = null, val unitId: String? = null,
    val inReEstrus: Boolean? = null, val offset: Int = 0, val limit: Int = 50) {
    init { require(listOfNotNull(taskId, batchId, unitId).all(String::isNotBlank)); require(offset >= 0 && limit in 1..200) }
    fun accepts(value: RecheckPig) = (taskId == null || value.taskId == taskId) && (batchId == null || value.batchId == batchId) &&
        (unitId == null || value.location?.basic?.unitId == unitId) && (inReEstrus == null || value.result.isInReEstrus == inReEstrus)
}
data class RecheckPage(val request: RecheckRequest, val pigs: List<RecheckPig>, val hasMore: Boolean)
interface ProductionAuxiliaryRemote {
    suspend fun batches(lease: ScopeLease, request: BatchRequest): BatchPage
    suspend fun batch(lease: ScopeLease, id: String): ProductionBatch?
    suspend fun rechecks(lease: ScopeLease, request: RecheckRequest): RecheckPage
}
