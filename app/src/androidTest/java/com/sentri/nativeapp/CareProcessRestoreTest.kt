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
import java.math.BigDecimal

/** Opt-in host-driven prepare/force-stop/restore; no real business writes. */
class CareProcessRestoreTest {
    @Test fun fosterAndCarePayloadsAndSnapshotsSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("careStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "care-process-test"
        val preferences = context.getSharedPreferences(name, 0)
        val scope = DataScope("simulated-dev", "simulated-oauth", "simulated-user", "simulated-farm")
        val lease = ScopeLease(scope, 1)
        val queueContext = QueueContext(scope, 1, true, true)
        val weight = BigDecimal("123456789.12345678900")
        val at = "2026-10-09T01:00:00.123456789Z"
        val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
        val farrowing = FarrowingTask("fm-task", scope.farmId, "batch", "RUNNING", listOf(FarrowingPig("sow", "SowTag", null, false, location, null)))
        val piglet = PigletTask("piglet-task", scope.farmId, "batch", "RUNNING", listOf(PigletPig("adopt", "AdoptTag", null, false, location,
            PigletResult(false, true, 0, 0, 1, 1, 0, 1, at, weight, null, listOf(IdentityPiglet(1, weight, "TagCase:A", null, PigletSex.FEMALE)),
                listOf(PigletTodo(PigletHandlingItem.LIGAMENT_CUTTING, 0, null, null))))))
        val commands: List<CommandInput> = listOf(
            FosterPigletInput("fm-task", "sow", "adopt", FosterStage.FARROWING, listOf("TagCase:A"), emptyList(), 0, at),
            SubmitFarrowingInput("fm-task", "sow", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 1)), at),
            SubmitPigletItemsInput("piglet-task", "adopt", listOf(PigletHandlingItem.LIGAMENT_CUTTING), null),
            SubmitPigletInfoInput("piglet-task", "adopt", 1, 0, listOf(IdentityPigletInput(weight, "TagCase:A", null, true, PigletSex.FEMALE)), weight, null, null, at),
            SubmitPigletWeightInput("piglet-task", "adopt", weight, null),
        )
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase("$name-cache"); preferences.edit().clear().commit()
            check(preferences.edit().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, "$name-cache").use { cache ->
                ProductionTaskReads(TaskRemote { _, _ -> farrowing }, cache, ProductionTaskCodecs.farrowing, { }).task(lease, farrowing.id)
                ProductionTaskReads(TaskRemote { _, _ -> piglet }, cache, ProductionTaskCodecs.piglet, { }).task(lease, piglet.id)
            }
            SqliteCommandQueue(context, name).use { queue ->
                commands.forEachIndexed { n, input -> queue.enqueue(scope, "op$n", input, emptySet(), n.toLong()) }
                assertEquals("FosterPiglet", requireNotNull(queue.claim(queueContext, 5)).record.operationName)
            }
            println("CARE_PROCESS_PREPARED snapshots=2 commands=5 foster=IN_FLIGHT decimalExact=true")
        } else {
            try {
                assertNotEquals(preferences.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, "$name-cache").use { cache ->
                    assertEquals(farrowing, ProductionTaskReads<FarrowingTask>(TaskRemote { _, _ -> throw ApiFailure.Network() }, cache, ProductionTaskCodecs.farrowing, { }).task(lease, farrowing.id).value)
                    assertEquals(piglet, ProductionTaskReads<PigletTask>(TaskRemote { _, _ -> throw ApiFailure.Network() }, cache, ProductionTaskCodecs.piglet, { }).task(lease, piglet.id).value)
                }
                SqliteCommandQueue(context, name).use { queue ->
                    val entries = queue.list(scope)
                    assertEquals(commands, entries.map { it.input }); assertEquals(setOf("pig:sow", "pig:adopt"), entries.first().record.groups)
                    assertEquals(1, queue.recoverInterrupted(scope, 6))
                    var sends = 0
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated") }
                    }, { queueContext }) { 7 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    queue.finish(entries.first(), CommandOutcome.Success("simulated-confirmed-foster"), 8)
                    assertEquals("SubmitFarrowingPiglet", requireNotNull(queue.claim(queueContext, 9)).record.operationName)
                }
                println("CARE_PROCESS_RESTORED newProcess=true snapshots=2 commands=5 decimalExact=true sourceAndRecipientBlocked=true automaticResends=0")
            } finally { context.deleteDatabase(name); context.deleteDatabase("$name-cache"); preferences.edit().clear().commit() }
        }
    }
}
