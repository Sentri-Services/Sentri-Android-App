package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

class StageProcessRestoreTest {
    @Test fun sowAndLocationCommandsAndFourSnapshotsSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("stageRestore")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stages-process-test"
        val prefs = context.getSharedPreferences(name, 0)
        val scope = DataScope("simulated-dev", "simulated-oauth", "simulated-user", "simulated-farm")
        val lease = ScopeLease(scope, 1)
        val queueContext = QueueContext(scope, 1, true, true)
        val at = "2026-10-09T01:00:00.123456789Z"
        val weight = BigDecimal("123456789.12345678900")
        val assessment = SowAssessment(ActivityLevel.LAME, BackfatThickness.THIN, BodyConditionScore.SCORE_2, FeedingSituation.REDUCED_INTAKE,
            MastitisSeverity.NO_MASSTITIS, MilkProductionCapacity.POOR, SecretionStatus.NORMAL, ProlapseType.RECTAL, null, false)
        val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
        val result = SowCheckResult(false, at, JsonValueAdapter.validate("{\"prolapse_type\":\"RECTAL\",\"prolapse_recurred\":null,\"prolapse_resolved\":false}"), null, null, true, false, null, "operator")
        val pp = PostpartumTask("pp-task", scope.farmId, "batch", "RUNNING", listOf(PostpartumPig("pig", "TagCase:A", null, false, location, result)))
        val lc = LactationTask("lc-task", scope.farmId, "batch", "RUNNING", listOf(LactationPig("pig", "TagCase:A", null, false, location,
            LactationResult(result, 1, 0, 0, null, weight, listOf(LactationIdentity(IdentityPiglet(1, weight, "TagCase:A", null, PigletSex.FEMALE), true)), emptyList()))))
        val ns = GrowingTask("ns-task", scope.farmId, "batch", "RUNNING", GrowingStage.NURSERY, listOf(GrowingPig("pig", "TagCase:A", null, false, location, PigletSex.FEMALE, weight, GrowingPigResult(true, false, "z.u.p"))), listOf(NurseryLocation("z.u.p", at, at, "operator", 1, weight)))
        val ft = ns.copy(id = "ft-task", stage = GrowingStage.FATTENING, locations = emptyList())
        val growing = GrowingAssessment(1, 0, 1, 0, weight, 0)
        val commands: List<CommandInput> = listOf(SubmitPostpartumInput("pp-task", "pig", assessment, at, null, false, null),
            SubmitLactationInput("lc-task", "pig", null, null, null, false, null, 1, 0, weight, emptyList(), emptyList()),
            CheckGrowingInput("ns-task", "z.u.p", GrowingStage.NURSERY, growing, emptyList(), at),
            CheckGrowingInput("ft-task", "z.u.p", GrowingStage.FATTENING, growing, null, null))
        suspend fun snapshots(cache: SqliteSnapshotCache, save: Boolean) {
            suspend fun <T : ProductionTaskSnapshot> snapshot(value: T, codec: TaskCodec<T>) {
                val reader = ProductionTaskReads<T>(TaskRemote { _, _ -> if (save) value else throw ApiFailure.Network() }, cache, codec, { })
                assertEquals(value, reader.task(lease, value.id).value)
            }
            snapshot(pp, StageTaskCodecs.postpartum); snapshot(lc, StageTaskCodecs.lactation); snapshot(ns, StageTaskCodecs.nursery); snapshot(ft, StageTaskCodecs.fattening)
        }
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-cache"); prefs.edit().clear().commit()
            check(prefs.edit().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, "$name-cache").use { snapshots(it, true) }
            SqliteCommandQueue(context, name).use { queue ->
                commands.forEachIndexed { n, input -> queue.enqueue(scope, "op$n", input, emptySet(), n.toLong()) }
                assertEquals("SubmitPostpartumPigStatus", requireNotNull(queue.claim(queueContext, 5)).record.operationName)
            }
            println("STAGES_PROCESS_PREPARED snapshots=4 commands=4 postpartum=IN_FLIGHT")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, "$name-cache").use { snapshots(it, false) }
                SqliteCommandQueue(context, name).use { queue ->
                    val records = queue.list(scope); assertEquals(commands, records.map { it.input })
                    assertEquals(1, queue.recoverInterrupted(scope, 6))
                    val sent = mutableListOf<String>()
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sent += command.record.operationId; return CommandOutcome.Success("simulated") }
                    }, { queueContext }) { 7 }
                    assertTrue(dispatcher.drainOne()); assertTrue(dispatcher.drainOne()); assertFalse(dispatcher.drainOne())
                    assertEquals(listOf("op2", "op3"), sent); assertFalse(sent.contains("op0")); assertFalse(sent.contains("op1"))
                    queue.finish(records.first(), CommandOutcome.Success("simulated-confirmed-postpartum"), 8)
                    assertEquals("SubmitLactationPigStatus", requireNotNull(queue.claim(queueContext, 9)).record.operationName)
                }
                println("STAGES_PROCESS_RESTORED newProcess=true snapshots=4 commands=4 decimalExact=true nullableProlapse=true unconfirmedResends=0 independentLocations=2")
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-cache"); prefs.edit().clear().commit() }
        }
    }
}
