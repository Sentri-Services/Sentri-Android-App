package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope

enum class VaccinationPreviewState { PREVIEW, WAITING_CONFIRMATION, REJECTED, WAITING_DEPENDENCY,
    WAITING_REFRESH, MISSING_CONTEXT, INVALID_CONTEXT, CYCLIC_DEPENDENCY }
data class LocalVaccinationDose(val commandId: String, val taskId: String, val targetId: String,
    val doseIndex: Int, val reason: String)
data class VaccinationPreviewEffect(val command: QueuedCommand, val state: VaccinationPreviewState,
    val affectedTargetIds: Set<String> = emptySet(), val localDoses: List<LocalVaccinationDose> = emptyList())
data class VaccinationProjection(val serverTask: VaccinationTask, val previewTask: VaccinationTask,
    val effects: List<VaccinationPreviewEffect>, val localDoses: List<LocalVaccinationDose>)

/** Replays only confirmed-to-be-queued intent; no server snapshot or durable command is mutated. */
object VaccinationProjector {
    fun project(scope: DataScope, task: VaccinationTask, commands: List<QueuedCommand>,
        reflectedCommandIds: Set<String> = emptySet()): VaccinationProjection {
        VaccinationReadValidation.task(scope.farmId, task)
        val scoped = commands.filter { it.record.scope == scope }
        require(scoped.map { it.record.id }.distinct().size == scoped.size)
        require(scoped.all { it.record.operationName == it.input.operationName &&
            it.record.productionType == it.input.productionType && it.record.groupKeys == it.input.groupKeys })
        val indexed = scoped.associateBy { it.record.id }
        require(reflectedCommandIds.all { indexed[it]?.record?.status == CommandStatus.SYNCED })
        val remaining = scoped.filter { it.input is VaccinationCommandInput &&
            (it.input as VaccinationCommandInput).vaccinationTaskId == task.id &&
            it.record.status != CommandStatus.CANCELLED && it.record.id !in reflectedCommandIds }.toMutableList()
        val effects = mutableListOf<VaccinationPreviewEffect>()
        val local = mutableListOf<LocalVaccinationDose>()
        val applied = mutableSetOf<String>()
        var preview = task
        fun reaches(from: QueuedCommand, targetId: String): Boolean {
            val pending = ArrayDeque(from.record.dependsOn); val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (id == targetId) return true
                if (seen.add(id)) indexed[id]?.record?.dependsOn?.let(pending::addAll)
            }
            return false
        }
        fun predecessors(command: QueuedCommand): Set<String> = command.record.dependsOn + scoped.filter { prior ->
            prior.record.id != command.record.id && prior.record.status != CommandStatus.CANCELLED &&
                prior.record.id !in reflectedCommandIds && CommandPolicy.before(prior.record, command.record) &&
                prior.record.groups.any(command.record.groups::contains) && !reaches(prior, command.record.id)
        }.map { it.record.id }
        while (remaining.isNotEmpty()) {
            val command = remaining.filter { candidate -> predecessors(candidate).none { id -> remaining.any { it.record.id == id } } }
                .minWithOrNull(compareBy<QueuedCommand> { it.record.createdAtMillis }.thenBy { it.record.id })
            if (command == null) {
                remaining.forEach { effects += VaccinationPreviewEffect(it, VaccinationPreviewState.CYCLIC_DEPENDENCY) }
                break
            }
            remaining.remove(command)
            val deps = predecessors(command)
            val state = when (command.record.status) {
                CommandStatus.SYNCED -> VaccinationPreviewState.WAITING_REFRESH
                CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN -> VaccinationPreviewState.WAITING_CONFIRMATION
                CommandStatus.FAILED, CommandStatus.CONFLICTED -> VaccinationPreviewState.REJECTED
                CommandStatus.CANCELLED -> error("Cancelled command was excluded")
                CommandStatus.QUEUED -> when {
                    deps.any { indexed[it]?.record?.status == CommandStatus.SYNCED && it !in reflectedCommandIds } -> VaccinationPreviewState.WAITING_REFRESH
                    deps.any { it !in applied && it !in reflectedCommandIds && indexed[it]?.record?.status != CommandStatus.CANCELLED } -> VaccinationPreviewState.WAITING_DEPENDENCY
                    else -> null
                }
            }
            if (state != null) { effects += VaccinationPreviewEffect(command, state); continue }
            val appliedResult = try { apply(preview, command) }
            catch (failure: PreviewProblem) { effects += VaccinationPreviewEffect(command, failure.state); continue }
            catch (_: ArithmeticException) { effects += VaccinationPreviewEffect(command, VaccinationPreviewState.INVALID_CONTEXT); continue }
            preview = appliedResult.first
            effects += appliedResult.second
            local += appliedResult.second.localDoses
            applied += command.record.id
        }
        return VaccinationProjection(task, preview, effects.toList(), local.toList())
    }

    private fun apply(task: VaccinationTask, command: QueuedCommand): Pair<VaccinationTask, VaccinationPreviewEffect> {
        val input = command.input as VaccinationCommandInput
        fun need(ok: Boolean, state: VaccinationPreviewState = VaccinationPreviewState.INVALID_CONTEXT) {
            if (!ok) throw PreviewProblem(state)
        }
        return when (input) {
            is ExecuteVaccinationTaskInput -> {
                need(task.status !in setOf("COMPLETED", "DELETED"))
                val selected = input.targetIds.toSet()
                need(selected.isNotEmpty() && selected.all { id -> task.targets.any { it.targetId == id } })
                val changed = task.targets.toMutableList()
                val local = mutableListOf<LocalVaccinationDose>()
                for (targetId in selected) {
                    val history = changed.withIndex().filter { it.value.targetId == targetId }
                    need(history.isNotEmpty(), VaccinationPreviewState.MISSING_CONTEXT)
                    val latest = history.maxBy { it.value.doseIndex }
                    need(history.filter { it.value.doseIndex < latest.value.doseIndex }.all { it.value.status == "COMPLETED" },
                        VaccinationPreviewState.MISSING_CONTEXT)
                    need(latest.value.status in setOf("PENDING", "IN_PROGRESS") && latest.value.doseIndex <= task.doseCount)
                    changed[latest.index] = latest.value.copy(status = "COMPLETED", executedAt = input.executedAt,
                        executedBy = null, operatorId = null)
                    if (latest.value.doseIndex < task.doseCount) {
                        changed += latest.value.copy(doseIndex = latest.value.doseIndex + 1, status = "PENDING",
                            scheduledAt = null, executedAt = null, executedBy = null, operatorId = null)
                        local += LocalVaccinationDose(command.record.id, task.id, targetId, latest.value.doseIndex + 1, "NEXT_DOSE")
                    }
                }
                val finishedTargets = changed.groupBy { it.targetId }.values.count { history ->
                    history.any { it.doseIndex == task.doseCount && it.status == "COMPLETED" }
                }
                val finished = finishedTargets == task.targetCount
                val next = task.copy(status = if (finished) "COMPLETED" else "IN_PROGRESS",
                    modifiedAt = null, executedAt = input.executedAt, executedBy = null, operatorId = null,
                    completedAt = if (finished) input.executedAt else task.completedAt,
                    completedTargetDoseCount = Math.addExact(task.completedTargetDoseCount, selected.size),
                    settledTargetCount = maxOf(task.settledTargetCount, finishedTargets), targets = changed)
                next to VaccinationPreviewEffect(command, VaccinationPreviewState.PREVIEW, selected, local)
            }
            is FinishVaccinationTaskInput -> {
                need(task.status != "DELETED")
                val next = if (task.status == "COMPLETED") task else task.copy(status = "COMPLETED",
                    modifiedAt = null, executedAt = input.finishedAt, executedBy = null, operatorId = null)
                next to VaccinationPreviewEffect(command, VaccinationPreviewState.PREVIEW)
            }
        }
    }
}
private class PreviewProblem(val state: VaccinationPreviewState) : RuntimeException()
