package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TherapyProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val context = TherapyPrescriptionContext("rx", "farm", "InProgress", setOf("A", "B"),
        mapOf("t1" to "InProgress", "t2" to "InProgress"))
    private fun task(therapy: String, task: String, pig: String, times: Int = 1, status: String = "InProgress") =
        TherapyTargetTask("farm", "rx", therapy, task, pig, times, status, null, null, null, null, null, null)
    private fun row(id: String, taskId: String) = TherapyRow(id, "farm", "rx", null, BigDecimal("0.100"), null,
        "med", null, null, null, null, null, null, null, "InProgress", 2, "treatment", null, "ml",
        listOf(task(id, taskId, "A"), task(id, taskId, "B")))
    private val rows = listOf(row("t1", "scheduled1"), row("t2", "scheduled2"))
    private fun command(id: String, input: TherapyCommandInput, at: Long, status: CommandStatus = CommandStatus.QUEUED,
        depends: Set<String> = emptySet()) = QueuedCommand(CommandRecord(id, id, scope, input.operationName, null,
            input.groupKeys, depends, at, status), input)

    @Test fun completeAndSkipCreateNextRoundForEachTargetBeforeOtherTargetsSettle() {
        val first = command("first", CompleteTherapyTargetInput("rx", "t1", "scheduled1", listOf("A"), "2026-10-10T08:00:00.123456789+08:00"), 1)
        val second = command("second", SkipTherapyTaskInput("rx", "t1", "scheduled1", listOf("B"), "reason"), 2, depends = setOf("first"))
        val projection = TherapyProjector.project(scope, context, rows, listOf(first, second))
        assertEquals(listOf(TherapyPreviewState.PREVIEW, TherapyPreviewState.PREVIEW), projection.effects.map { it.state })
        val t1 = projection.previewRows.getValue("t1")
        assertEquals(listOf("Done", "InProgress"), t1.tasks.filter { it.targetId == "A" }.sortedBy { it.times }.map { it.status })
        assertEquals(listOf("Skipped", "InProgress"), t1.tasks.filter { it.targetId == "B" }.sortedBy { it.times }.map { it.status })
        assertNull(t1.tasks.single { it.targetId == "A" && it.times == 2 }.scheduledAt)
        assertNull(t1.tasks.single { it.targetId == "B" && it.times == 1 }.executedAt)
        assertEquals(listOf("first", "second"), projection.localRows.map { it.commandId })
        assertEquals(2, rows.first().tasks.size); assertEquals("InProgress", projection.previewContext.status)
        assertEquals(rows.associateBy { it.id }, projection.serverRows)
    }
    @Test fun targetChangesTouchEveryActiveTherapyAndTerminationIsPrescriptionWide() {
        val add = command("add", AddPrescriptionTargetInput("rx", listOf(PrescriptionTargetInput("C")), null), 1)
        val remove = command("remove", RemovePrescriptionTargetInput("rx", listOf(PrescriptionTargetInput("B")), null), 2, depends = setOf("add"))
        val stop = command("stop", TerminateTherapyInput("rx", "t1", "reason"), 3, depends = setOf("remove"))
        val all = command("all", TerminatePrescriptionInput("rx", null), 4, depends = setOf("stop"))
        val result = TherapyProjector.project(scope, context, rows, listOf(add, remove, stop, all))
        assertTrue(result.effects.all { it.state == TherapyPreviewState.PREVIEW })
        assertEquals(setOf("A", "C"), result.previewContext.targetIds)
        assertEquals("Terminated", result.previewContext.status)
        assertEquals(mapOf("t1" to "Terminated", "t2" to "Terminated"), result.previewContext.therapyStatuses)
        assertEquals(2, result.localRows.size); assertEquals(setOf("t1", "t2"), result.effects.first().affectedTherapyIds)
        assertEquals(setOf("C"), result.effects.first().affectedTargetIds)
        assertTrue(result.previewRows.values.all { it.tasks.none { task -> task.targetId == "B" } })
        assertTrue(result.previewRows.values.flatMap { it.tasks }.all { it.status == "Terminated" })
        assertEquals(2, rows.first().tasks.size)
    }
    @Test fun unavailableOrRejectedEarlierCommandBlocksFollowingChangesWithoutMutatingSnapshot() {
        val uncertain = command("unknown", CompleteTherapyTargetInput("rx", "t1", "scheduled1", listOf("A"), "2026-10-10T00:00:00Z"), 1,
            CommandStatus.UNCERTAIN)
        val next = command("next", TerminatePrescriptionInput("rx", null), 2, depends = setOf("unknown"))
        val projection = TherapyProjector.project(scope, context, rows, listOf(uncertain, next))
        assertEquals(listOf(TherapyPreviewState.WAITING_CONFIRMATION, TherapyPreviewState.WAITING_DEPENDENCY), projection.effects.map { it.state })
        assertEquals(context, projection.previewContext); assertEquals(projection.serverRows, projection.previewRows)
        val synced = uncertain.copy(record = uncertain.record.copy(status = CommandStatus.SYNCED))
        assertEquals(listOf(TherapyPreviewState.WAITING_REFRESH, TherapyPreviewState.WAITING_REFRESH),
            TherapyProjector.project(scope, context, rows, listOf(synced, next)).effects.map { it.state })
        val rejected = uncertain.copy(record = uncertain.record.copy(status = CommandStatus.FAILED))
        assertEquals(listOf(TherapyPreviewState.REJECTED, TherapyPreviewState.WAITING_DEPENDENCY),
            TherapyProjector.project(scope, context, rows, listOf(rejected, next)).effects.map { it.state })
    }
    @Test fun malformedMemberOrInvalidTargetNeverPartiallyAppliesACommand() {
        val wrongTask = command("wrong", CompleteTherapyTargetInput("rx", "t1", "missing", listOf("A"), "2026-10-10T00:00:00Z"), 1)
        val result = TherapyProjector.project(scope, context, rows, listOf(wrongTask))
        assertEquals(TherapyPreviewState.MISSING_CONTEXT, result.effects.single().state)
        assertEquals(result.serverRows, result.previewRows)
        val invalid = command("invalid", AddPrescriptionTargetInput("rx", listOf(PrescriptionTargetInput("C"), PrescriptionTargetInput("C")), null), 1)
        assertEquals(TherapyPreviewState.INVALID_CONTEXT, TherapyProjector.project(scope, context, rows, listOf(invalid)).effects.single().state)
        try { TherapyProjector.project(scope, context.copy(therapyStatuses = mapOf("t1" to "InProgress")), rows, emptyList()); fail() }
        catch (_: IllegalArgumentException) { }
        val foreign = wrongTask.copy(record = wrongTask.record.copy(scope = scope.copy(farmId = "other")))
        assertTrue(TherapyProjector.project(scope, context, rows, listOf(foreign)).effects.isEmpty())
    }
    @Test fun lastTargetAtFinalDoseFinishesTherapyThenPrescriptionOnlyWhenAllTherapiesTerminal() {
        val dose = task("t1", "scheduled1", "A", 2)
        val first = row("t1", "scheduled1").copy(tasks = listOf(dose))
        val second = row("t2", "scheduled2").copy(status = "Terminated", tasks = emptyList())
        val base = context.copy(targetIds = setOf("A"), therapyStatuses = mapOf("t1" to "InProgress", "t2" to "Terminated"))
        val input = CompleteTherapyTargetInput("rx", "t1", "scheduled1", listOf("A"), "2026-10-10T00:00:00Z")
        val result = TherapyProjector.project(scope, base, listOf(first, second), listOf(command("complete", input, 1)))
        assertEquals("Finished", result.previewRows.getValue("t1").status)
        assertEquals("Finished", result.previewContext.status)
        assertTrue(result.localRows.isEmpty())
        assertEquals("Done", result.previewRows.getValue("t1").tasks.single().status)
        assertEquals("InProgress", base.status)
    }
}
