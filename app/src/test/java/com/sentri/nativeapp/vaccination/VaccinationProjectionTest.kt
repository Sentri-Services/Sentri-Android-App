package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class VaccinationProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private fun dose(target: String, index: Int = 1, status: String = "PENDING") =
        VaccinationTargetDose("farm", "task", target, "vaccine", "brand", index, status, at,
            if (status == "COMPLETED") at else null, null, null, null)
    private val task = VaccinationTask("task", "farm", "vaccine", "brand", "PENDING", at, at, at,
        null, null, null, null, "IM", 7, "DAY", "mL", 2, BigDecimal("0.100"), 0, 0, 0, 2,
        "PLAN", null, null, null, false, null, null, null, listOf(dose("A"), dose("B")))
    private fun command(id: String, input: VaccinationCommandInput, time: Long, status: CommandStatus = CommandStatus.QUEUED,
        deps: Set<String> = emptySet()) = QueuedCommand(CommandRecord(id, id, scope, input.operationName, null,
            input.groupKeys, deps, time, status), input)

    @Test fun oneExecutionAdvancesOnlySelectedTargetThenSecondDoseFinishesIt() {
        val first = command("first", ExecuteVaccinationTaskInput("task", listOf("A", "A"), at), 100)
        val second = command("second", ExecuteVaccinationTaskInput("task", listOf("A"), at), 0, deps = setOf("first"))
        val result = VaccinationProjector.project(scope, task, listOf(first, second))
        assertEquals(listOf(VaccinationPreviewState.PREVIEW, VaccinationPreviewState.PREVIEW), result.effects.map { it.state })
        assertEquals(listOf("COMPLETED", "COMPLETED"), result.previewTask.targets.filter { it.targetId == "A" }.map { it.status })
        assertEquals("PENDING", result.previewTask.targets.single { it.targetId == "B" }.status)
        assertEquals(2, result.previewTask.completedTargetDoseCount)
        assertEquals(1, result.previewTask.settledTargetCount)
        assertEquals("IN_PROGRESS", result.previewTask.status)
        assertEquals(1, result.localDoses.size)
        assertEquals("first", result.localDoses.single().commandId)
        assertEquals(task, result.serverTask)
        assertEquals(2, task.targets.size)
    }
    @Test fun finishingDoesNotInventUnadministeredTargetDoses() {
        val finish = command("finish", FinishVaccinationTaskInput("task", null), 1)
        val result = VaccinationProjector.project(scope, task, listOf(finish))
        assertEquals("COMPLETED", result.previewTask.status)
        assertEquals(task.targets, result.previewTask.targets)
        assertEquals(0, result.previewTask.completedTargetDoseCount)
        assertNull(result.previewTask.executedAt)
        assertNull(result.previewTask.modifiedAt)
    }
    @Test fun unknownOrSyncedDoseBlocksDependentFinishUntilExplicitReadEvidence() {
        val dose = command("dose", ExecuteVaccinationTaskInput("task", listOf("A"), at), 1, CommandStatus.UNCERTAIN)
        val finish = command("finish", FinishVaccinationTaskInput("task", at), 2, deps = setOf("dose"))
        val unknown = VaccinationProjector.project(scope, task, listOf(dose, finish))
        assertEquals(listOf(VaccinationPreviewState.WAITING_CONFIRMATION, VaccinationPreviewState.WAITING_DEPENDENCY), unknown.effects.map { it.state })
        assertEquals(task, unknown.previewTask)
        val synced = dose.copy(record = dose.record.copy(status = CommandStatus.SYNCED))
        val pending = VaccinationProjector.project(scope, task, listOf(synced, finish))
        assertEquals(listOf(VaccinationPreviewState.WAITING_REFRESH, VaccinationPreviewState.WAITING_REFRESH), pending.effects.map { it.state })
        val reflected = VaccinationProjector.project(scope, task, listOf(synced, finish), setOf("dose"))
        assertEquals(VaccinationPreviewState.PREVIEW, reflected.effects.single().state)
        assertEquals("COMPLETED", reflected.previewTask.status)
        val failed = dose.copy(record = dose.record.copy(status = CommandStatus.FAILED))
        assertEquals(listOf(VaccinationPreviewState.REJECTED, VaccinationPreviewState.WAITING_DEPENDENCY),
            VaccinationProjector.project(scope, task, listOf(failed, finish)).effects.map { it.state })
    }
    @Test fun invalidSelectionDoesNotPartiallyApplyAndLastTargetDoseCompletesTask() {
        val bad = command("bad", ExecuteVaccinationTaskInput("task", listOf("A", "missing"), at), 1)
        val invalid = VaccinationProjector.project(scope, task, listOf(bad))
        assertEquals(VaccinationPreviewState.INVALID_CONTEXT, invalid.effects.single().state)
        assertEquals(task, invalid.previewTask)
        val last = task.copy(status = "IN_PROGRESS", completedTargetDoseCount = 3, settledTargetCount = 1,
            targets = listOf(dose("A", 1, "COMPLETED"), dose("A", 2, "COMPLETED"),
                dose("B", 1, "COMPLETED"), dose("B", 2)))
        val completed = VaccinationProjector.project(scope, last,
            listOf(command("last", ExecuteVaccinationTaskInput("task", listOf("B"), at), 2)))
        assertEquals("COMPLETED", completed.previewTask.status)
        assertEquals(4, completed.previewTask.completedTargetDoseCount)
        assertEquals(2, completed.previewTask.settledTargetCount)
        assertTrue(completed.localDoses.isEmpty())
    }
}
