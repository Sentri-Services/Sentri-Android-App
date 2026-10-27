package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.network.TimestampAdapter
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale

enum class ProductionTaskStatus { COMPLETED, INITIALIZED, PLANNED, RUNNING, SCHEDULED, SKIPPED }
enum class TaskCatalogOrder { CREATED_DESC, PLANNED_ASC }
/** Absolute seconds with decimal fractions; no rounding or implicit device timezone. */
private fun seconds(value: String): BigDecimal {
    TimestampAdapter.validate(value)
    val fraction = Regex("\\.(\\d+)").find(value)?.groupValues?.get(1)
    val whole = value.replace(' ', 'T').replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+0000")
        .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    val epoch = requireNotNull(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).apply { isLenient = false }.parse(whole)).time
    return BigDecimal.valueOf(epoch, 3) + (fraction?.let { BigDecimal("0.$it") } ?: BigDecimal.ZERO)
}
data class TaskTimeWindow(val from: String, val until: String) {
    init { require(seconds(from) < seconds(until)) }
    fun contains(at: String) = seconds(at).let { it >= seconds(from) && it < seconds(until) }
}
data class TaskCatalogRequest(val kinds: Set<ProductionTaskKind> = emptySet(), val statuses: Set<ProductionTaskStatus> = emptySet(),
    val completedWindow: TaskTimeWindow? = null, val batchId: String? = null, val lineId: String? = null, val operatorId: String? = null,
    val offset: Int = 0, val limit: Int = 50, val order: TaskCatalogOrder = TaskCatalogOrder.CREATED_DESC, val pigIds: Set<String>? = null) {
    init { require(offset >= 0 && limit in 1..200); require(listOfNotNull(batchId, lineId, operatorId).all(String::isNotBlank))
        require(completedWindow == null || ProductionTaskStatus.COMPLETED !in statuses); require(pigIds?.all(String::isNotBlank) != false) }
    fun accepts(value: TaskMetadata): Boolean = (kinds.isEmpty() || value.kind in kinds) && (batchId == null || value.batch.id == batchId) &&
        (lineId == null || value.batch.line?.id == lineId) && (operatorId == null || value.operatorId == operatorId) &&
        (pigIds == null || value.pigs.any { it.id in pigIds }) &&
        (if (completedWindow == null) statuses.isEmpty() || value.status in statuses else value.status in statuses ||
            (value.status == ProductionTaskStatus.COMPLETED && value.endedAt?.let(completedWindow::contains) == true))
    companion object {
        fun activeAndCompleted(from: String, until: String) = TaskCatalogRequest(statuses = setOf(ProductionTaskStatus.SCHEDULED, ProductionTaskStatus.RUNNING), completedWindow = TaskTimeWindow(from, until))
    }
}
data class TaskOperator(val id: String, val name: String?, val avatar: String?)
data class TaskLine(val id: String, val farmId: String, val name: String, val sowCapacity: Int)
data class RelatedProductionTask(val id: String, val kind: ProductionTaskKind, val status: ProductionTaskStatus)
data class TaskBatch(val id: String, val serial: String, val profile: JsonValue?, val line: TaskLine?, val tasks: List<RelatedProductionTask>)
data class TaskPlan(val startedAt: String?, val durationDays: Int?)
data class TaskPigReference(val id: String, val disabled: Boolean, val earTag: String?, val earNotch: String?, val locationCode: String?, val mark: String?)
data class TaskRecheckStatistic(val at: String, val value: JsonValue?)
/** Common metadata is separate from the ten typed production-detail snapshots. */
data class TaskMetadata(val id: String, val farmId: String, val kind: ProductionTaskKind, val status: ProductionTaskStatus,
    val startedAt: String?, val endedAt: String?, val createdAt: String?, val modifiedAt: String?, val operatorId: String?, val operator: TaskOperator?,
    val batch: TaskBatch, val plan: TaskPlan?, val pigs: List<TaskPigReference>, val matingMaterials: List<MatingMaterial>,
    val recheckStatistics: List<TaskRecheckStatistic>, val nurseryLocations: List<NurseryLocation>)
data class TaskCatalogPage(val request: TaskCatalogRequest, val tasks: List<TaskMetadata>, val total: Int)
