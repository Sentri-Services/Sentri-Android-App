package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EstrusQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val input = CheckEstrusInput("task", "pig", EstrusStatus.IN_ESTRUS, "2026-10-09T08:00:00+08:00")
    private val lease = QueueContext(scope, 1, true, true)
    private fun database(test: (SqliteCommandQueue, String) -> Unit) {
        val name = "estrus-test-${UUID.randomUUID()}.db"
        try { SqliteCommandQueue(context, name).use { test(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun persistentSuccessDuplicatePayloadAndScopeIsolation() = database { store, name ->
        val first = store.enqueue(scope, "op", input, emptySet(), 1)
        assertEquals(first, store.enqueue(scope, "op", input, emptySet(), 1))
        try { store.enqueue(scope, "op", input.copy(status = EstrusStatus.NOT_IN_ESTRUS), emptySet(), 2); fail() } catch (_: IllegalArgumentException) { }
        val other = scope.copy(userId = "other")
        store.enqueue(other, "op", input, emptySet(), 1)
        runBlocking {
            var calls = 0
            val dispatcher = CommandDispatcher(store, object : CommandTransport {
                override suspend fun send(command: QueuedCommand): CommandOutcome { calls++; return CommandOutcome.Success("simulated-receipt") }
            }, { lease }) { 100 }
            assertTrue(dispatcher.drainOne()); assertFalse(dispatcher.drainOne()); assertEquals(1, calls)
        }
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(CommandStatus.SYNCED, reopened.list(scope).single().record.status)
            assertEquals("simulated-receipt", reopened.list(scope).single().receipt)
            assertEquals(CommandStatus.QUEUED, reopened.list(other).single().record.status)
        }
    }
    @Test fun responseLossRestartConfirmationAndStaleReceipt() = database { store, name ->
        store.enqueue(scope, "op", input, emptySet(), 1)
        val attempt = requireNotNull(store.claim(lease, 2))
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(1, reopened.recoverInterrupted(scope, 3))
            assertNull(reopened.claim(lease, 10000))
            try { reopened.retryAfterConfirmation(scope, attempt.record.id, 4, false); fail() } catch (_: IllegalStateException) { }
            // Confirmed late result resolves the same attempt without sending a second request.
            reopened.finish(attempt, CommandOutcome.Success("confirmed-result"), 5)
            assertEquals(CommandStatus.SYNCED, reopened.list(scope).single().record.status)
            try { reopened.finish(attempt, CommandOutcome.Uncertain, 6); fail() } catch (_: IllegalStateException) { }
        }
        store.enqueue(scope, "retry-op", input.copy(pigId = "pig2"), emptySet(), 10)
        val uncertain = requireNotNull(store.claim(lease, 11))
        store.finish(uncertain, CommandOutcome.Uncertain, 12)
        store.retryAfterConfirmation(scope, uncertain.record.id, 13, true)
        assertNull(store.claim(lease, 5012))
        val retry = requireNotNull(store.claim(lease, 5013))
        assertEquals(2, retry.record.attempts)
        try { store.finish(uncertain, CommandOutcome.Success("old-attempt"), 5014); fail() } catch (_: IllegalStateException) { }
        store.finish(retry, CommandOutcome.Success("retry-result"), 5015)
    }
    @Test fun conflictDependencyCancellationNetworkAndTransactionRollback() = database { store, _ ->
        val first = store.enqueue(scope, "a", input, emptySet(), 1)
        val second = store.enqueue(scope, "b", input.copy(pigId = "pig2"), setOf(first.record.id), 2)
        assertNull(store.claim(lease.copy(authenticated = false), 3))
        assertNull(store.claim(lease.copy(goodNetwork = false), 3))
        val attempt = requireNotNull(store.claim(lease, 3)); store.finish(attempt, CommandOutcome.Rejected("CONFLICT", true), 4)
        assertNull(store.claim(lease, 5)); store.cancel(scope, first.record.id, 6)
        assertEquals(second.record.id, requireNotNull(store.claim(lease, 7)).record.id)
        val before = store.list(scope).size
        store.writableDatabase.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON events WHEN NEW.kind='ENQUEUED' BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.enqueue(scope, "rollback", input, emptySet(), 8); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, store.list(scope).size)
        assertTrue(store.list(scope).none { it.record.operationId == "rollback" })
    }
    @Test fun interruptedTransportAndContextSwitchKeepOriginalOwnership() = database { store, _ -> runBlocking {
        val original = store.enqueue(scope, "op", input, emptySet(), 1)
        var current: QueueContext? = lease
        val dispatcher = CommandDispatcher(store, object : CommandTransport {
            override suspend fun send(command: QueuedCommand): CommandOutcome {
                current = lease.copy(scope = scope.copy(farmId = "other"), revision = 2)
                return CommandOutcome.Success("simulated-scope-receipt")
            }
        }, { current }) { 100 }
        assertTrue(dispatcher.drainOne())
        assertEquals("simulated-scope-receipt", store.list(scope).single().receipt)
        assertTrue(store.list(current!!.scope).isEmpty())
        assertFalse(dispatcher.drainOne())
        current = lease
        store.enqueue(scope, "cancelled", input.copy(pigId = "pig2"), emptySet(), 101)
        val cancelled = CommandDispatcher(store, object : CommandTransport {
            override suspend fun send(command: QueuedCommand): CommandOutcome = throw CancellationException("simulated cancellation")
        }, { current }) { 102 }
        try { cancelled.drainOne(); fail() } catch (_: CancellationException) { }
        assertEquals(CommandStatus.UNCERTAIN, store.list(scope).single { it.record.id != original.record.id }.record.status)
    } }
    @Test fun notSentDelayCorruptionAndAttemptJournalAreDurable() = database { store, name ->
        val command = store.enqueue(scope, "not-sent", input, emptySet(), 1)
        val attempt = requireNotNull(store.claim(lease, 2))
        store.finish(attempt, CommandOutcome.NotSent("NETWORK_PREFLIGHT"), 3)
        assertNull(store.claim(lease, 5002))
        SqliteCommandQueue(context, name).use { reopened ->
            val next = requireNotNull(reopened.claim(lease, 5003))
            assertEquals(2, next.record.attempts)
            reopened.finish(next, CommandOutcome.Rejected("BUSINESS_REJECTED"), 5004)
            reopened.readableDatabase.rawQuery("SELECT number,outcome,error_code FROM attempts WHERE command_id=? ORDER BY number", arrayOf(command.record.id)).use { c ->
                assertTrue(c.moveToNext()); assertEquals(1, c.getInt(0)); assertEquals("NOT_SENT", c.getString(1)); assertEquals("NETWORK_PREFLIGHT", c.getString(2))
                assertTrue(c.moveToNext()); assertEquals(2, c.getInt(0)); assertEquals("REJECTED", c.getString(1)); assertEquals("BUSINESS_REJECTED", c.getString(2))
                assertFalse(c.moveToNext())
            }
            reopened.writableDatabase.execSQL("UPDATE commands SET payload='{}' WHERE id=?", arrayOf(command.record.id))
            try { reopened.list(scope); fail("Corruption must stop dispatch") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun twoDatabaseOwnersCannotClaimTheSameAttemptAndFinishRollsBackWithJournal() = database { store, name ->
        store.enqueue(scope, "op", input, emptySet(), 1)
        val attempt = requireNotNull(store.claim(lease, 2))
        SqliteCommandQueue(context, name).use { second -> assertNull(second.claim(lease, 3)) }
        store.writableDatabase.execSQL("CREATE TRIGGER reject_outcome BEFORE INSERT ON events WHEN NEW.kind='CONFIRMED_SUCCESS' BEGIN SELECT RAISE(ABORT,'test rollback'); END")
        try { store.finish(attempt, CommandOutcome.Success("receipt"), 4); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(CommandStatus.IN_FLIGHT, store.list(scope).single().record.status)
        store.readableDatabase.rawQuery("SELECT finished_at FROM attempts WHERE command_id=?", arrayOf(attempt.record.id)).use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        store.writableDatabase.execSQL("DROP TRIGGER reject_outcome")
        store.finish(attempt, CommandOutcome.Success("receipt"), 5)
        assertEquals(CommandStatus.SYNCED, store.list(scope).single().record.status)
    }

}
