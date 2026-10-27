package com.sentri.nativeapp.offline

import com.sentri.nativeapp.production.CheckEstrusInput
import com.sentri.nativeapp.production.SubmitEstrusInput
import com.sentri.nativeapp.production.UnitRoundInput
import com.sentri.nativeapp.production.CompleteTaskInput
import com.sentri.nativeapp.therapy.TherapyCommandInput
import com.sentri.nativeapp.vaccination.VaccinationCommandInput
import com.sentri.nativeapp.vaccination.FinishVaccinationTaskInput
import com.sentri.nativeapp.sampling.SubmitSampledInput
import com.sentri.nativeapp.inspection.DeviceInspectionReference
import com.sentri.nativeapp.inspection.ResolveDeviceInspectionInput
import com.sentri.nativeapp.inspection.SubmitUnitInspectionInput
import com.sentri.nativeapp.inspection.SubmitUnitRemarkInput
import com.sentri.nativeapp.inspection.PigHealthCommandInput
import com.sentri.nativeapp.inspection.PigMeasurementCommandInput
import com.sentri.nativeapp.inspection.RecordPigWeightsInput
import com.sentri.nativeapp.inspection.ReconcileLocationInventoryInput
import com.sentri.nativeapp.transfer.TransferTaskCommandInput
import com.sentri.nativeapp.transfer.CompleteTransferTaskInput
import com.sentri.nativeapp.transfer.TransferPigInput

enum class TaskQueueConflictReason { COMPLETION_STARTED, DUPLICATE_COMPLETION, COMPLETION_DEPENDENCY }
class TaskQueueConflict(val reason: TaskQueueConflictReason) : IllegalStateException(reason.name)
enum class VaccinationQueueConflictReason { FINISH_STARTED, DUPLICATE_FINISH, FINISH_PENDING }
class VaccinationQueueConflict(val reason: VaccinationQueueConflictReason) : IllegalStateException(reason.name)
enum class SamplingQueueConflictReason { DUPLICATE_SUBMISSION, RESULT_UNCONFIRMED }
class SamplingQueueConflict(val reason: SamplingQueueConflictReason) : IllegalStateException(reason.name)
enum class TransferQueueConflictReason { COMPLETION_STARTED, DUPLICATE_COMPLETION, COMPLETION_PENDING }
class TransferQueueConflict(val reason: TransferQueueConflictReason) : IllegalStateException(reason.name)

/** Called inside the enqueue transaction with commands from exactly one scope. */
object CommandDependencies {
    fun validateNew(input: CommandInput, dependencies: Set<String>, scopedCommands: List<QueuedCommand>) {
        if (input is ResolveDeviceInspectionInput && input.reference is DeviceInspectionReference.LocalReport) {
            val parent = DeviceInspectionReferences.report(input, scopedCommands)
            require(parent != null && parent.record.status != CommandStatus.CANCELLED) { "Unknown local device inspection report" }
        }
        if (input is SubmitSampledInput) {
            val prior = scopedCommands.filter { it.input is SubmitSampledInput &&
                (it.input as SubmitSampledInput).samplingTaskId == input.samplingTaskId &&
                it.record.status != CommandStatus.CANCELLED }
            if (prior.any { it.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.SYNCED) })
                throw SamplingQueueConflict(SamplingQueueConflictReason.RESULT_UNCONFIRMED)
            if (prior.isNotEmpty()) throw SamplingQueueConflict(SamplingQueueConflictReason.DUPLICATE_SUBMISSION)
        }
        if (input is VaccinationCommandInput) {
            val finishes = scopedCommands.filter { it.input is FinishVaccinationTaskInput &&
                (it.input as FinishVaccinationTaskInput).vaccinationTaskId == input.vaccinationTaskId }
            if (finishes.any { it.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.SYNCED) })
                throw VaccinationQueueConflict(VaccinationQueueConflictReason.FINISH_STARTED)
            if (finishes.any { it.record.status != CommandStatus.CANCELLED })
                throw VaccinationQueueConflict(if (input is FinishVaccinationTaskInput)
                    VaccinationQueueConflictReason.DUPLICATE_FINISH else VaccinationQueueConflictReason.FINISH_PENDING)
        }
        if (input is TransferTaskCommandInput) {
            val completions = scopedCommands.filter { it.input is CompleteTransferTaskInput &&
                (it.input as CompleteTransferTaskInput).transferId == input.transferId &&
                it.record.status != CommandStatus.CANCELLED }
            if (completions.any { it.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.SYNCED) })
                throw TransferQueueConflict(TransferQueueConflictReason.COMPLETION_STARTED)
            if (completions.isNotEmpty()) throw TransferQueueConflict(if (input is CompleteTransferTaskInput)
                TransferQueueConflictReason.DUPLICATE_COMPLETION else TransferQueueConflictReason.COMPLETION_PENDING)
        }
        val task = input.taskId ?: return
        val completions = scopedCommands.filter { it.input is CompleteTaskInput && it.input.taskId == task }
        if (completions.any { it.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.SYNCED) })
            throw TaskQueueConflict(TaskQueueConflictReason.COMPLETION_STARTED)
        val pending = completions.filter { it.record.status != CommandStatus.CANCELLED }.map { it.record.id }.toSet()
        if (input is CompleteTaskInput && pending.isNotEmpty()) throw TaskQueueConflict(TaskQueueConflictReason.DUPLICATE_COMPLETION)
        val indexed = scopedCommands.associateBy { it.record.id }
        val visits = mutableSetOf<String>(); val todo = ArrayDeque(dependencies)
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (id in pending) throw TaskQueueConflict(TaskQueueConflictReason.COMPLETION_DEPENDENCY)
            if (visits.add(id)) indexed[id]?.record?.dependsOn?.let(todo::addAll)
        }
    }
    fun required(input: CommandInput, scopedCommands: List<QueuedCommand>): Set<String> = when (input) {
        is TransferTaskCommandInput -> scopedCommands.filter { command ->
            val previous = command.input
            val sameTask = (previous as? TransferTaskCommandInput)?.transferId == input.transferId
            val samePig = (previous is TransferTaskCommandInput || previous is TransferPigInput) &&
                previous.targetPigIds.any(input.targetPigIds::contains)
            (sameTask || samePig) && command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is TransferPigInput -> scopedCommands.filter { command ->
            (command.input is TransferTaskCommandInput || command.input is TransferPigInput) &&
                input.pigId in command.input.targetPigIds &&
                command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is ReconcileLocationInventoryInput -> scopedCommands.filter { command ->
            val previous = command.input as? ReconcileLocationInventoryInput
            previous?.locationCode == input.locationCode &&
                command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is PigHealthCommandInput, is PigMeasurementCommandInput -> scopedCommands.filter { command ->
            val previous = command.input
            val samePig = previous is PigHealthCommandInput || previous is PigMeasurementCommandInput
            val aggregateWeight = input is RecordPigWeightsInput && input.totalWeight != null &&
                previous is RecordPigWeightsInput && previous.totalWeight != null
            (aggregateWeight || samePig && previous.targetPigIds.any(input.targetPigIds::contains)) &&
                command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is SubmitUnitInspectionInput -> if (input.unitInspectionId != null) emptySet() else scopedCommands.filter { command ->
            val previous = when (val old = command.input) {
                is SubmitUnitInspectionInput -> old.unitCode
                is SubmitUnitRemarkInput -> old.unitCode
                else -> null
            }
            previous == input.unitCode && command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is SubmitUnitRemarkInput -> {
            val unitCode = input.unitCode
            scopedCommands.filter { command ->
                val previous = when (val old = command.input) {
                    is SubmitUnitInspectionInput -> old.unitCode
                    is SubmitUnitRemarkInput -> old.unitCode
                    else -> null
                }
                previous == unitCode && command.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
            }.map { it.record.id }.toSet()
        }
        is ResolveDeviceInspectionInput -> DeviceInspectionReferences.report(input, scopedCommands)?.let { setOf(it.record.id) } ?: emptySet()
        is VaccinationCommandInput -> scopedCommands.filter {
            val previous = it.input as? VaccinationCommandInput
            previous?.vaccinationTaskId == input.vaccinationTaskId &&
                it.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is TherapyCommandInput -> scopedCommands.filter {
            val previous = it.input as? TherapyCommandInput
            previous?.prescriptionId == input.prescriptionId && it.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is CompleteTaskInput -> scopedCommands.filter {
            it.input.taskId == input.taskId && it.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is SubmitEstrusInput -> scopedCommands.filter {
            val check = it.input as? CheckEstrusInput
            check != null && check.taskId == input.taskId && check.pigId in input.targetPigIds &&
                it.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        is UnitRoundInput -> scopedCommands.filter {
            val previous = it.input as? UnitRoundInput
            previous != null && previous.productionType == input.productionType && previous.taskId == input.taskId &&
                previous.unitCode == input.unitCode && (input.isSubmission || previous.isSubmission) &&
                it.record.status !in setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
        }.map { it.record.id }.toSet()
        else -> emptySet()
    }
}
