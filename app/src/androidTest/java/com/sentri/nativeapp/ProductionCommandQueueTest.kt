package com.sentri.nativeapp

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.production.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class ProductionCommandQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private val input = CheckEstrusInput("task", "pig", EstrusStatus.IN_ESTRUS, null)
    private val submit = SubmitEstrusInput("task", listOf("pig", "pig2"), "2026-10-09T01:00:00.123456Z")
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "production-${UUID.randomUUID()}.db"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun submitInfersScopedChecksAndRetainsDependencyAcrossReopenAndConfirmation() = database { store, name ->
        val first = store.enqueue(scope, "check", input, emptySet(), 1)
        val second = store.enqueue(scope, "check2", input.copy(pigId = "pig2"), emptySet(), 2)
        store.enqueue(scope.copy(farmId = "other"), "check", input, emptySet(), 1)
        val batch = store.enqueue(scope, "submit", submit, emptySet(), 3)
        assertEquals(setOf(first.record.id, second.record.id), batch.record.dependsOn)
        assertEquals(setOf("task:task", "pig:pig", "pig:pig2"), batch.record.groups)
        assertEquals("SubmitEstrusStatus", batch.record.operationName)
        val attempt = requireNotNull(store.claim(lease, 4))
        store.finish(attempt, CommandOutcome.Uncertain, 5)
        assertNull(store.claim(lease, 6))
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(submit, reopened.list(scope).single { it.record.operationId == "submit" }.input)
            assertNull(reopened.claim(lease, 7))
            reopened.finish(attempt, CommandOutcome.Success("confirmed-check"), 8)
            // Repeated enqueue must keep its original dependencies after an inferred check is resolved.
            assertEquals(batch, reopened.enqueue(scope, "submit", submit, emptySet(), 99))
            val check2 = requireNotNull(reopened.claim(lease, 9))
            assertEquals(second.record.id, check2.record.id)
            reopened.finish(check2, CommandOutcome.Success("confirmed-check2"), 10)
            val submitted = requireNotNull(reopened.claim(lease, 11))
            assertEquals(batch.record.id, submitted.record.id)
            reopened.finish(submitted, CommandOutcome.Success("confirmed-submit"), 12)
            assertEquals(CommandStatus.SYNCED, reopened.list(scope).last().record.status)
        }
    }
    @Test fun unrelatedChecksDoNotBecomeSubmitDependenciesAndPayloadReuseIsRejected() = database { store, _ ->
        val otherTask = store.enqueue(scope, "other-task", input.copy(taskId = "other"), emptySet(), 1)
        val otherPig = store.enqueue(scope, "other-pig", input.copy(pigId = "not-selected"), emptySet(), 2)
        val batch = store.enqueue(scope, "submit", submit, setOf(otherTask.record.id), 3)
        assertEquals(setOf(otherTask.record.id), batch.record.dependsOn)
        assertFalse(batch.record.dependsOn.contains(otherPig.record.id))
        try { store.enqueue(scope, "submit", submit, emptySet(), 4); fail() } catch (_: IllegalArgumentException) { }
        try { store.enqueue(scope, "submit", submit.copy(autoEstrus = true), setOf(otherTask.record.id), 4); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun typedPayloadRejectsUnknownOperationsFieldsAndCoercedValues() {
        assertEquals(input, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(input)))
        assertEquals(submit, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(submit)))
        val boar = MateWithBoarInput("mating-task", "pig", "boar")
        val semen = MateWithSemenInput("mating-task", "pig", "SemenCase:A")
        assertEquals(boar, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(boar)))
        assertEquals(semen, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(semen)))
        val bad = listOf(
            """{"operation":"FutureCommand","input":{}}""",
            """{"operation":"SubmitEstrusStatus","input":{"task":"task","pigs":[1],"at":null,"autoEstrus":false}}""",
            """{"operation":"SubmitEstrusStatus","input":{"task":"task","pigs":["pig"],"autoEstrus":false}}""",
            """{"operation":"SubmitEstrusStatus","input":{"task":"task","pigs":["pig"],"at":null,"autoEstrus":"false"}}""",
            """{"operation":"CheckEstrus","input":{"task":"task","pig":"pig","status":"IN_ESTRUS","at":null},"token":"forbidden"}""",
        )
        bad.forEach { try { CommandPayloadCodec.decode(2, it); fail("Malformed payload accepted") } catch (_: RuntimeException) { } }
        try { CommandPayloadCodec.decode(99, CommandPayloadCodec.encode(input)); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun matingStaysBlockedByUncertainEstrusAcrossDifferentTasksAndRetainsBothPayloads() = database { store, name ->
        store.enqueue(scope, "check", input, emptySet(), 1)
        store.enqueue(scope, "submit", submit.copy(pigIds = listOf("pig")), emptySet(), 2)
        val boarInput = MateWithBoarInput("mating-task", "pig", "boar")
        val boar = store.enqueue(scope, "boar", boarInput, emptySet(), 3)
        val semenInput = MateWithSemenInput("mating-task", "pig", "SemenCase:A")
        val semen = store.enqueue(scope, "semen", semenInput, emptySet(), 4)
        val attempt = requireNotNull(store.claim(lease, 5))
        store.finish(attempt, CommandOutcome.Uncertain, 6)
        SqliteCommandQueue(context, name).use { reopened ->
            assertNull(reopened.claim(lease, 7))
            assertEquals(boarInput, reopened.list(scope).single { it.record.id == boar.record.id }.input)
            assertEquals(semenInput, reopened.list(scope).single { it.record.id == semen.record.id }.input)
            reopened.finish(attempt, CommandOutcome.Success("check-result"), 8)
            val batch = requireNotNull(reopened.claim(lease, 9))
            assertEquals("SubmitEstrusStatus", batch.record.operationName)
            reopened.finish(batch, CommandOutcome.Success("submit-result"), 10)
            val mating = requireNotNull(reopened.claim(lease, 11))
            assertEquals(boar.record.id, mating.record.id)
            reopened.finish(mating, CommandOutcome.Rejected("BUSINESS_REJECTED"), 12)
            assertNull(reopened.claim(lease, 13))
            reopened.cancel(scope, mating.record.id, 14)
            assertEquals(semen.record.id, requireNotNull(reopened.claim(lease, 15)).record.id)
        }
    }
    @Test fun legacyDatabaseUpgradeKeepsPayloadDependenciesAttemptsAndUncertainRecovery() {
        val name = "legacy-${UUID.randomUUID()}.db"
        val encoded = """{"task":"task","pig":"pig","status":"IN_ESTRUS","at":null}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            object : SQLiteOpenHelper(context, name, null, 1) {
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Legacy fixture")
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE commands (id TEXT PRIMARY KEY, environment TEXT NOT NULL, oauth TEXT NOT NULL, user_id TEXT NOT NULL, farm TEXT NOT NULL, operation_id TEXT NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL, format INTEGER NOT NULL, dependencies TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, retry_at INTEGER, receipt TEXT, UNIQUE(environment,oauth,user_id,farm,operation_id))")
                    db.execSQL("CREATE TABLE attempts (command_id TEXT NOT NULL REFERENCES commands(id), number INTEGER NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, outcome TEXT, error_code TEXT, PRIMARY KEY(command_id,number))")
                    db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL REFERENCES commands(id), at INTEGER NOT NULL, kind TEXT NOT NULL)")
                }
            }.use { old ->
                old.writableDatabase.insertOrThrow("commands", null, ContentValues().apply {
                    put("id", "legacy"); put("environment", scope.environment); put("oauth", scope.oauthId); put("user_id", scope.userId); put("farm", scope.farmId)
                    put("operation_id", "legacy-op"); put("payload", encoded); put("digest", digest); put("format", 1); put("dependencies", "[]")
                    put("created_at", 1); put("status", "IN_FLIGHT"); put("attempts", 1)
                })
                old.writableDatabase.execSQL("INSERT INTO attempts(command_id,number,started_at) VALUES('legacy',1,2)")
                old.writableDatabase.execSQL("INSERT INTO events(command_id,at,kind) VALUES('legacy',2,'STARTED')")
            }
            SqliteCommandQueue(context, name).use { upgraded ->
                assertEquals(2, upgraded.readableDatabase.version)
                assertEquals(input, upgraded.list(scope).single().input)
                assertEquals(1, upgraded.recoverInterrupted(scope, 3))
                assertNull(upgraded.claim(lease, 10000))
                assertEquals(CommandStatus.UNCERTAIN, upgraded.list(scope).single().record.status)
                val batch = upgraded.enqueue(scope, "new-submit", submit, emptySet(), 4)
                assertEquals(setOf("legacy"), batch.record.dependsOn)
                upgraded.readableDatabase.rawQuery("SELECT outcome FROM attempts WHERE command_id='legacy'", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("UNCERTAIN", c.getString(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
