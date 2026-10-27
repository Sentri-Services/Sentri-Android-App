package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProjectionProcessRestoreTest {
    @Test fun serverSnapshotsAndQueueRebuildPreviewAfterProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("projectionStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "projection-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = ProjectionDeviceFixture
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-queue"); prefs.edit().clear().commit()
            check(prefs.edit().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                f.reader(cache, queue, false).task(f.f.lease, "e")
                queue.enqueue(f.f.scope, "check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, f.f.at), emptySet(), 0)
                queue.enqueue(f.f.scope, "submit", SubmitEstrusInput("e", listOf("pig"), f.f.at), emptySet(), 1)
                val pending = f.reader(cache, queue, true).task(f.f.lease, "e", true)
                assertEquals("pig", (pending.projection.previewTasks["m"] as MatingTask).pigs.single().id)
                queue.claim(QueueContext(f.f.scope, 1, true, true), 2)
            } }
            println("PROJECTION_PREPARED serverSnapshots=2 metadata=1 commands=2 previewPersisted=false")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                    assertEquals(1, queue.recoverInterrupted(f.f.scope, 3))
                    val value = f.reader(cache, queue, true).task(f.f.lease, "e", true)
                    assertEquals(f.estrus, value.projection.serverTasks["e"]); assertEquals(f.mating, value.projection.serverTasks["m"])
                    assertEquals(f.estrus, value.projection.previewTasks["e"]); assertTrue((value.projection.previewTasks["m"] as MatingTask).pigs.isEmpty())
                    assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), value.projection.effects.map { it.disposition })
                    var sends = 0
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport { override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") } }, { QueueContext(f.f.scope, 1, true, true) }) { 4 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    assertEquals(f.metadata, value.metadata.value)
                } }
                println("PROJECTION_RESTORED newProcess=true serverSnapshots=2 metadata=1 commands=2 unknownResends=0 dependentPreviewBlocked=true confirmedCacheUnchanged=true")
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue"); prefs.edit().clear().commit() }
        }
    }
}
