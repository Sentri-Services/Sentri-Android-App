package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

internal object PigManagementFixture {
    val scope = TaskCatalogFixture.scope
    val at = "2026-10-10T08:00:00.1234567890123+08:00"
    val property = UpdatePigPropertyInput("A", PigLifecycle.MISSING, PigletSex.FEMALE, 0, BigDecimal("123456789.12345678900"), "")
    val role = UpdateBreederRoleInput(BreederRoleAction.UPGRADE_TO_PRODUCTION,
        listOf(BreederPigInput("B", "", null, PigletSex.MALE, "mother", null), BreederPigInput("A", null, "NotchCase:A", null, null, "father")), at)
    val other = UpdatePigPropertyInput("C", weight = BigDecimal("1E+3"))
}
class PigManagementQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = PigManagementFixture
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "pig-management-" + UUID.randomUUID()
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun twoPayloadsReopenWithExactWeightNullFieldsParentsAndOrderedDuplicateSelection() = database { queue, name ->
        val inputs = listOf<CommandInput>(f.property, f.other, f.role, f.role.copy(pigs = f.role.pigs + f.role.pigs.first(), markedAt = null),
            f.role.copy(action = BreederRoleAction.CANCEL_TO_PIGLET, pigs = emptyList()), UpdatePigPropertyInput("none"))
        inputs.forEachIndexed { i, input -> queue.enqueue(f.scope, "op" + i, input, emptySet(), i.toLong()) }
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(inputs, reopened.list(f.scope).map { it.input })
            assertEquals(BigDecimal("1E+3"), (reopened.list(f.scope)[1].input as UpdatePigPropertyInput).weight)
            assertEquals(listOf("B", "A", "B"), (reopened.list(f.scope)[3].input as UpdateBreederRoleInput).pigs.map { it.pigId })
        }
    }
    @Test fun unknownPropertyBlocksMultiPigRoleButOtherPigAndScopeRemainIndependent() = database { queue, _ ->
        val a = queue.enqueue(f.scope, "property", f.property, emptySet(), 0)
        queue.enqueue(f.scope, "role", f.role, emptySet(), 1); queue.enqueue(f.scope, "other", f.other, emptySet(), 2)
        val attempt = requireNotNull(queue.claim(QueueContext(f.scope, 1, true, true), 3)); assertEquals(a.record.id, attempt.record.id)
        queue.finish(attempt, CommandOutcome.Uncertain, 4)
        val other = requireNotNull(queue.claim(QueueContext(f.scope, 1, true, true), 5)); assertEquals("other", other.record.operationId)
        queue.finish(other, CommandOutcome.Success("simulated"), 6); assertNull(queue.claim(QueueContext(f.scope, 1, true, true), 7))
        val foreign = f.scope.copy(userId = "other")
        queue.enqueue(foreign, "role", f.role, emptySet(), 0)
        assertEquals(foreign, queue.claim(QueueContext(foreign, 1, true, true), 8)?.record?.scope)
        queue.finish(attempt, CommandOutcome.Success("simulated-confirmed"), 9)
        assertEquals("role", queue.claim(QueueContext(f.scope, 1, true, true), 10)?.record?.operationId)
    }
    @Test fun knownProductionCommandsSharePigOrderAndOperationIdentityChecksFullInput() = database { queue, _ ->
        val estrus = queue.enqueue(f.scope, "check", CheckEstrusInput("e", "A", EstrusStatus.IN_ESTRUS, f.at), emptySet(), 0)
        val property = queue.enqueue(f.scope, "property", f.property, emptySet(), 1)
        assertEquals(property, queue.enqueue(f.scope, "property", f.property, emptySet(), 2))
        try { queue.enqueue(f.scope, "property", f.property.copy(remark = null), emptySet(), 2); fail() } catch (_: IllegalArgumentException) { }
        val attempt = requireNotNull(queue.claim(QueueContext(f.scope, 1, true, true), 3)); assertEquals(estrus.record.id, attempt.record.id)
        queue.finish(attempt, CommandOutcome.Rejected("SIMULATED"), 4)
        assertNull(queue.claim(QueueContext(f.scope, 1, true, true), 5))
        queue.cancel(f.scope, estrus.record.id, 6)
        assertEquals(property.record.id, queue.claim(QueueContext(f.scope, 1, true, true), 7)?.record?.id)
    }
    @Test fun strictPayloadDecodeRejectsCoercedNumbersEnumsMalformedDatesAndExtraFields() {
        val property = CommandPayloadCodec.encode(f.property)
        val role = CommandPayloadCodec.encode(f.role)
        val invalid = listOf(property.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":123456789.12345678900"),
            property.replace("\"teats\":0", "\"teats\":0.5"), property.replace("\"lifecycle\":\"MISSING\"", "\"lifecycle\":\"FUTURE\""),
            role.replace(f.at, "bad"), role.replace("\"mother\":\"mother\"", "\"mother\":true"),
            JSONObject(role).apply { getJSONObject("input").put("extra", true) }.toString())
        for (bad in invalid) {
            assertTrue(bad != property && bad != role)
            try { CommandPayloadCodec.decode(2, bad); fail() } catch (_: Exception) { }
        }
    }
}
