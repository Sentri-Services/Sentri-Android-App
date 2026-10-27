package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TaskCommonProcessRestoreTest {
    @Test fun lateCompletionDependenciesAndCatalogSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("taskCommonStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "task-common-process-test"; val prefs = context.getSharedPreferences(name, 0)
        val f = TaskCatalogFixture; val queueContext = QueueContext(f.scope, 1, true, true)
        val completion = CompleteTaskInput("task", "batch", ProductionTaskKind.MATING, f.at)
        val mark = MarkTaskPigInput("task", "pig", "batch", "RemarkCase")
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-cache"); prefs.edit().clear().commit()
            check(prefs.edit().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, "$name-cache").use { cache ->
                val reader = TaskCatalogReads(f.remote(), cache, { }); reader.metadata(f.lease, "task"); reader.page(f.lease, f.request)
            }
            SqliteCommandQueue(context, name).use { queue ->
                queue.enqueue(f.scope, "end", completion, emptySet(), 0)
                val late = queue.enqueue(f.scope, "late", mark, emptySet(), 10)
                assertEquals(late.record.id, requireNotNull(queue.claim(queueContext, 11)).record.id)
                assertEquals(setOf(late.record.id), queue.list(f.scope).single { it.record.operationId == "end" }.record.dependsOn)
            }
            println("TASK_COMMON_PREPARED commands=2 metadata=1 pages=1 lateMark=IN_FLIGHT barrierPersisted=true")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, "$name-cache").use { cache ->
                    val reader = TaskCatalogReads(f.remote(ApiFailure.Network()), cache, { })
                    assertEquals(f.metadata, reader.metadata(f.lease, "task").value); assertEquals(f.page, reader.page(f.lease, f.request).value)
                }
                SqliteCommandQueue(context, name).use { queue ->
                    val rows = queue.list(f.scope); val late = rows.single { it.record.operationId == "late" }; val end = rows.single { it.record.operationId == "end" }
                    assertEquals(mark, late.input); assertEquals(completion, end.input); assertEquals(setOf(late.record.id), end.record.dependsOn)
                    assertEquals(1, queue.recoverInterrupted(f.scope, 12)); assertNull(queue.claim(queueContext, 13))
                    var sends = 0
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport { override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") } }, { queueContext }) { 14 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    queue.finish(late, CommandOutcome.Success("simulated-confirmed-mark"), 15)
                    val started = requireNotNull(queue.claim(queueContext, 16)); assertEquals(end.record.id, started.record.id)
                    try { queue.enqueue(f.scope, "too-late", mark.copy(pigId = "pig2"), emptySet(), 17); fail() }
                    catch (failure: TaskQueueConflict) { assertEquals(TaskQueueConflictReason.COMPLETION_STARTED, failure.reason) }
                }
                println("TASK_COMMON_RESTORED newProcess=true commands=2 metadata=1 pages=1 decimalExact=true rawJsonExact=true unconfirmedResends=0 completionBlockedUntilConfirmation=true")
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-cache"); prefs.edit().clear().commit() }
        }
    }
}
