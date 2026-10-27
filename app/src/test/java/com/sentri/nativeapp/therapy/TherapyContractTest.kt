package com.sentri.nativeapp.therapy

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.NativeCreatePrescriptionMutation
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TherapyContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlTherapyGateway(executor)
    private val at = "2026-10-10T08:00:00.1234567890123+08:00"
    private fun response(field: String, record: String, status: String = "SUCCESS") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"biosafety":{"$field":{"status":"$status","recordId":"$record","errors":[]}}}}"""))
    }
    private fun body() = server.takeRequest().let {
        assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key"));
        assertNull(it.getHeader("x-hasura-admin-secret")); assertNull(it.getHeader("x-hasura-farm-id")); it.body.readUtf8()
    }
    @After fun close() { client.close(); server.shutdown() }

    @Test fun completeAndSkipSendOnlyScheduledTaskTargetsAndCapturedTimeOrReason() = runTest {
        val complete = CompleteTherapyTargetInput("local-prescription", "local-therapy", "task", listOf("B", "A", "B"), at)
        response("complete_therapy_target", "task")
        val receipt = TherapyWriteReceipt.decode((gateway.execute(complete) as CommandOutcome.Success).receipt)
        receipt.validate(complete)
        val sent = body(); assertTrue(sent.contains("\"target_ids\":[\"B\",\"A\",\"B\"]")); assertTrue(sent.contains("\"completed_at\":\"$at\""))
        assertFalse(sent.contains("local-prescription")); assertFalse(sent.contains("local-therapy")); assertFalse(sent.contains("times"))
        for (reason in listOf(null, "", "skip reason")) {
            response("skip_therapy_task", "task")
            gateway.execute(SkipTherapyTaskInput("p", "t", "task", emptyList(), reason))
            val wire = body(); assertTrue(wire.contains("\"target_ids\":[]")); assertTrue(wire.contains("\"reason\":" + if (reason == null) "null" else "\"$reason\""))
        }
    }
    @Test fun addAndRemovePreserveAllTargetFieldsAndDuplicateOrderingWithoutInventedTherapyFilter() = runTest {
        val targets = listOf(PrescriptionTargetInput("B", "", null, listOf("s2", "s1"), listOf("d")), PrescriptionTargetInput("A", null, "pen"), PrescriptionTargetInput("B"))
        val inputs = listOf(AddPrescriptionTargetInput("p", targets, ""), RemovePrescriptionTargetInput("p", targets, null))
        for ((index, input) in inputs.withIndex()) {
            response(if (index == 0) "add_prescription_target" else "remove_prescription_target", "p")
            TherapyWriteReceipt.decode((gateway.execute(input) as CommandOutcome.Success).receipt).validate(input)
            val wire = body()
            for (part in listOf("\"prescription_id\":\"p\"", "\"ear_tag\":\"\"", "\"pen_code\":null", "\"symptoms\":[\"s2\",\"s1\"]", "\"diseases\":[\"d\"]")) assertTrue(part, wire.contains(part))
            assertTrue(wire.indexOf("\"target_id\":\"B\"") < wire.indexOf("\"target_id\":\"A\"")); assertFalse(wire.contains("therapy_id"))
        }
    }
    @Test fun terminationKeepsPrescriptionAndTherapyIdentitySeparateAndNullVersusEmptyReason() = runTest {
        response("terminate_therapy", "p"); gateway.execute(TerminateTherapyInput("p", "t", ""))
        val therapy = body(); assertTrue(therapy.contains("\"therapy_id\":\"t\"")); assertTrue(therapy.contains("\"reason\":\"\""))
        response("terminate_prescription", "p"); gateway.execute(TerminatePrescriptionInput("p", null))
        val prescription = body(); assertFalse(prescription.contains("therapy_id")); assertTrue(prescription.contains("\"reason\":null"))
    }
    @Test fun declaredCreateInputKeepsDecimalPrecisionAndDoesNotTreatReturnedSerialAsPrescriptionId() = runTest {
        val therapy = PrescriptionTherapyInput("treatment", "medication", null, BigDecimal("123456789.12345678900"), "ml", at, 0, null)
        for (dosage in listOf(therapy.dosage, BigDecimal("1E+3"))) {
            response("create_prescription", "server-serial")
            val result = executor.mutation(NativeCreatePrescriptionMutation(CreatePrescriptionInput(listOf(PrescriptionTargetInput("pig")), listOf(therapy.copy(dosage = dosage)), remark = "", adhoc = false).graphql()))
            assertEquals("server-serial", result.biosafety.create_prescription.recordId)
            val sent = body(); assertTrue(sent.contains("\"dosage\":\"${dosage.toPlainString()}\"")); assertTrue(sent.contains("\"times\":0")); assertTrue(sent.contains("\"interval\":null"))
        }
    }
    @Test fun refusalsMismatchedReceiptsAndAuthenticationFailureNeverBecomeConfirmedOrReplay() = runTest {
        val input = TerminatePrescriptionInput("p", null)
        response("terminate_prescription", "", "FAILURE"); assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.execute(input)); body()
        for (record in listOf("other", "")) {
            response("terminate_prescription", record)
            try { gateway.execute(input); fail() } catch (_: ApiFailure.Protocol) { }; body()
        }
        server.enqueue(MockResponse().setResponseCode(401))
        try { gateway.execute(input); fail() } catch (_: ApiFailure.Authentication) { }
        assertEquals(4, server.requestCount)
    }
    @Test fun receiptStrictlyBindsOperationAndRecordAndRejectsCoercionDuplicatesAndTrailingData() {
        val input = SkipTherapyTaskInput("p", "t", "task", emptyList(), null)
        val receipt = TherapyWriteReceipt(input.operationName, "task"); val text = receipt.encode()
        assertEquals(receipt, TherapyWriteReceipt.decode(text)); receipt.validate(input)
        for (bad in listOf("{}", text + "true", text.replace("\"format\":1", "\"format\":\"1\""), text.replace("\"record\":\"task\"", "\"record\":\"task\",\"record\":\"other\""))) {
            try { TherapyWriteReceipt.decode(bad); fail() } catch (_: Exception) { }
        }
        for (bad in listOf(receipt.copy(recordId = "other"), receipt.copy(operation = "CompleteTherapyTarget"))) {
            try { bad.validate(input); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sharedPrescriptionDependenciesCoverOtherTherapiesAndHonorInsertionOrderWhenClockMovesBack() {
        val scope = DataScope("dev", "oauth", "user", "farm")
        fun queued(id: String, input: TherapyCommandInput, at: Long, deps: Set<String> = emptySet()) = QueuedCommand(CommandRecord(id, id, scope, input.operationName, input.productionType, input.groupKeys, deps, at), input)
        val a = queued("a", SkipTherapyTaskInput("p", "t1", "scheduled1", listOf("pig"), null), 100)
        val bInput = TerminateTherapyInput("p", "t2", null)
        val deps = CommandDependencies.required(bInput, listOf(a)); assertEquals(setOf("a"), deps)
        val b = queued("b", bInput, 0, deps)
        assertNull(CommandPolicy.blocked(a.record, listOf(a.record, b.record), scope, true, true, 1000))
        assertEquals(CommandBlock.DEPENDENCY, CommandPolicy.blocked(b.record, listOf(a.record.copy(status = CommandStatus.UNCERTAIN), b.record), scope, true, true, 1000))
        assertTrue(CommandDependencies.required(TerminatePrescriptionInput("other", null), listOf(a, b)).isEmpty())
        assertTrue(a.record.groups.contains("pig:pig")); assertNull(a.input.taskId)
    }
    @Test fun explicitTransportChecksContextBeforeSendingOneRemoteStep() = runTest {
        val scope = DataScope("dev", "oauth", "user", "farm"); val input = TerminatePrescriptionInput("p", null)
        val ctx = QueueContext(scope, 1, true, true)
        val command = QueuedCommand(CommandRecord("id", "op", scope, input.operationName, null, input.groupKeys, createdAtMillis = 0, status = CommandStatus.IN_FLIGHT), input)
        var calls = 0
        val fake = object : TherapyGateway { override suspend fun execute(input: TherapyCommandInput): CommandOutcome { calls++; return CommandOutcome.Uncertain } }
        assertTrue(TherapyTransport(fake) { ctx.copy(scope = scope.copy(farmId = "other")) }.send(command) is CommandOutcome.NotSent)
        var checks = 0
        assertTrue(TherapyTransport(fake) { if (checks++ == 0) ctx else ctx.copy(revision = 2) }.send(command) is CommandOutcome.NotSent)
        assertEquals(0, calls); assertEquals(CommandOutcome.Uncertain, TherapyTransport(fake) { ctx }.send(command)); assertEquals(1, calls)
    }
}
