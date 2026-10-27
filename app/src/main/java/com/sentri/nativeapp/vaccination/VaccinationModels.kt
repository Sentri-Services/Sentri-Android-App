package com.sentri.nativeapp.vaccination

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.BiosafetyExecuteVaccinationTaskInput
import com.sentri.nativeapp.graphql.type.BiosafetyFinishVaccinationTaskInput
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput

/** A vaccination task owns the aggregate. A target execution may advance only one of several doses. */
sealed interface VaccinationCommandInput : CommandInput {
    val vaccinationTaskId: String
    override val taskId: String? get() = null
    override val productionType: String? get() = null
    override val groupKeys: Set<String> get() = buildSet {
        add("vaccination:$vaccinationTaskId")
        targetPigIds.forEach { add("pig:$it") }
    }
}

data class ExecuteVaccinationTaskInput(override val vaccinationTaskId: String,
    val targetIds: List<String>, val executedAt: String?) : VaccinationCommandInput {
    override val operationName: String get() = "ExecuteVaccinationTask"
    override val targetPigIds: Set<String> get() = targetIds.toSet()
    init {
        require(vaccinationTaskId.isNotBlank() && targetIds.isNotEmpty() && targetIds.all(String::isNotBlank))
        executedAt?.let(TimestampAdapter::validate)
    }
    fun graphql() = BiosafetyExecuteVaccinationTaskInput(task_id = vaccinationTaskId,
        target_ids = Optional.Present(targetIds), executed_at = Optional.Present(executedAt))
}

/** Finish is a task-wide barrier; it must wait for every earlier target execution. */
data class FinishVaccinationTaskInput(override val vaccinationTaskId: String,
    val finishedAt: String?) : VaccinationCommandInput {
    override val operationName: String get() = "FinishVaccinationTask"
    override val targetPigIds: Set<String> get() = emptySet()
    init { require(vaccinationTaskId.isNotBlank()); finishedAt?.let(TimestampAdapter::validate) }
    fun graphql() = BiosafetyFinishVaccinationTaskInput(task_id = vaccinationTaskId,
        finished_at = Optional.Present(finishedAt))
}
