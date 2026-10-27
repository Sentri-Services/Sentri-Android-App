package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import com.sentri.nativeapp.therapy.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object TherapyFixture {
    val scope = TaskCatalogFixture.scope
    val ctx = QueueContext(scope, 1, true, true)
    val at = "2026-10-10T08:00:00.1234567890123+08:00"
    val complete = CompleteTherapyTargetInput("p", "t1", "scheduled1", listOf("B", "A", "B"), at)
    val skip = SkipTherapyTaskInput("p", "t2", "scheduled2", listOf("A"), "")
    val targets = listOf(PrescriptionTargetInput("B", "", null, listOf("s2", "s1"), listOf("d")), PrescriptionTargetInput("A", null, "pen"), PrescriptionTargetInput("B"))
    val inputs = listOf<TherapyCommandInput>(complete, skip, AddPrescriptionTargetInput("p", targets, null),
        RemovePrescriptionTargetInput("p", targets, ""), TerminateTherapyInput("p", "t1", null), TerminatePrescriptionInput("p", ""))
    val steps = listOf<TherapyCommandInput>(complete, skip, TerminatePrescriptionInput("p", null))
    fun receipt(input: TherapyCommandInput) = TherapyWriteReceipt(input.operationName, when (input) {
        is CompleteTherapyTargetInput -> input.therapyTaskId
        is SkipTherapyTaskInput -> input.therapyTaskId
        else -> input.prescriptionId
    }).encode()
}
class TherapyQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = TherapyFixture
    private fun database(block: suspend (SqliteCommandQueue, String) -> Unit) = runBlocking {
        val name = "therapy-${UUID.randomUUID()}"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun sixTypedPayloadsRestoreExactContextOrderedDuplicateTargetsNullAndEmptyValues() = database { queue, name ->
        f.inputs.forEachIndexed { i, input -> queue.enqueue(f.scope, "op-$i", input, emptySet(), i.toLong()) }
        SqliteCommandQueue(context, name).use { reopened ->
            val all = reopened.list(f.scope); assertEquals(f.inputs, all.map { it.input })
            assertEquals(listOf("B", "A", "B"), (all.first().input as CompleteTherapyTargetInput).targetIds)
            assertEquals(f.at, (all.first().input as CompleteTherapyTargetInput).completedAt)
            assertEquals(f.targets, (all[2].input as AddPrescriptionTargetInput).targets)
            assertEquals(setOf("prescription:p", "therapy:t1", "pig:B", "pig:A"), all.first().record.groups)
            assertEquals(all.take(5).map { it.record.id }.toSet(), all.last().record.dependsOn)
        }
    }
    @Test fun unknownEarlierTherapyBlocksPrescriptionTerminationAndSiblingTherapyButOtherPrescriptionCanRun() = database { queue, _ ->
        val first = queue.enqueue(f.scope, "first", f.complete, emptySet(), 100)
        queue.enqueue(f.scope, "skip", f.skip, emptySet(), 0)
        queue.enqueue(f.scope, "stop", TerminatePrescriptionInput("p", null), emptySet(), -1)
        queue.enqueue(f.scope, "other", SkipTherapyTaskInput("other", "other-t", "other-task", listOf("C"), null), emptySet(), 101)
        val attempt = queue.claim(f.ctx, 200)!!; assertEquals(first.record.id, attempt.record.id)
        queue.finish(attempt, CommandOutcome.Uncertain, 201)
        val other = queue.claim(f.ctx, 202)!!; assertEquals("other", other.record.operationId)
        queue.finish(other, CommandOutcome.Success(f.receipt(other.input as TherapyCommandInput)), 203)
        assertNull(queue.claim(f.ctx, 204))
        queue.finish(attempt, CommandOutcome.Success(f.receipt(f.complete)), 205)
        assertEquals("skip", queue.claim(f.ctx, 206)?.record?.operationId)
    }
    @Test fun partialBatchSuccessSurvivesReopenAndRetryDoesNotReplaySuccessfulSteps() = database { queue, name ->
        val steps = queue.enqueueTherapySteps(f.scope, "batch:with:colon", f.steps, emptySet(), 0)
        val first = queue.claim(f.ctx, 1)!!; queue.finish(first, CommandOutcome.Success(f.receipt(f.complete)), 2)
        val second = queue.claim(f.ctx, 3)!!; queue.finish(second, CommandOutcome.Rejected("SIMULATED"), 4)
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(steps.map { it.record.id }, reopened.enqueueTherapySteps(f.scope, "batch:with:colon", f.steps, emptySet(), 999).map { it.record.id })
            assertEquals(f.receipt(f.complete), reopened.list(f.scope).single { it.record.id == first.record.id }.receipt); assertNull(reopened.claim(f.ctx, 5))
            reopened.retryAfterConfirmation(f.scope, second.record.id, 6, true)
            val retry = reopened.claim(f.ctx, 5006)!!; assertEquals(second.record.id, retry.record.id); assertEquals(2, retry.record.attempts)
            reopened.finish(retry, CommandOutcome.Success(f.receipt(f.skip)), 5007)
            val third = reopened.claim(f.ctx, 5008)!!; assertEquals(steps.last().record.id, third.record.id)
            reopened.finish(third, CommandOutcome.Success(f.receipt(f.steps.last())), 5009)
            assertNull(reopened.claim(f.ctx, 5010)); assertEquals(listOf(1, 2, 1), steps.map { step -> reopened.list(f.scope).single { it.record.id == step.record.id }.record.attempts })
        }
    }
    @Test fun batchReuseChecksAllStepsCountOrderAndCallerDependenciesAtomically() = database { queue, _ ->
        val saved = queue.enqueueTherapySteps(f.scope, "batch", f.steps, emptySet(), 0)
        for (changed in listOf(f.steps.dropLast(1), f.steps + f.steps.last(), f.steps.reversed(), f.steps.toMutableList().apply { this[1] = f.skip.copy(reason = null) })) {
            try { queue.enqueueTherapySteps(f.scope, "batch", changed, emptySet(), 1); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(saved.associateBy { it.record.id }, queue.list(f.scope).associateBy { it.record.id })
        }
        try { queue.enqueueTherapySteps(f.scope, "batch", f.steps, setOf("different-dependency"), 1); fail() } catch (_: IllegalArgumentException) { }
        // A partial namespace collision cannot be mistaken for the original batch.
        val collision = queue.enqueue(f.scope, "therapy-step:3:new:1", f.skip, emptySet(), 2)
        try { queue.enqueueTherapySteps(f.scope, "new", f.steps, emptySet(), 3); fail() } catch (_: IllegalArgumentException) { }
        assertEquals((saved + collision).associateBy { it.record.id }, queue.list(f.scope).associateBy { it.record.id })
        queue.writableDatabase.execSQL("CREATE TRIGGER therapy_batch_abort BEFORE INSERT ON commands WHEN NEW.operation_id='therapy-step:6:atomic:1' BEGIN SELECT RAISE(ABORT,'fixture failure after first insert'); END")
        try { queue.enqueueTherapySteps(f.scope, "atomic", f.steps, emptySet(), 4); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals((saved + collision).associateBy { it.record.id }, queue.list(f.scope).associateBy { it.record.id })
        queue.writableDatabase.execSQL("DROP TRIGGER therapy_batch_abort")
    }
    @Test fun malformedPayloadOrWrongReceiptCannotBeCoercedIntoSuccessOrAdvanceDependencies() = database { queue, _ ->
        val encoded = CommandPayloadCodec.encode(f.complete)
        val encodedTarget = CommandPayloadCodec.encode(f.inputs[2])
        val invalid = listOf(encoded.replace(f.at, "bad"), encoded.replace("\"task\":\"scheduled1\"", "\"task\":false"),
            encoded.replace("\"targets\":[\"B\",\"A\",\"B\"]", "\"targets\":[1]"),
            JSONObject(encoded).apply { getJSONObject("input").put("extra", true) }.toString(),
            encodedTarget.replace("\"symptoms\":[\"s2\",\"s1\"]", "\"symptoms\":null"))
        for (bad in invalid) { assertTrue(bad != encoded && bad != encodedTarget); try { CommandPayloadCodec.decode(2, bad); fail() } catch (_: Exception) { } }
        queue.enqueue(f.scope, "first", f.complete, emptySet(), 0); val attempt = queue.claim(f.ctx, 1)!!
        for (receipt in listOf("{}", TherapyWriteReceipt("SkipTherapyTask", "scheduled1").encode(), TherapyWriteReceipt("CompleteTherapyTarget", "other").encode())) {
            try { queue.finish(attempt, CommandOutcome.Success(receipt), 2); fail() } catch (_: Exception) { }
            assertEquals(CommandStatus.IN_FLIGHT, queue.list(f.scope).single().record.status)
        }
        queue.finish(attempt, CommandOutcome.Success(f.receipt(f.complete)), 3)
    }
    @Test fun therapyTargetsRespectProductionPigOrderingAndAllFourScopeComponentsStayIndependent() = database { queue, _ ->
        val production = queue.enqueue(f.scope, "production", CheckEstrusInput("e", "A", EstrusStatus.IN_ESTRUS, f.at), emptySet(), 0)
        queue.enqueue(f.scope, "therapy", f.complete, emptySet(), 1)
        val first = queue.claim(f.ctx, 2)!!; assertEquals(production.record.id, first.record.id)
        queue.finish(first, CommandOutcome.Uncertain, 3); assertNull(queue.claim(f.ctx, 4))
        for (scope in listOf(f.scope.copy(environment = "other"), f.scope.copy(oauthId = "other"), f.scope.copy(userId = "other"), f.scope.copy(farmId = "other"))) {
            val value = queue.enqueue(scope, "therapy", f.complete, emptySet(), 1)
            assertTrue(value.record.dependsOn.isEmpty()); assertEquals(value.record.id, queue.claim(f.ctx.copy(scope = scope), 5)?.record?.id)
        }
        assertNull(queue.claim(f.ctx.copy(authenticated = false), 5)); assertNull(queue.claim(f.ctx.copy(goodNetwork = false), 5))
    }
}
