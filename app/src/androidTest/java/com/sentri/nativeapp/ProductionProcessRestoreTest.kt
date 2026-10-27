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

/** Explicit two-stage host-driven process test. All business data and transport are simulated. */
class ProductionProcessRestoreTest {
    @Test fun unitRoundsAndReadSnapshotsSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("productionStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "production-process-test"
        val preferences = context.getSharedPreferences(name, 0)
        val scope = DataScope("simulated-dev", "simulated-oauth", "simulated-user", "simulated-farm")
        val lease = ScopeLease(scope, 1)
        val queueContext = QueueContext(scope, 1, true, true)
        val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
        val preg = PregnancyTask("preg-task", scope.farmId, "batch", "RUNNING", listOf(PregnancyPig("pig", "TagA", null, false, location,
            PregnancyResult(PregnancyStatus.NOT_SURE, "2026-10-09T01:00:00.123456Z", null, null, null, null, null, null))))
        val re = ReEstrusTask("re-task", scope.farmId, "batch", "RUNNING", listOf(ReEstrusPig("pig", "TagA", null, false, location,
            ReEstrusResult(ReEstrusStatus.NOT_RE_ESTRUS, null, false, null, null, null, null, null))))
        val pregCheck = CheckPregnancyInput("preg-task", "pig", PregnancyStatus.NOT_SURE, null, 3, "z.u")
        val pregSubmit = SubmitPregnancyInput("preg-task", listOf("pig"), null, "z.u")
        val reCheck = CheckReEstrusInput("re-task", "pig", ReEstrusStatus.SIGNS_OF_RE_ESTRUS, null, "z.u")
        val reSubmit = SubmitReEstrusInput("re-task", listOf("pig"), null, "z.u")
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-cache"); preferences.edit().clear().commit()
            check(preferences.edit().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, "$name-cache").use { cache ->
                ProductionTaskReads(TaskRemote { _, _ -> preg }, cache, ProductionTaskCodecs.pregnancy, { }).task(lease, preg.id)
                ProductionTaskReads(TaskRemote { _, _ -> re }, cache, ProductionTaskCodecs.reEstrus, { }).task(lease, re.id)
            }
            SqliteCommandQueue(context, name).use { queue ->
                queue.enqueue(scope, "preg-check", pregCheck, emptySet(), 1)
                queue.enqueue(scope, "preg-submit", pregSubmit, emptySet(), 2)
                queue.enqueue(scope, "re-check", reCheck, emptySet(), 3)
                queue.enqueue(scope, "re-submit", reSubmit, emptySet(), 4)
                assertEquals("CheckPregnancyPig", requireNotNull(queue.claim(queueContext, 5)).record.operationName)
            }
            println("PRODUCTION_PROCESS_PREPARED twoTaskSnapshots=true fourTypedCommands=true pregnancyCheck=IN_FLIGHT")
        } else {
            try {
                assertNotEquals(preferences.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, "$name-cache").use { cache ->
                    val offlinePreg = ProductionTaskReads<PregnancyTask>(TaskRemote { _, _ -> throw ApiFailure.Network() }, cache, ProductionTaskCodecs.pregnancy, { })
                    val offlineRe = ProductionTaskReads<ReEstrusTask>(TaskRemote { _, _ -> throw ApiFailure.Network() }, cache, ProductionTaskCodecs.reEstrus, { })
                    assertEquals(preg, offlinePreg.task(lease, preg.id).value)
                    assertEquals(re, offlineRe.task(lease, re.id).value)
                }
                SqliteCommandQueue(context, name).use { queue ->
                    val entries = queue.list(scope).associateBy { it.record.operationId }
                    assertEquals(pregCheck, entries.getValue("preg-check").input)
                    assertEquals(pregSubmit, entries.getValue("preg-submit").input)
                    assertEquals(reCheck, entries.getValue("re-check").input)
                    assertEquals(reSubmit, entries.getValue("re-submit").input)
                    assertEquals(setOf(entries.getValue("preg-check").record.id), entries.getValue("preg-submit").record.dependsOn)
                    assertEquals(setOf(entries.getValue("re-check").record.id), entries.getValue("re-submit").record.dependsOn)
                    assertEquals(1, queue.recoverInterrupted(scope, 6))
                    var sends = 0
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") }
                    }, { queueContext }) { 7 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    queue.finish(entries.getValue("preg-check"), CommandOutcome.Success("simulated-confirmed-check"), 8)
                    assertEquals("SubmitPregnancyStatus", requireNotNull(queue.claim(queueContext, 9)).record.operationName)
                }
                println("PRODUCTION_PROCESS_RESTORED newProcess=true snapshots=true fourTypedCommands=true uncertainBlocks=true automaticResends=0 confirmed=true")
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-cache"); preferences.edit().clear().commit() }
        }
    }
}
