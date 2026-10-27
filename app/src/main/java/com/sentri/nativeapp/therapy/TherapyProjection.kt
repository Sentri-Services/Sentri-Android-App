package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.offline.CommandPolicy
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.pigs.DataScope

enum class TherapyPreviewState { PREVIEW, WAITING_CONFIRMATION, REJECTED, WAITING_DEPENDENCY,
    WAITING_REFRESH, MISSING_CONTEXT, INVALID_CONTEXT, CYCLIC_DEPENDENCY }

/** A pending row has an operation identity; its unknown server event time and operator remain null. */
data class LocalTherapyRow(val commandId: String, val therapyId: String, val taskId: String,
    val targetId: String, val times: Int, val reason: String)
data class TherapyPreviewEffect(val command: QueuedCommand, val state: TherapyPreviewState,
    val affectedTherapyIds: Set<String> = emptySet(), val affectedTargetIds: Set<String> = emptySet(),
    val localRows: List<LocalTherapyRow> = emptyList())
data class TherapyProjection(val serverContext: TherapyPrescriptionContext, val serverRows: Map<String, TherapyRow>,
    val previewContext: TherapyPrescriptionContext, val previewRows: Map<String, TherapyRow>,
    val effects: List<TherapyPreviewEffect>, val localRows: List<LocalTherapyRow>)

/** Replays only known queued intents against complete confirmed prescription membership. */
object TherapyProjector {
    fun project(scope: DataScope, context: TherapyPrescriptionContext, rows: List<TherapyRow>,
        commands: List<QueuedCommand>, reflectedCommandIds: Set<String> = emptySet()): TherapyProjection {
        TherapyReadValidation.context(scope.farmId, context.id, context)
        TherapyReadValidation.rows(scope.farmId, rows)
        require(rows.map { it.id }.toSet() == context.therapyStatuses.keys)
        require(rows.all { row -> row.prescriptionId == context.id && row.status == context.therapyStatuses[row.id] &&
            (row.prescription == null || row.prescription.status == context.status &&
                (row.prescription.therapyCount == null || row.prescription.therapyCount == rows.size)) })
        val original = rows.associateBy { it.id }
        val scoped = commands.filter { it.record.scope == scope }
        require(scoped.map { it.record.id }.distinct().size == scoped.size)
        require(scoped.all { it.record.operationName == it.input.operationName &&
            it.record.productionType == it.input.productionType && it.record.groupKeys == it.input.groupKeys })
        val indexed = scoped.associateBy { it.record.id }
        require(reflectedCommandIds.all { indexed[it]?.record?.status == CommandStatus.SYNCED })
        val preview = original.toMutableMap()
        var previewContext = context
        val effects = mutableListOf<TherapyPreviewEffect>()
        val localRows = mutableListOf<LocalTherapyRow>()
        val applied = mutableSetOf<String>()
        val remaining = scoped.filter { it.input is TherapyCommandInput && (it.input as TherapyCommandInput).prescriptionId == context.id &&
            it.record.status != CommandStatus.CANCELLED && it.record.id !in reflectedCommandIds }.toMutableList()
        fun reaches(from: QueuedCommand, target: String): Boolean {
            val todo = ArrayDeque(from.record.dependsOn); val seen = mutableSetOf<String>()
            while (todo.isNotEmpty()) { val id = todo.removeFirst(); if (id == target) return true; if (seen.add(id)) indexed[id]?.record?.dependsOn?.let(todo::addAll) }
            return false
        }
        fun predecessors(command: QueuedCommand): Set<String> = command.record.dependsOn + scoped.filter { other ->
            other.record.id != command.record.id && other.record.status != CommandStatus.CANCELLED && other.record.id !in reflectedCommandIds &&
                CommandPolicy.before(other.record, command.record) && other.record.groups.any(command.record.groups::contains) &&
                !reaches(other, command.record.id)
        }.map { it.record.id }
        while (remaining.isNotEmpty()) {
            val command = remaining.filter { candidate -> predecessors(candidate).none { id -> remaining.any { it.record.id == id } } }
                .minWithOrNull(compareBy<QueuedCommand> { it.record.createdAtMillis }.thenBy { it.record.id })
            if (command == null) {
                remaining.forEach { effects += TherapyPreviewEffect(it, TherapyPreviewState.CYCLIC_DEPENDENCY) }
                break
            }
            remaining.remove(command)
            val deps = predecessors(command)
            val state = when (command.record.status) {
                CommandStatus.SYNCED -> TherapyPreviewState.WAITING_REFRESH
                CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN -> TherapyPreviewState.WAITING_CONFIRMATION
                CommandStatus.FAILED, CommandStatus.CONFLICTED -> TherapyPreviewState.REJECTED
                CommandStatus.CANCELLED -> error("Cancelled command was excluded")
                CommandStatus.QUEUED -> when {
                    deps.any { indexed[it]?.record?.status == CommandStatus.SYNCED && it !in reflectedCommandIds } -> TherapyPreviewState.WAITING_REFRESH
                    deps.any { it !in applied && it !in reflectedCommandIds && indexed[it]?.record?.status != CommandStatus.CANCELLED } -> TherapyPreviewState.WAITING_DEPENDENCY
                    else -> null
                }
            }
            if (state != null) { effects += TherapyPreviewEffect(command, state); continue }
            val next = preview.toMutableMap()
            val result = try { apply(command, previewContext, next) }
            catch (failure: TherapyPreviewProblem) { effects += TherapyPreviewEffect(command, failure.state); continue }
            catch (_: ArithmeticException) { effects += TherapyPreviewEffect(command, TherapyPreviewState.INVALID_CONTEXT); continue }
            preview.clear(); preview.putAll(next); previewContext = result.first
            effects += result.second; localRows += result.second.localRows; applied += command.record.id
        }
        return TherapyProjection(context, original, previewContext, preview.toMap(), effects.toList(), localRows.toList())
    }

    private fun apply(command: QueuedCommand, context: TherapyPrescriptionContext,
        rows: MutableMap<String, TherapyRow>): Pair<TherapyPrescriptionContext, TherapyPreviewEffect> {
        val input = command.input as TherapyCommandInput
        var next = context
        val affected = mutableSetOf<String>(); val targets = mutableSetOf<String>(); val local = mutableListOf<LocalTherapyRow>()
        fun need(ok: Boolean, state: TherapyPreviewState = TherapyPreviewState.INVALID_CONTEXT) {
            if (!ok) throw TherapyPreviewProblem(state)
        }
        fun activeTaskIds(row: TherapyRow): Set<String> = row.tasks.groupBy { it.taskId }.filterValues { entries ->
            entries.groupBy { it.targetId }.values.any { targetRows -> targetRows.maxByOrNull { it.times }?.status == "InProgress" }
        }.keys
        fun replace(row: TherapyRow) { rows[row.id] = row; affected += row.id }
        need(input.prescriptionId == context.id)
        when (input) {
            is CompleteTherapyTargetInput, is SkipTherapyTaskInput -> {
                val therapyId = requireNotNull(input.therapyId)
                val taskId = if (input is CompleteTherapyTargetInput) input.therapyTaskId else (input as SkipTherapyTaskInput).therapyTaskId
                val selected = if (input is CompleteTherapyTargetInput) input.targetIds.toSet() else (input as SkipTherapyTaskInput).targetIds.toSet()
                val row = rows[therapyId] ?: throw TherapyPreviewProblem(TherapyPreviewState.MISSING_CONTEXT)
                need(row.status in setOf("NotStarted", "InProgress") && context.status in setOf("NotStarted", "InProgress"))
                val group = row.tasks.filter { it.taskId == taskId }
                need(group.isNotEmpty(), TherapyPreviewState.MISSING_CONTEXT)
                val current = group.groupBy { it.targetId }.mapValues { (_, history) -> history.maxByOrNull { it.times }!! }
                need(current.values.map { it.times to it.targetId }.distinct().size == current.size)
                if (input is SkipTherapyTaskInput) need(selected.all(current::containsKey))
                val chosen = current.values.filter { it.targetId in selected && it.status == "InProgress" }
                need(chosen.isNotEmpty() && chosen.all { it.times in 1..row.times })
                val chosenKeys = chosen.map { Triple(it.taskId, it.targetId, it.times) }.toSet()
                val changed = row.tasks.map { item -> if (Triple(item.taskId, item.targetId, item.times) !in chosenKeys) item else {
                    targets += item.targetId
                    if (input is CompleteTherapyTargetInput) item.copy(status = "Done", executedAt = input.completedAt, executedBy = null)
                    else item.copy(status = "Skipped", executedAt = null, executedBy = null, remark = (input as SkipTherapyTaskInput).reason)
                } }.toMutableList()
                for (item in chosen) if (item.times < row.times) {
                    need(changed.none { it.taskId == taskId && it.targetId == item.targetId && it.times == item.times + 1 })
                    changed += item.copy(times = item.times + 1, status = "InProgress", scheduledAt = null,
                        executedAt = null, executedBy = null, operatorId = null, remark = null)
                    local += LocalTherapyRow(command.record.id, therapyId, taskId, item.targetId, item.times + 1, "NEXT_ROUND")
                }
                val latest = changed.filter { it.taskId == taskId }.groupBy { it.targetId }.values.map { it.maxByOrNull { item -> item.times }!! }
                val finished = latest.all { it.status != "InProgress" && it.times >= row.times }
                val therapyStatus = if (finished) "Finished" else if (row.status == "NotStarted") "InProgress" else row.status
                next = next.copy(therapyStatuses = next.therapyStatuses + (therapyId to therapyStatus))
                if (finished && next.therapyStatuses.values.all { it in setOf("Finished", "Terminated") }) next = next.copy(status = "Finished")
                else if (next.status == "NotStarted") next = next.copy(status = "InProgress")
                replace(row.copy(tasks = changed, status = therapyStatus))
            }
            is AddPrescriptionTargetInput -> {
                need(context.status in setOf("NotStarted", "InProgress"))
                val ids = input.targets.map { it.targetId }; need(ids.distinct().size == ids.size)
                val added = ids.filterNot(context.targetIds::contains); need(added.isNotEmpty())
                next = next.copy(targetIds = next.targetIds + added)
                targets += added
                rows.values.toList().forEach { row ->
                    val active = activeTaskIds(row)
                    if (active.isNotEmpty()) {
                        val expanded = row.tasks.toMutableList()
                        for (taskId in active) for (targetId in added) {
                            need(expanded.none { it.taskId == taskId && it.targetId == targetId })
                            expanded += TherapyTargetTask(row.farmId, row.prescriptionId, row.id, taskId, targetId, 1,
                                "InProgress", null, null, null, null, null, null)
                            local += LocalTherapyRow(command.record.id, row.id, taskId, targetId, 1, "TARGET_ADDED")
                        }
                        replace(row.copy(tasks = expanded))
                    }
                }
            }
            is RemovePrescriptionTargetInput -> {
                need(context.status in setOf("NotStarted", "InProgress"))
                val ids = input.targets.map { it.targetId }; need(ids.distinct().size == ids.size)
                val removed = ids.filter(context.targetIds::contains); need(removed.isNotEmpty())
                next = next.copy(targetIds = next.targetIds - removed.toSet())
                targets += removed
                rows.values.toList().forEach { row ->
                    val active = activeTaskIds(row)
                    if (active.isNotEmpty()) replace(row.copy(tasks = row.tasks.filterNot { it.taskId in active && it.targetId in removed }))
                }
            }
            is TerminateTherapyInput -> {
                val row = rows[input.therapyId] ?: throw TherapyPreviewProblem(TherapyPreviewState.MISSING_CONTEXT)
                need(context.status in setOf("NotStarted", "InProgress") && row.status in setOf("NotStarted", "InProgress"))
                val active = activeTaskIds(row)
                replace(row.copy(status = "Terminated", tasks = row.tasks.map {
                    if (it.taskId in active) it.copy(status = "Terminated", remark = input.reason) else it
                }))
                next = next.copy(therapyStatuses = next.therapyStatuses + (row.id to "Terminated"))
                if (next.therapyStatuses.values.all { it in setOf("Finished", "Terminated") }) next = next.copy(status = "Terminated")
            }
            is TerminatePrescriptionInput -> {
                need(context.status in setOf("NotStarted", "InProgress"))
                next = next.copy(status = "Terminated", therapyStatuses = next.therapyStatuses.mapValues { (_, status) ->
                    if (status in setOf("NotStarted", "InProgress")) "Terminated" else status
                })
                rows.values.toList().forEach { row ->
                    val active = activeTaskIds(row)
                    replace(row.copy(status = next.therapyStatuses.getValue(row.id), tasks = row.tasks.map {
                        if (it.taskId in active) it.copy(status = "Terminated", remark = input.reason) else it
                    }))
                }
            }
        }
        // Relationship labels follow the projected aggregate, without changing the saved server snapshots.
        rows.values.toList().forEach { row -> rows[row.id] = row.copy(
            prescription = row.prescription?.copy(status = next.status), status = next.therapyStatuses.getValue(row.id)) }
        return next to TherapyPreviewEffect(command, TherapyPreviewState.PREVIEW, affected, targets, local)
    }
}

private class TherapyPreviewProblem(val state: TherapyPreviewState) : RuntimeException()
