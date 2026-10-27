package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.pigs.DataScope

enum class SamplingPreviewState { PREVIEW, WAITING_CONFIRMATION, WAITING_REFRESH, REJECTED, INVALID_CONTEXT }
data class SamplingPreviewEffect(val command: QueuedCommand, val state: SamplingPreviewState)
data class SamplingProjection(val serverTask: SamplingTask, val previewTask: SamplingTask,
    val effects: List<SamplingPreviewEffect>)

/** The server assigns sampled_at and sampled_by; a local intent cannot invent those values. */
object SamplingProjector {
    fun project(scope: DataScope, task: SamplingTask, commands: List<QueuedCommand>,
        reflectedCommandIds: Set<String> = emptySet()): SamplingProjection {
        SamplingReadValidation.task(scope.farmId, task)
        val scoped = commands.filter { it.record.scope == scope }
        require(scoped.map { it.record.id }.distinct().size == scoped.size)
        require(scoped.all { it.record.operationName == it.input.operationName &&
            it.record.productionType == it.input.productionType && it.record.groupKeys == it.input.groupKeys })
        val indexed = scoped.associateBy { it.record.id }
        require(reflectedCommandIds.all { indexed[it]?.record?.status == CommandStatus.SYNCED })
        val relevant = scoped.filter { it.input is SubmitSampledInput &&
            (it.input as SubmitSampledInput).samplingTaskId == task.id &&
            it.record.status != CommandStatus.CANCELLED && it.record.id !in reflectedCommandIds }
        require(relevant.size <= 1) { "Multiple active submissions for one sampling task" }
        var preview = task
        val effects = relevant.map { command ->
            val state = when (command.record.status) {
                CommandStatus.QUEUED -> if (task.disabled) SamplingPreviewState.INVALID_CONTEXT else {
                    val input = command.input as SubmitSampledInput
                    preview = task.copy(isSampled = true, sampledAt = null, sampledBy = null, remark = input.remark)
                    SamplingPreviewState.PREVIEW
                }
                CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN -> SamplingPreviewState.WAITING_CONFIRMATION
                CommandStatus.SYNCED -> SamplingPreviewState.WAITING_REFRESH
                CommandStatus.FAILED, CommandStatus.CONFLICTED -> SamplingPreviewState.REJECTED
                CommandStatus.CANCELLED -> error("Cancelled command was excluded")
            }
            SamplingPreviewEffect(command, state)
        }
        return SamplingProjection(task, preview, effects)
    }
}
