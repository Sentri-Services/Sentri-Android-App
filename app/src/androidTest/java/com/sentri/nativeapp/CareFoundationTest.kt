package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class CareFoundationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private val at = "2026-10-09T01:00:00.123456789Z"
    private val weight = BigDecimal("123456789.12345678900")
    private val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
    private val identity = IdentityPigletInput(weight, "TagCase:A", "Notch.*", true, PigletSex.FEMALE)
    private val commands: List<CommandInput> = listOf(
        SubmitFarrowingInput("task", "sow", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 3), PigletCount(FarrowingPigletType.MUMMY, 0)), at),
        SubmitPigletItemsInput("task", "sow", listOf(PigletHandlingItem.LIGAMENT_CUTTING, PigletHandlingItem.TEETH_CLIPPING), null),
        SubmitPigletInfoInput("task", "sow", 2, 1, listOf(identity), weight, "ReasonCase", null, at),
        SubmitPigletWeightInput("task", "sow", weight, null),
        FosterPigletInput("task", "sow", "adopt", FosterStage.FARROWING, listOf("TagB", "TagA"), emptyList(), 0, at),
    )
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "care-${UUID.randomUUID()}.db"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun carePayloadsPersistExactValuesAndKeepReplayIdentityAfterReopen() = database { queue, name ->
        commands.forEachIndexed { n, input -> queue.enqueue(scope, "op$n", input, emptySet(), n.toLong()) }
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(commands, reopened.list(scope).map { it.input })
            commands.forEachIndexed { n, input -> assertEquals(input, reopened.enqueue(scope, "op$n", input, emptySet(), 99).input) }
            val first = requireNotNull(reopened.claim(lease, 100))
            assertEquals(commands.first(), first.input)
            reopened.finish(first, CommandOutcome.Uncertain, 101)
            assertNull(reopened.claim(lease, 102))
        }
    }
    @Test fun nullEmptySelectionsZeroAndScientificDecimalScaleRemainDistinct() {
        val farrowing = commands.first() as SubmitFarrowingInput
        val info = commands[2] as SubmitPigletInfoInput
        val variants = listOf<CommandInput>(farrowing.copy(counts = null), farrowing.copy(counts = emptyList()),
            info.copy(identities = null, totalWeight = null), info.copy(identities = emptyList(), totalWeight = BigDecimal.ZERO, increaseReason = ""),
            SubmitPigletItemsInput("task", "sow", emptyList(), null), SubmitPigletWeightInput("task", "sow", BigDecimal("1E+3"), null)) +
            FosterStage.entries.map { (commands.last() as FosterPigletInput).copy(stage = it, earTags = null, earNotches = emptyList()) }
        variants.forEach { assertEquals(it, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(it))) }
    }
    @Test fun malformedCarePayloadCannotCoerceNumbersFlagsEnumsOrNestedFields() {
        val base = CommandPayloadCodec.encode(commands[2])
        val malformed = listOf(
            base.replace("\"female\":2", "\"female\":2.5"), base.replace("\"male\":1", "\"male\":2147483648"),
            base.replace("\"breeder\":true", "\"breeder\":\"true\""), base.replace("\"FEMALE\"", "\"FUTURE\""),
            base.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":123456789.12345678900"),
            base.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":\"NaN\""),
            base.replace("\"female\":2", "\"female\":-2"), base.replace("\"breeder\":true", "\"breeder\":true,\"future\":null"),
            CommandPayloadCodec.encode(commands.last()).replace("\"adopt\":\"adopt\"", "\"adopt\":\"sow\""),
        )
        malformed.forEach { try { CommandPayloadCodec.decode(2, it); fail("Malformed payload accepted") } catch (_: RuntimeException) { } }
        val missing = JSONObject(base); missing.getJSONObject("input").remove("reduce")
        try { CommandPayloadCodec.decode(2, missing.toString()); fail() } catch (_: RuntimeException) { }
    }
    @Test fun uncertainFosterBlocksBothSowsButDoesNotSerializeUnrelatedPig() = database { queue, name ->
        val foster = queue.enqueue(scope, "foster", commands.last(), emptySet(), 1)
        queue.enqueue(scope, "source", SubmitPigletWeightInput("other-task", "sow", weight, null), emptySet(), 2)
        queue.enqueue(scope, "recipient", SubmitPigletItemsInput("other-task", "adopt", emptyList(), null), emptySet(), 3)
        val independent = queue.enqueue(scope, "independent", SubmitPigletItemsInput("task", "unrelated", emptyList(), null), emptySet(), 4)
        assertEquals(setOf("pig:sow", "pig:adopt"), foster.record.groups)
        val sending = requireNotNull(queue.claim(lease, 5)); assertEquals(foster.record.id, sending.record.id)
        queue.finish(sending, CommandOutcome.Uncertain, 6)
        SqliteCommandQueue(context, name).use { reopened ->
            val next = requireNotNull(reopened.claim(lease, 7)); assertEquals(independent.record.id, next.record.id)
            reopened.finish(next, CommandOutcome.Success("independent"), 8); assertNull(reopened.claim(lease, 9))
            reopened.finish(sending, CommandOutcome.Success("foster-confirmed"), 10)
            assertEquals("source", requireNotNull(reopened.claim(lease, 11)).record.operationId)
        }
    }
    @Test fun changingFosterStageOrRecipientCannotReuseOperationIdentity() = database { queue, _ ->
        val input = commands.last() as FosterPigletInput
        queue.enqueue(scope, "foster", input, emptySet(), 1)
        for (changed in listOf(input.copy(stage = FosterStage.PIGLET_PROCESSING), input.copy(adoptPigId = "other"))) {
            try { queue.enqueue(scope, "foster", changed, emptySet(), 2); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(input, queue.enqueue(scope.copy(farmId = "other"), "foster", input, emptySet(), 2).input)
    }
    @Test fun careSnapshotsPersistDecimalsJsonAndNestedResultsWithScopedOfflineFallback() = runBlocking {
        val name = "care-snapshots-${UUID.randomUUID()}.db"
        try { SqliteSnapshotCache(context, name).use { cache ->
            val piglet = IdentityPiglet(1, weight, "TagCase:A", null, PigletSex.FEMALE)
            val farrowing = FarrowingTask("task", "farm", "batch", "RUNNING", listOf(FarrowingPig("sow", "SowTag", null, false, location,
                FarrowingResult(FarrowingStatus.FARROWING, false, 1, 0, 2, at, at, null, null, null, "operator", listOf(piglet),
                    listOf(PigletCount(FarrowingPigletType.HEALTHY, 3)), listOf(FarrowingRecord(7, 3, 0, at, "operator"))))))
            val handling = PigletTask("task", "farm", "batch", "RUNNING", listOf(PigletPig("sow", "SowTag", null, false, location,
                PigletResult(false, true, 1, 0, 2, 2, 1, 3, at, weight, JsonValueAdapter.validate("{\"count\":123456789.12345678900}"), listOf(piglet),
                    listOf(PigletTodo(PigletHandlingItem.LIGAMENT_CUTTING, 0, at, "operator"))))))
            suspend fun <T : ProductionTaskSnapshot> save(value: T, codec: TaskCodec<T>) {
                val scopeLease = ScopeLease(scope, 1)
                assertEquals(value, codec.decode(codec.encode(value))); assertNull(codec.decode(codec.encode(null)))
                ProductionTaskReads(TaskRemote { _, _ -> value }, cache, codec, { }) { 123 }.task(scopeLease, "task")
                SqliteSnapshotCache(context, name).use { reopened ->
                    val offline = ProductionTaskReads<T>(TaskRemote { _, _ -> throw ApiFailure.Network() }, reopened, codec, { })
                    val result = offline.task(scopeLease, "task")
                    assertEquals(value, result.value); assertEquals(123L, result.fetchedAt); assertEquals(DataSource.CACHE, result.source)
                    try { offline.task(ScopeLease(scope.copy(farmId = "other"), 2), "task", true); fail() } catch (_: CacheMiss) { }
                }
            }
            save(farrowing, ProductionTaskCodecs.farrowing); save(handling, ProductionTaskCodecs.piglet)
            val encoded = ProductionTaskCodecs.piglet.encode(handling)
            for (bad in listOf(encoded.replace("LIGAMENT_CUTTING", "FUTURE"), encoded.replace("\"female\":2", "\"female\":\"2\""),
                encoded.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":\"NaN\""))) {
                try { ProductionTaskCodecs.piglet.decode(bad); fail() } catch (_: RuntimeException) { }
            }
        } } finally { context.deleteDatabase(name) }
    }
}
