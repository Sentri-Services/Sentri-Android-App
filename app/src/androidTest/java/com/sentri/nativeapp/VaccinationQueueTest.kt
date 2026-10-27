package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.vaccination.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VaccinationQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "vaccination-${UUID.randomUUID()}"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    private fun receipt(input: VaccinationCommandInput) = VaccinationWriteReceipt(input.operationName, input.vaccinationTaskId).encode()

    @Test fun payloadRejectsInvalidTimeTargetsAndExtraFields() {
        val input = ExecuteVaccinationTaskInput("task", listOf("B", "A", "B"), null)
        val encoded = CommandPayloadCodec.encode(input)
        assertEquals(input, CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, encoded))
        assertTrue(encoded.contains("\"at\":null"))
        for (bad in listOf(encoded.replace("\"at\":null", "\"at\":1"),
            encoded.replace("\"targets\":[\"B\",\"A\",\"B\"]", "\"targets\":[\"B\",1]"),
            encoded.replace("\"task\":\"task\"", "\"task\":\"\""),
            encoded.replace("\"at\":null", "\"at\":null,\"extra\":1"))) {
            assertNotEquals(encoded, bad)
            try { CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, bad); fail() } catch (_: Exception) { }
        }
    }
    @Test fun twoDosesAndFinishRestoreWithExactPayloadAndDependencies() = database { queue, name ->
        val first = ExecuteVaccinationTaskInput("task", listOf("pig-b", "pig-a", "pig-b"), at)
        val second = ExecuteVaccinationTaskInput("task", listOf("pig-a"), null)
        val a = queue.enqueue(scope, "dose-1", first, emptySet(), 100)
        val b = queue.enqueue(scope, "dose-2", second, emptySet(), 0)
        val finish = queue.enqueue(scope, "finish", FinishVaccinationTaskInput("task", null), emptySet(), -1)
        assertEquals(setOf(a.record.id, b.record.id), finish.record.dependsOn)
        SqliteCommandQueue(context, name).use { reopened ->
            val saved = reopened.list(scope)
            assertEquals(listOf(first, second, FinishVaccinationTaskInput("task", null)), saved.map { it.input }.sortedBy {
                when (it) { is ExecuteVaccinationTaskInput -> if (it.targetIds.size > 1) 0 else 1; else -> 2 }
            })
            assertEquals(setOf("vaccination:task", "pig:pig-a", "pig:pig-b"), saved.single { it.record.id == a.record.id }.record.groups)
            assertEquals(a.record.id, reopened.claim(lease, 101)?.record?.id)
        }
    }
    @Test fun unknownDoseBlocksFinishAndWrongReceiptCannotAcknowledge() = database { queue, _ ->
        val input = ExecuteVaccinationTaskInput("task", listOf("pig"), at)
        val first = queue.enqueue(scope, "dose", input, emptySet(), 0)
        queue.enqueue(scope, "finish", FinishVaccinationTaskInput("task", at), emptySet(), 1)
        val attempt = queue.claim(lease, 2)!!; assertEquals(first.record.id, attempt.record.id)
        for (bad in listOf("{}", VaccinationWriteReceipt("FinishVaccinationTask", "task").encode(),
            VaccinationWriteReceipt("ExecuteVaccinationTask", "other").encode())) {
            try { queue.finish(attempt, CommandOutcome.Success(bad), 3); fail() } catch (_: Exception) { }
            assertEquals(CommandStatus.IN_FLIGHT, queue.list(scope).single { it.record.id == first.record.id }.record.status)
        }
        queue.finish(attempt, CommandOutcome.Uncertain, 4); assertNull(queue.claim(lease, 5))
        queue.finish(attempt, CommandOutcome.Success(receipt(input)), 6)
        val next = queue.claim(lease, 7)!!; assertEquals("finish", next.record.operationId)
        queue.finish(next, CommandOutcome.Success(receipt(next.input as VaccinationCommandInput)), 8)
        assertNull(queue.claim(lease, 9))
    }
    @Test fun finishBarrierRejectsLaterDoseButCancelledFinishAllowsItAndOtherTaskRemainsIndependent() = database { queue, _ ->
        val finish = queue.enqueue(scope, "finish", FinishVaccinationTaskInput("task", at), emptySet(), 0)
        try { queue.enqueue(scope, "dose", ExecuteVaccinationTaskInput("task", listOf("pig"), at), emptySet(), 1); fail() }
        catch (failure: VaccinationQueueConflict) { assertEquals(VaccinationQueueConflictReason.FINISH_PENDING, failure.reason) }
        val other = queue.enqueue(scope, "other", ExecuteVaccinationTaskInput("other-task", listOf("pig"), at), emptySet(), 1)
        assertTrue(other.record.dependsOn.isEmpty())
        queue.cancel(scope, finish.record.id, 2)
        assertTrue(queue.enqueue(scope, "dose", ExecuteVaccinationTaskInput("task", listOf("pig"), at), emptySet(), 3).record.dependsOn.isEmpty())
    }
}
