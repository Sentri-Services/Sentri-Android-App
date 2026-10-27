package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.production.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UnitRoundQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private fun checks(pregnancy: Boolean, pig: String = "pig", unit: String = "rawUnit.*"): UnitRoundInput = if (pregnancy)
        CheckPregnancyInput("task", pig, PregnancyStatus.NOT_SURE, "2026-10-09T01:00:00.123456Z", 3, unit)
        else CheckReEstrusInput("task", pig, ReEstrusStatus.SIGNS_OF_RE_ESTRUS, null, unit)
    private fun submission(pregnancy: Boolean, unit: String = "rawUnit.*"): UnitRoundInput = if (pregnancy)
        SubmitPregnancyInput("task", listOf("pig"), null, unit) else SubmitReEstrusInput("task", listOf("pig"), null, unit)
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "round-${UUID.randomUUID()}.db"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun eachModuleCreatesUnitRoundBarrierNotJustSelectedPigDependencies() {
        for (pregnancy in listOf(false, true)) database { store, name ->
            val first = store.enqueue(scope, "check", checks(pregnancy), emptySet(), 1)
            val otherPig = store.enqueue(scope, "unselected-check", checks(pregnancy, "unselected"), emptySet(), 2)
            val batch = store.enqueue(scope, "submit", submission(pregnancy), emptySet(), 3)
            val next = store.enqueue(scope, "next-round", checks(pregnancy), emptySet(), 4)
            assertEquals(setOf(first.record.id, otherPig.record.id), batch.record.dependsOn)
            assertEquals(setOf(batch.record.id), next.record.dependsOn)
            SqliteCommandQueue(context, name).use { reopened ->
                assertEquals(checks(pregnancy), reopened.list(scope).first().input)
                assertEquals(submission(pregnancy), reopened.list(scope).single { it.record.id == batch.record.id }.input)
                val attempt = requireNotNull(reopened.claim(lease, 5))
                reopened.finish(attempt, CommandOutcome.Uncertain, 6)
                // Independent pig check is allowed, but the unit submit and next round remain blocked.
                val independent = requireNotNull(reopened.claim(lease, 7))
                assertEquals(otherPig.record.id, independent.record.id)
                reopened.finish(independent, CommandOutcome.Success("other-check"), 8)
                assertNull(reopened.claim(lease, 9))
                reopened.finish(attempt, CommandOutcome.Success("confirmed-check"), 10)
                assertEquals(batch, reopened.enqueue(scope, "submit", submission(pregnancy), emptySet(), 999))
                val submitAttempt = requireNotNull(reopened.claim(lease, 11))
                assertEquals(batch.record.id, submitAttempt.record.id)
                reopened.finish(submitAttempt, CommandOutcome.Uncertain, 12)
                assertNull(reopened.claim(lease, 13))
                reopened.finish(submitAttempt, CommandOutcome.Success("confirmed-submit"), 14)
                assertEquals(next.record.id, requireNotNull(reopened.claim(lease, 15)).record.id)
            }
        }
    }
    @Test fun dependencyInferenceKeepsUnitModuleTaskAndScopeBoundaries() = database { store, _ ->
        val mine = store.enqueue(scope, "mine", checks(true, "pig", ""), emptySet(), 1)
        store.enqueue(scope, "other-unit", checks(true, "other", "unit"), emptySet(), 2)
        store.enqueue(scope, "other-module", checks(false, "other", ""), emptySet(), 3)
        store.enqueue(scope, "other-task", (checks(true, "other", "") as CheckPregnancyInput).copy(taskId = "other-task"), emptySet(), 4)
        store.enqueue(scope.copy(userId = "other-user"), "other-scope", checks(true, "pig", ""), emptySet(), 1)
        val batch = store.enqueue(scope, "submit", submission(true, ""), emptySet(), 5)
        assertEquals(setOf(mine.record.id), batch.record.dependsOn)
        val otherBatch = store.enqueue(scope, "submit2", submission(true, ""), emptySet(), 6)
        assertEquals(setOf(mine.record.id, batch.record.id), otherBatch.record.dependsOn)
    }
    @Test fun explicitRoundDependenciesWinWhenClockMovesBackwards() = database { store, _ ->
        val first = store.enqueue(scope, "first", checks(false), emptySet(), 100)
        val batch = store.enqueue(scope, "submit", submission(false), emptySet(), 90)
        val next = store.enqueue(scope, "next", checks(false), emptySet(), 80)
        assertEquals(setOf(first.record.id), batch.record.dependsOn)
        assertEquals(setOf(batch.record.id), next.record.dependsOn)
        var attempt = requireNotNull(store.claim(lease, 200)); assertEquals(first.record.id, attempt.record.id)
        store.finish(attempt, CommandOutcome.Success("first"), 201)
        attempt = requireNotNull(store.claim(lease, 202)); assertEquals(batch.record.id, attempt.record.id)
        store.finish(attempt, CommandOutcome.Success("submit"), 203)
        assertEquals(next.record.id, requireNotNull(store.claim(lease, 204)).record.id)
    }
    @Test fun integerNullZeroAndRangeAreDurableAndCoercionIsRejected() {
        for (days in listOf(null, 0, 3, Int.MAX_VALUE)) {
            val input = CheckPregnancyInput("task", "pig", PregnancyStatus.NOT_SURE, null, days, "unit")
            assertEquals(input, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(input)))
        }
        val input = CheckPregnancyInput("task", "pig", PregnancyStatus.NOT_SURE, null, 3, "unit")
        for (value in listOf("3.5", "2147483648", "\"3\"")) {
            val bad = CommandPayloadCodec.encode(input).replace("\"days\":3", "\"days\":$value")
            try { CommandPayloadCodec.decode(2, bad); fail() } catch (_: RuntimeException) { }
        }
    }
}
