package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

class ProfileWorkspaceProcessRestoreTest {
    @Test fun profilesAndManagementPreviewRebuildWithoutUnknownReplayOrSnapshotMutation() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("profileWorkspaceStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "profile-workspace-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = ProfileWorkspaceFixture
        val amount = BigDecimal("0.000000000000012345678900")
        val queueContext = QueueContext(f.scope, 1, true, true)
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-queue")
            check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                f.reader(cache, queue).task(f.lease, "n")
                val property = queue.enqueue(f.scope, "property", UpdatePigPropertyInput("pig", weight = amount, remark = ""), emptySet(), 0)
                queue.enqueue(f.scope, "role", f.role(), setOf(property.record.id), 1)
                val preview = f.reader(cache, queue, true).task(f.lease, "n", true)
                assertEquals(amount, preview.projection.previewProfiles["pig"]!!.weight)
                assertEquals("BREEDER_GILT", preview.projection.previewProfiles["pig"]!!.role)
                assertTrue((preview.projection.previewTasks["n"] as GrowingTask).pigs.single().result!!.breeder)
                assertEquals("property", queue.claim(queueContext, 2)!!.record.operationId)
            } }
            println("PROFILE_WORKSPACE_PREPARED pid=${Process.myPid()} snapshots=3 commands=2 property=IN_FLIGHT previewPersisted=false")
        } else {
            try {
                val previousPid = prefs.getInt("pid", -1)
                assertNotEquals(previousPid, Process.myPid())
                SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                    assertEquals(1, queue.recoverInterrupted(f.scope, 3))
                    var remoteReads = 0; var sends = 0
                    val reader = f.reader(cache, queue, true, f.remote(called = { remoteReads++ }))
                    val restored = reader.task(f.lease, "n", true)
                    assertEquals(0, remoteReads); assertEquals(f.pig, restored.projection.previewProfiles["pig"])
                    assertEquals(f.growing, restored.projection.previewTasks["n"])
                    assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), restored.projection.effects.map { it.disposition })
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") }
                    }, { queueContext }) { 4 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    val property = queue.list(f.scope).single { it.record.operationId == "property" }
                    assertEquals(amount, (property.input as UpdatePigPropertyInput).weight)
                    queue.finish(property, CommandOutcome.Success("simulated-confirmed"), 5)
                    val waiting = reader.task(f.lease, "n", true)
                    assertTrue(waiting.projection.effects.all { it.disposition == ProjectionDisposition.WAITING_REFRESH })
                    assertEquals(f.pig, waiting.projection.serverProfiles["pig"])
                    // Simulated confirmed read plus explicit caller evidence; a write receipt alone never changes a snapshot.
                    val confirmed = f.pig.copy(weight = amount)
                    val refreshed = f.reader(cache, queue, remote = f.remote(listOf(confirmed))).task(f.lease, "n", reflectedCommandIds = setOf(property.record.id))
                    assertEquals(confirmed, refreshed.projection.serverProfiles["pig"])
                    assertEquals("BREEDER_GILT", refreshed.projection.previewProfiles["pig"]!!.role)
                    assertEquals(amount, refreshed.projection.previewProfiles["pig"]!!.weight)
                    assertEquals(f.metadata, refreshed.metadata.value)
                    assertEquals(0, sends)
                    println("PROFILE_WORKSPACE_RESTORED previousPid=$previousPid pid=${Process.myPid()} remoteReadsBeforeRefresh=$remoteReads unknownResends=$sends decimalExact=true confirmedCacheUnchangedBeforeRead=true receiptWaitsForRead=true rolePreviewAfterExplicitEvidence=true")
                } }
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue"); prefs.edit().clear().commit() }
        }
    }
}
