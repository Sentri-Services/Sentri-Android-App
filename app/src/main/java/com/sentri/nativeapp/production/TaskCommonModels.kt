package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput

enum class ProductionTaskKind { ESTRUS, FARROWING, FATTENING, LACTATION, MATING, NURSERY, PIGLET_PROCESSING, POSTPARTUM, PREGNANCY, RE_ESTRUS }
data class MarkTaskPigInput(override val taskId: String, override val pigId: String, val batchId: String, val remark: String?) : CarePigInput {
    override val operationName: String get() = "MarkTaskPig"
    override val productionType: String get() = "unknown"
    init { require(taskId.isNotBlank() && pigId.isNotBlank() && batchId.isNotBlank()) }
    fun graphql() = ProductionMarkTaskPigCommandStrawberryInput(task_id = taskId, pig_id = pigId, batch_id = batchId, remark = Optional.Present(remark))
}
data class CompleteTaskInput(override val taskId: String, val batchId: String, val kind: ProductionTaskKind, val endedAt: String?) : CommandInput {
    override val operationName: String get() = "CompleteProductionTask"
    override val productionType: String get() = "unknown"
    override val targetPigIds: Set<String> get() = emptySet()
    override val groupKeys: Set<String> get() = setOf("taskComplete:$taskId")
    init { require(taskId.isNotBlank() && batchId.isNotBlank()); endedAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionCompleteTaskCommandStrawberryInput(task_id = taskId, batch_id = batchId, task_type = ProductionBatchTaskType.valueOf(kind.name), actual_end_time = Optional.Present(endedAt))
}
