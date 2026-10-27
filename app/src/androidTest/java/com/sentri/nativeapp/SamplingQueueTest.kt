package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.sampling.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SamplingQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "sampling-${UUID.randomUUID()}"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun nullAndEmptyRemarkRemainDifferentAcrossReopen() = database { queue, name ->
        val first = SubmitSampledInput("task-a", null)
        val second = SubmitSampledInput("task-b", "")
        queue.enqueue(scope, "a", first, emptySet(), 1)
        queue.enqueue(scope, "b", second, emptySet(), 2)
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(listOf(first, second), reopened.list(scope).map { it.input })
            assertTrue(reopened.list(scope).all { it.record.dependsOn.isEmpty() })
            val wrongScope = scope.copy(farmId = "other")
            assertTrue(reopened.list(wrongScope).isEmpty())
        }
    }
    @Test fun unknownResultBlocksRetryAndReceiptMustMatchTask() = database { queue, _ ->
        val input = SubmitSampledInput("task", "原始备注")
        val saved = queue.enqueue(scope, "submit", input, emptySet(), 1)
        try { queue.enqueue(scope, "second", input, emptySet(), 2); fail() }
        catch (failure: SamplingQueueConflict) { assertEquals(SamplingQueueConflictReason.DUPLICATE_SUBMISSION, failure.reason) }
        val attempt = queue.claim(lease, 3)!!; assertEquals(saved.record.id, attempt.record.id)
        for (receipt in listOf("{}", SamplingWriteReceipt("SubmitSampled", "other").encode())) {
            try { queue.finish(attempt, CommandOutcome.Success(receipt), 4); fail() } catch (_: Exception) { }
            assertEquals(CommandStatus.IN_FLIGHT, queue.list(scope).single().record.status)
        }
        queue.finish(attempt, CommandOutcome.Uncertain, 5)
        assertNull(queue.claim(lease, 6))
        try { queue.enqueue(scope, "second", input, emptySet(), 7); fail() }
        catch (failure: SamplingQueueConflict) { assertEquals(SamplingQueueConflictReason.RESULT_UNCONFIRMED, failure.reason) }
        queue.finish(attempt, CommandOutcome.Success(SamplingWriteReceipt("SubmitSampled", "task").encode()), 8)
        assertEquals(CommandStatus.SYNCED, queue.list(scope).single().record.status)
        assertNull(queue.claim(lease, 9))
    }
    @Test fun strictPayloadRejectsExtraFieldsAndTypeChanges() {
        val encoded = CommandPayloadCodec.encode(SubmitSampledInput("task", null))
        assertEquals(SubmitSampledInput("task", null), CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, encoded))
        for (bad in listOf(encoded.replace("\"remark\":null", "\"remark\":1"),
            encoded.replace("\"task\":\"task\"", "\"task\":\"\""),
            encoded.replace("\"remark\":null", "\"remark\":null,\"extra\":1"))) {
            try { CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, bad); fail() } catch (_: Exception) { }
        }
    }
}
