package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.graphql.type.ProductionReportPigDeathCommandStrawberryInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PigDeathProcessRestoreTest {
    @Test fun partialUploadsSurviveProcessStopAndReportRetryReusesReceipts() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("deathStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pig-death-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = PigDeathFixture
        if (stage == "prepare") {
            context.deleteDatabase(name); check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteCommandQueue(context, name).use { queue ->
                queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
                val first = requireNotNull(queue.claim(f.context, 1)); assertEquals(0, (first.input as UploadPigDeathImageInput).imageIndex)
                queue.finish(first, CommandOutcome.Success(f.receipt(0)), 2)
                val second = requireNotNull(queue.claim(f.context, 3)); assertEquals(2, (second.input as UploadPigDeathImageInput).imageIndex)
            }
            println("PIG_DEATH_PREPARED pid=${Process.myPid()} commands=3 uploadsSynced=1 uploadsInFlight=1")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteCommandQueue(context, name).use { queue ->
                    val original = queue.list(f.scope); assertEquals(3, original.size)
                    val unknown = original.single { it.record.status == CommandStatus.IN_FLIGHT }
                    val confirmed = original.single { it.record.status == CommandStatus.SYNCED }
                    assertEquals(f.receipt(0), confirmed.receipt)
                    assertEquals(1, queue.recoverInterrupted(f.scope, 4))
                    var uploads = 0; val reports = mutableListOf<ProductionReportPigDeathCommandStrawberryInput>()
                    val transport = PigDeathTransport(queue, object : PigDeathGateway {
                        override suspend fun upload(input: UploadPigDeathImageInput): CommandOutcome { uploads++; error("Unknown must not replay") }
                        override suspend fun report(input: ProductionReportPigDeathCommandStrawberryInput): CommandOutcome {
                            reports += input
                            return if (reports.size == 1) CommandOutcome.Rejected("SIMULATED") else CommandOutcome.Success("simulated-report")
                        }
                    }, { f.context })
                    var now = 5L; val dispatcher = CommandDispatcher(queue, transport, { f.context }) { now }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, uploads); assertTrue(reports.isEmpty())
                    queue.finish(unknown, CommandOutcome.Success(f.receipt(1)), 6)
                    assertTrue(dispatcher.drainOne())
                    val report = queue.list(f.scope).single { it.input is ReportPigDeathInput }
                    queue.retryAfterConfirmation(f.scope, report.record.id, 7, true); now = 10_000
                    assertTrue(dispatcher.drainOne()); assertFalse(dispatcher.drainOne())
                    assertEquals(0, uploads); assertEquals(2, reports.size); assertEquals(reports[0], reports[1])
                    assertEquals(listOf("https://fixture.invalid/upload0", "https://fixture.invalid/remote", "https://fixture.invalid/upload1"),
                        reports[0].pigs.getOrNull()!!.first().death_images.getOrNull())
                    assertTrue(queue.list(f.scope.copy(userId = "other")).isEmpty())
                    assertEquals(3, queue.list(f.scope).count { it.record.status == CommandStatus.SYNCED })
                }
                println("PIG_DEATH_RESTORED pid=${Process.myPid()} newProcess=true commands=3 unknownResends=0 confirmedUploadResends=0 reportAttempts=2 imageOrder=true scopeIsolated=true")
            } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
        }
    }
}
