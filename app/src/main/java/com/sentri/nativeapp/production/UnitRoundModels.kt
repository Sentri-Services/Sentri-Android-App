package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput

/** The exact unit selector is offline coordination metadata, never a GraphQL input field. Empty means all units in the old entry. */
interface UnitRoundInput : CommandInput {
    val unitCode: String
    val isSubmission: Boolean
    override val groupKeys: Set<String> get() = if (isSubmission) emptySet() else targetPigIds.map { "pig:$it" }.toSet()
}
enum class ReEstrusStatus { NOT_RE_ESTRUS, RE_ESTRUS, SIGNS_OF_RE_ESTRUS }
enum class PregnancyStatus { NOT_PREGNANT, NOT_SURE, PREGNANT }

data class CheckReEstrusInput(override val taskId: String, val pigId: String, val status: ReEstrusStatus, val checkAt: String?, override val unitCode: String) : UnitRoundInput {
    override val operationName: String get() = "CheckReEstrusPig"
    override val productionType: String get() = "reEstrus"
    override val targetPigIds: Set<String> get() = setOf(pigId)
    override val isSubmission: Boolean get() = false
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); checkAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionCheckReEstrusPigCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, check_status = ProductionReEstrusCheckStatus.valueOf(status.name), check_at = Optional.Present(checkAt),
    )
}
data class SubmitReEstrusInput(override val taskId: String, val pigIds: List<String>, val submitAt: String?, override val unitCode: String) : UnitRoundInput {
    override val operationName: String get() = "SubmitReEstrusStatus"
    override val productionType: String get() = "reEstrus"
    override val targetPigIds: Set<String> get() = pigIds.toSet()
    override val isSubmission: Boolean get() = true
    init { require(taskId.isNotBlank() && pigIds.isNotEmpty() && pigIds.all(String::isNotBlank)); submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitReEstrusStatusCommandStrawberryInput(task_id = taskId, pig_ids = pigIds, submit_at = Optional.Present(submitAt))
}
data class CheckPregnancyInput(override val taskId: String, val pigId: String, val status: PregnancyStatus, val checkAt: String?, val reCheckDays: Int?, override val unitCode: String) : UnitRoundInput {
    override val operationName: String get() = "CheckPregnancyPig"
    override val productionType: String get() = "pregnancy"
    override val targetPigIds: Set<String> get() = setOf(pigId)
    override val isSubmission: Boolean get() = false
    init { require(taskId.isNotBlank() && pigId.isNotBlank()); checkAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionCheckPregnancyPigCommandStrawberryInput(
        task_id = taskId, pig_id = pigId, check_status = ProductionPregnancyCheckStatus.valueOf(status.name),
        check_at = Optional.Present(checkAt), re_check_days = Optional.Present(reCheckDays),
    )
}
data class SubmitPregnancyInput(override val taskId: String, val pigIds: List<String>, val submitAt: String?, override val unitCode: String) : UnitRoundInput {
    override val operationName: String get() = "SubmitPregnancyStatus"
    override val productionType: String get() = "pregnancy"
    override val targetPigIds: Set<String> get() = pigIds.toSet()
    override val isSubmission: Boolean get() = true
    init { require(taskId.isNotBlank() && pigIds.isNotEmpty() && pigIds.all(String::isNotBlank)); submitAt?.let(TimestampAdapter::validate) }
    fun graphql() = ProductionSubmitPregnancyStatusCommandStrawberryInput(task_id = taskId, pig_ids = pigIds, submit_at = Optional.Present(submitAt))
}

data class TaskPigLocation(val locationCode: String?, val unitId: String?, val unitLocation: String?, val penLocation: String?)
data class ReEstrusResult(val status: ReEstrusStatus?, val checkedAt: String?, val isInReEstrus: Boolean, val reEstrusAt: String?, val matingAt: String?, val matingBy: String?, val submittedAt: String?, val operatorId: String?)
data class ReEstrusPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: ReEstrusResult?)
data class ReEstrusTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<ReEstrusPig>) : ProductionTaskSnapshot {
    override val type: String get() = "RE_ESTRUS"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
data class PregnancyResult(val status: PregnancyStatus?, val checkedAt: String?, val isPregnant: Boolean?, val reCheckAt: String?, val matingAt: String?, val matingBy: String?, val submittedAt: String?, val operatorId: String?)
data class PregnancyPig(val id: String, val earTag: String?, val earNotch: String?, val disabled: Boolean, val location: TaskPigLocation, val result: PregnancyResult?)
data class PregnancyTask(override val id: String, override val farmId: String, override val batchId: String, override val status: String, val pigs: List<PregnancyPig>) : ProductionTaskSnapshot {
    override val type: String get() = "PREGNANCY"
    override val pigIds: List<String> get() = pigs.map { it.id }
}
