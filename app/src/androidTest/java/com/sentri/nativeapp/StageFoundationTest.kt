package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class StageFoundationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private val at = "2026-10-09T01:00:00.123456789Z"
    private val weight = BigDecimal("123456789.12345678900")
    private val sow = SowAssessment(ActivityLevel.LAME, BackfatThickness.THIN, BodyConditionScore.SCORE_2, FeedingSituation.REDUCED_INTAKE,
        MastitisSeverity.NO_MASSTITIS, MilkProductionCapacity.POOR, SecretionStatus.NORMAL, null, null, false)
    private val counts = GrowingAssessment(2, 0, 1, 1, weight, 0)
    private val identity = GrowingIdentityInput("pig", "z.u.p", true, false, "TagCase:A", null, PigletSex.FEMALE, weight)
    private val postpartum = SubmitPostpartumInput("pp-task", "pig", sow, at, null, false, null)
    private val lactation = SubmitLactationInput("lc-task", "pig", sow, null, "", false, null, 1, 1, weight,
        listOf(IdentityPigletInput(weight, "TagCase:A", null, true, PigletSex.FEMALE)), listOf(PigletCount(FarrowingPigletType.HEALTHY, 2)))
    private val nursery = CheckGrowingInput("ns-task", "z.u.p", GrowingStage.NURSERY, counts, listOf(identity), at)
    private val fattening = nursery.copy(taskId = "ft-task", stage = GrowingStage.FATTENING)
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "stages-${UUID.randomUUID()}.db"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun fourOperationsPersistAcrossReopenWithNestedEnumsDecimalAndNulls() = database { queue, name ->
        val commands = listOf(postpartum, lactation, nursery, fattening)
        commands.forEachIndexed { n, input -> queue.enqueue(scope, "op$n", input, emptySet(), n.toLong()) }
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(commands, reopened.list(scope).map { it.input })
            commands.forEachIndexed { n, input -> assertEquals(input, reopened.enqueue(scope, "op$n", input, emptySet(), 99).input) }
            assertEquals("growing", reopened.list(scope).last().record.productionType)
        }
    }
    @Test fun assessmentNullableFlagsAndAggregateSelectionsKeepExactSemantics() {
        val variants = listOf(postpartum.copy(assessment = sow.copy(prolapse = ProlapseType.NONE, recurred = false, resolved = null)),
            postpartum.copy(assessment = sow.copy(prolapse = ProlapseType.RECTAL, recurred = true, resolved = false)),
            lactation.copy(assessment = null, identities = null, counts = null, totalWeight = null),
            lactation.copy(assessment = null, identities = emptyList(), counts = emptyList(), totalWeight = BigDecimal.ZERO),
            nursery.copy(identities = emptyList()), nursery.copy(identities = null, assessment = counts.copy(totalWeight = BigDecimal("1E+3"))))
        variants.forEach { assertEquals(it, CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(it))) }
    }
    @Test fun corruptedNestedEnumBooleanDecimalIntegerAndMissingFieldsRejectRestore() {
        val encoded = CommandPayloadCodec.encode(postpartum)
        val growing = CommandPayloadCodec.encode(nursery)
        val invalid = listOf(encoded.replace("NO_MASSTITIS", "FUTURE"), encoded.replace("\"resolved\":false", "\"resolved\":\"false\""),
            encoded.replace("\"recurred\":null,", ""), encoded.replace("\"assessment\":{", "\"assessment\":{\"future\":null,"),
            growing.replace("\"healthy\":2", "\"healthy\":2.5"), growing.replace("\"healthy\":2", "\"healthy\":2147483648"),
            growing.replace("\"healthy\":2", "\"healthy\":-2"), growing.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":123456789.12345678900"),
            growing.replace("\"FEMALE\"", "\"FUTURE\""))
        invalid.forEach { try { CommandPayloadCodec.decode(2, it); fail() } catch (_: RuntimeException) { } }
    }
    @Test fun sameLocationWaitsForUncertainAggregateWhileDifferentLocationRemainsIndependent() = database { queue, name ->
        val first = queue.enqueue(scope, "first", nursery, emptySet(), 1)
        val later = queue.enqueue(scope, "later", nursery.copy(checkedAt = null), emptySet(), 2)
        val independent = queue.enqueue(scope, "other-pen", nursery.copy(locationCode = "z.u.p2", identities = listOf(identity.copy(pigId = "other-pig", locationCode = "z.u.p2"))), emptySet(), 3)
        assertEquals(first.record.groups, later.record.groups); assertNotEquals(first.record.groups, independent.record.groups)
        val sent = requireNotNull(queue.claim(lease, 4)); assertEquals(first.record.id, sent.record.id)
        queue.finish(sent, CommandOutcome.Uncertain, 5)
        SqliteCommandQueue(context, name).use { reopened ->
            val next = requireNotNull(reopened.claim(lease, 6)); assertEquals(independent.record.id, next.record.id)
            reopened.finish(next, CommandOutcome.Success("other-pen"), 7); assertNull(reopened.claim(lease, 8))
            reopened.finish(sent, CommandOutcome.Success("confirmed-location"), 9)
            assertEquals(later.record.id, requireNotNull(reopened.claim(lease, 10)).record.id)
        }
        assertNotEquals(nursery.copy(taskId = "a:b", locationCode = "c").groupKeys, nursery.copy(taskId = "a", locationCode = "b:c").groupKeys)
    }
    @Test fun postpartumAndLactationSharePigOrderingAcrossTasksAndFoster() = database { queue, _ ->
        queue.enqueue(scope, "postpartum", postpartum, emptySet(), 1)
        queue.enqueue(scope, "lactation", lactation, emptySet(), 2)
        queue.enqueue(scope, "foster", FosterPigletInput("lc-task", "pig", "adopt", FosterStage.LACTATION, null, null, 1, at), emptySet(), 3)
        val sent = requireNotNull(queue.claim(lease, 4)); queue.finish(sent, CommandOutcome.Uncertain, 5)
        assertNull(queue.claim(lease, 6))
        queue.finish(sent, CommandOutcome.Success("postpartum-confirmed"), 7)
        assertEquals("SubmitLactationPigStatus", requireNotNull(queue.claim(lease, 8)).record.operationName)
    }
    @Test fun fourSnapshotsRestoreSowJsonLactationBreederAndNurseryDecimalLocations() = runBlocking {
        val name = "stage-cache-${UUID.randomUUID()}.db"
        val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
        val result = SowCheckResult(false, at, JsonValueAdapter.validate("{\"prolapse_recurred\":null,\"prolapse_resolved\":false,\"future\":0.1234567890123456789}"), null, null, true, false, null, "operator")
        val pp = PostpartumTask("pp-task", "farm", "batch", "RUNNING", listOf(PostpartumPig("pig", "TagCase:A", null, false, location, result)))
        val lc = LactationTask("lc-task", "farm", "batch", "RUNNING", listOf(LactationPig("pig", "TagCase:A", null, false, location,
            LactationResult(result, 1, 1, 1, null, weight, listOf(LactationIdentity(IdentityPiglet(1, weight, "TagCase:A", null, PigletSex.FEMALE), true)), listOf(PigletCount(FarrowingPigletType.HEALTHY, 2))))))
        val ns = GrowingTask("ns-task", "farm", "batch", "RUNNING", GrowingStage.NURSERY, listOf(GrowingPig("pig", "TagCase:A", null, false, location, PigletSex.FEMALE, weight, GrowingPigResult(true, false, "z.u.p"))), listOf(NurseryLocation("z.u.p", at, at, "operator", 2, weight)))
        val ft = ns.copy(id = "ft-task", stage = GrowingStage.FATTENING, locations = emptyList())
        try { SqliteSnapshotCache(context, name).use { cache ->
            suspend fun <T : ProductionTaskSnapshot> save(value: T, codec: TaskCodec<T>) {
                val scopeLease = ScopeLease(scope, 1)
                assertEquals(value, codec.decode(codec.encode(value))); assertNull(codec.decode(codec.encode(null)))
                ProductionTaskReads(TaskRemote { _, _ -> value }, cache, codec, { }) { 123 }.task(scopeLease, value.id)
                SqliteSnapshotCache(context, name).use { reopened ->
                    val reader = ProductionTaskReads<T>(TaskRemote { _, _ -> throw ApiFailure.Network() }, reopened, codec, { })
                    assertEquals(value, reader.task(scopeLease, value.id).value)
                    try { reader.task(ScopeLease(scope.copy(farmId = "other"), 2), value.id, true); fail() } catch (_: CacheMiss) { }
                }
            }
            save(pp, StageTaskCodecs.postpartum); save(lc, StageTaskCodecs.lactation); save(ns, StageTaskCodecs.nursery); save(ft, StageTaskCodecs.fattening)
            try { StageTaskCodecs.fattening.decode(StageTaskCodecs.nursery.encode(ns)); fail() } catch (_: IllegalArgumentException) { }
            try { StageTaskCodecs.nursery.encode(ft); fail() } catch (_: IllegalArgumentException) { }
            for (bad in listOf(StageTaskCodecs.lactation.encode(lc).replace("\"breeder\":true", "\"breeder\":\"true\""),
                StageTaskCodecs.lactation.encode(lc).replace("\"female\":1", "\"female\":1.5"))) {
                try { StageTaskCodecs.lactation.decode(bad); fail() } catch (_: RuntimeException) { }
            }
        } } finally { context.deleteDatabase(name) }
    }
}
