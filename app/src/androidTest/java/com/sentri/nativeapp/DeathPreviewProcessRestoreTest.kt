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

class DeathPreviewProcessRestoreTest {
    @Test fun deathMembershipAndImageDependenciesRebuildPreviewAfterProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("deathPreviewStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "death-preview-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = DeathWorkspaceFixture
        val ctx = QueueContext(f.scope, 1, true, true)
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-queue")
            check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
                val value = f.reader(cache, queue).task(f.lease, "f")
                assertEquals(setOf("f", "i", "l"), value.deathContext.taskIdsByPig["mother"])
                assertEquals("DEAD", value.projection.previewProfiles["pig"]!!.lifecycle)
                assertTrue((value.projection.previewTasks["l"] as LactationTask).pigs.single().result!!.identities.isEmpty())
                assertEquals("UploadPigDeathImage", queue.claim(ctx, 1)!!.input.operationName)
            } }
            println("DEATH_PREVIEW_PREPARED pid=${Process.myPid()} snapshots=6 commands=2 crossBatchTasks=3 image=IN_FLIGHT")
        } else {
            try {
                val old = prefs.getInt("pid", -1); assertNotEquals(old, Process.myPid())
                SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { queue ->
                    assertEquals(1, queue.recoverInterrupted(f.scope, 2))
                    var reads = 0; var sends = 0
                    val reader = f.reader(cache, queue, true, remoteCalled = { reads++ })
                    val restored = reader.task(f.lease, "f", true)
                    assertEquals(0, reads); assertEquals(f.child, restored.projection.previewProfiles["pig"])
                    assertEquals(f.tasks.associateBy { it.id }, restored.projection.previewTasks)
                    assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), restored.projection.effects.map { it.disposition })
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") }
                    }, { ctx }) { 3 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    val image = queue.list(f.scope).single { it.input is UploadPigDeathImageInput }
                    queue.finish(image, CommandOutcome.Success(DeathImageReceipt("key", "https://fixture.invalid/key.jpg").encode()), 4)
                    val preview = reader.task(f.lease, "f", true)
                    assertEquals(ProjectionDisposition.RESOURCE_READY, preview.projection.effects.first().disposition)
                    assertEquals("https://fixture.invalid/key.jpg", preview.projection.effects.last().deaths.single().images.single().confirmedUrl)
                    assertEquals(f.child.weight, preview.projection.previewProfiles["pig"]!!.weight)
                    assertEquals(f.mother, preview.projection.previewProfiles["mother"])
                    val report = queue.claim(ctx, 5)!!; assertEquals("ReportPigDeath", report.input.operationName)
                    queue.finish(report, CommandOutcome.Success("simulated-death-receipt"), 6)
                    val waiting = reader.task(f.lease, "f", true)
                    assertEquals(ProjectionDisposition.WAITING_REFRESH, waiting.projection.effects.last().disposition)
                    assertEquals(f.tasks.associateBy { it.id }, waiting.projection.previewTasks)
                    assertEquals(f.child, waiting.projection.previewProfiles["pig"])
                    assertEquals(0, reads); assertEquals(0, sends)
                    println("DEATH_PREVIEW_RESTORED previousPid=$old pid=${Process.myPid()} snapshots=6 commands=2 remoteReads=$reads unknownResends=$sends crossBatchMembership=true motherAlive=true imageReadyWithoutPigReadProof=true receiptWaitsForRead=true")
                } }
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue"); prefs.edit().clear().commit() }
        }
    }
}
