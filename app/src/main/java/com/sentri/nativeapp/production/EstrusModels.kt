package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.ProductionCheckEstrusPigCommandStrawberryInput
import com.sentri.nativeapp.graphql.type.ProductionEstrusCheckStatus
import com.sentri.nativeapp.graphql.type.ProductionSubmitEstrusStatusCommandStrawberryInput
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput

enum class EstrusStatus { IN_ESTRUS, NOT_IN_ESTRUS, SIGNS_OF_ESTRUS }
data class CheckEstrusInput(override val taskId: String, val pigId: String, val status: EstrusStatus, val checkAt: String?) : CommandInput {
    override val operationName: String get() = "CheckEstrus"
    override val productionType: String get() = "estrus"
    override val targetPigIds: Set<String> get() = setOf(pigId)
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); checkAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionCheckEstrusPigCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, check_status = ProductionEstrusCheckStatus.valueOf(status.name), check_at = Optional.Present(checkAt),
    )
}
data class SubmitEstrusInput(override val taskId: String, val pigIds: List<String>, val submitAt: String?, val autoEstrus: Boolean = false) : CommandInput {
    override val operationName: String get() = "SubmitEstrusStatus"
    override val productionType: String get() = "estrus"
    override val targetPigIds: Set<String> get() = pigIds.toSet()
    init {
        require(taskId.isNotBlank() && pigIds.isNotEmpty() && pigIds.all(String::isNotBlank))
        submitAt?.let(TimestampAdapter::validate)
    }
    fun graphql() = ProductionSubmitEstrusStatusCommandStrawberryInput(
        task_id = taskId, pig_ids = pigIds, submit_at = Optional.Present(submitAt), auto_estrus = Optional.Present(autoEstrus),
    )
}
data class EstrusPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val checkStatus: EstrusStatus?, val checkAt: String?, val isInEstrus: Boolean? = null, val estrusAt: String? = null, val location: TaskPigLocation? = null)
data class EstrusTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<EstrusPig>) : ProductionTaskSnapshot {
    override val type: String get() = "ESTRUS"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
