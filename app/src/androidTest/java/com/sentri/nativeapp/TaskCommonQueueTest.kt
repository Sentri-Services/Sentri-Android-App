package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskCommonQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("simulated-dev", "simulated-oauth", "user", "farm")
    private val queueContext = QueueContext(scope, 1, true, true)
    private val mark = MarkTaskPigInput("task", "pig", "batch", null)
    private val completion = CompleteTaskInput("task", "batch", ProductionTaskKind.MATING, "2026-10-09T09:00:00.123456789+08:00")
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "common-queue-${UUID.randomUUID()}"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    private fun conflict(reason: TaskQueueConflictReason, action: () -> Any) {
        try { action(); fail("Expected $reason") } catch (failure: TaskQueueConflict) { assertEquals(reason, failure.reason) }
    }
    @Test fun commonPayloadsKeepNullEmptyBatchKindAndTimeOnReopen() = database { queue, name ->
        val inputs = listOf(mark, mark.copy(pigId = "pig2", remark = ""), completion)
        inputs.forEachIndexed { n, input -> queue.enqueue(scope, "op$n", input, emptySet(), n.toLong()) }
        SqliteCommandQueue(context, name).use { restored -> assertEquals(inputs, restored.list(scope).map { it.input }) }
        val encoded = CommandPayloadCodec.encode(completion)
        for (bad in listOf(encoded.replace("MATING", "FUTURE"), encoded.replace("\"at\":\"2026", "\"at\":\"invalid2026"))) {
            try { CommandPayloadCodec.decode(2, bad); fail() } catch (_: RuntimeException) { }
        }
        assertEquals(setOf("pig:pig"), mark.groupKeys); assertEquals(setOf("taskComplete:task"), completion.groupKeys)
    }
    @Test fun completionWaitsForAllTaskPigsIncludingKnownFailureAndUnknownAttempt() = database { queue, _ ->
        val failed = queue.enqueue(scope, "failed", mark, emptySet(), 0)
        queue.finish(requireNotNull(queue.claim(queueContext, 1)), CommandOutcome.Rejected("SIMULATED"), 2)
        val unknown = queue.enqueue(scope, "unknown", mark.copy(pigId = "pig2"), emptySet(), 3)
        val attempt = requireNotNull(queue.claim(queueContext, 4)); assertEquals(unknown.record.id, attempt.record.id)
        queue.finish(attempt, CommandOutcome.Uncertain, 5)
        queue.enqueue(scope.copy(farmId = "other"), "foreign", mark, emptySet(), 0)
        val end = queue.enqueue(scope, "end", completion, emptySet(), 6)
        assertEquals(setOf(failed.record.id, unknown.record.id), end.record.dependsOn)
        assertNull(queue.claim(queueContext, 7)); queue.cancel(scope, failed.record.id, 8); assertNull(queue.claim(queueContext, 9))
        queue.finish(attempt, CommandOutcome.Success("simulated-confirmed"), 10)
        assertEquals(end.record.id, requireNotNull(queue.claim(queueContext, 11)).record.id)
    }
    @Test fun lateTaskWritesGrowCompletionBarrierPersistentlyDespiteClockReversal() = database { queue, name ->
        val end = queue.enqueue(scope, "end", completion, emptySet(), 0)
        val late = queue.enqueue(scope, "late", mark, emptySet(), 100)
        val claimed = requireNotNull(queue.claim(queueContext, 101)); assertEquals(late.record.id, claimed.record.id)
        SqliteCommandQueue(context, name).use { reopened ->
            val saved = reopened.list(scope).single { it.record.id == end.record.id }
            assertEquals(setOf(late.record.id), saved.record.dependsOn); assertTrue(saved.requestedDependencies.isEmpty())
            assertEquals(saved.record.id, reopened.enqueue(scope, "end", completion, emptySet(), -1).record.id)
            assertEquals(1, reopened.recoverInterrupted(scope, 102)); assertNull(reopened.claim(queueContext, 103))
            reopened.finish(claimed, CommandOutcome.Success("simulated-confirmed"), 104)
            assertEquals(end.record.id, requireNotNull(reopened.claim(queueContext, 105)).record.id)
            val events = reopened.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE command_id=? AND kind='TASK_DEPENDENCIES_EXTENDED'", arrayOf(end.record.id)).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, events)
        }
    }
    @Test fun completionStartedUnknownAndSyncedRefuseNewTaskWritesButKeepIdentityAndOtherTasks() = database { queue, _ ->
        val end = queue.enqueue(scope, "end", completion, emptySet(), 0)
        val attempt = requireNotNull(queue.claim(queueContext, 1))
        fun refused() {
            conflict(TaskQueueConflictReason.COMPLETION_STARTED) { queue.enqueue(scope, "late", mark, emptySet(), 2) }
            assertEquals(end.record.id, queue.enqueue(scope, "end", completion, emptySet(), 3).record.id)
        }
        refused(); queue.finish(attempt, CommandOutcome.Uncertain, 4); refused()
        queue.finish(attempt, CommandOutcome.Success("simulated"), 5); refused()
        queue.enqueue(scope, "other-task", mark.copy(taskId = "other"), emptySet(), 6)
        queue.enqueue(scope.copy(userId = "other"), "other-user", mark, emptySet(), 7)
    }
    @Test fun duplicateAndTransitiveCompletionDependenciesCannotCreateCycles() = database { queue, _ ->
        val end = queue.enqueue(scope, "end", completion, emptySet(), 0)
        conflict(TaskQueueConflictReason.DUPLICATE_COMPLETION) { queue.enqueue(scope, "second-end", completion, emptySet(), 1) }
        conflict(TaskQueueConflictReason.COMPLETION_DEPENDENCY) { queue.enqueue(scope, "direct", mark, setOf(end.record.id), 2) }
        val other = queue.enqueue(scope, "other", mark.copy(taskId = "other", pigId = "other-pig"), setOf(end.record.id), 3)
        conflict(TaskQueueConflictReason.COMPLETION_DEPENDENCY) { queue.enqueue(scope, "transitive", mark, setOf(other.record.id), 4) }
        queue.cancel(scope, end.record.id, 5)
        queue.enqueue(scope, "replacement", completion, emptySet(), 6)
        assertEquals(3, queue.list(scope).size)
    }
    @Test fun rejectedCompletionCanWaitForRepairAndOnlyRetryAfterConfirmation() = database { queue, _ ->
        val end = queue.enqueue(scope, "end", completion, emptySet(), 0)
        queue.finish(requireNotNull(queue.claim(queueContext, 1)), CommandOutcome.Rejected("SIMULATED"), 2)
        val repair = queue.enqueue(scope, "repair", mark, emptySet(), 3)
        conflict(TaskQueueConflictReason.DUPLICATE_COMPLETION) { queue.enqueue(scope, "another-end", completion, emptySet(), 4) }
        try { queue.retryAfterConfirmation(scope, end.record.id, 5, false); fail() } catch (_: IllegalStateException) { }
        queue.retryAfterConfirmation(scope, end.record.id, 5, true)
        val fix = requireNotNull(queue.claim(queueContext, 5006)); assertEquals(repair.record.id, fix.record.id)
        assertNull(queue.claim(queueContext, 5007)); queue.finish(fix, CommandOutcome.Success("simulated"), 5008)
        val retry = requireNotNull(queue.claim(queueContext, 5009)); assertEquals(end.record.id, retry.record.id); assertEquals(2, retry.record.attempts)
    }
    @Test fun lateLocationAggregateIsIncludedWithoutSplittingIntoPigCommands() = database { queue, _ ->
        val end = queue.enqueue(scope, "end", completion.copy(kind = ProductionTaskKind.NURSERY), emptySet(), 0)
        val input = CheckGrowingInput("task", "z.u.p", GrowingStage.NURSERY, GrowingAssessment(2, 0, 1, 1, java.math.BigDecimal.ZERO, 0), emptyList(), null)
        val location = queue.enqueue(scope, "location", input, emptySet(), 10)
        val claim = requireNotNull(queue.claim(queueContext, 11)); assertEquals(location.record.id, claim.record.id)
        assertEquals(setOf(location.record.id), queue.list(scope).single { it.record.id == end.record.id }.record.dependsOn)
        assertEquals(2, queue.list(scope).size)
    }
}
