package com.sentri.nativeapp.vaccination

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VaccinationContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlVaccinationGateway(executor)
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private fun response(field: String, record: String, status: String = "SUCCESS") = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"biosafety":{"$field":{"status":"$status","recordId":"$record","errors":[]}}}}"""))
    private fun body() = server.takeRequest().let {
        assertEquals("en", it.getHeader("sentri-language"))
        assertNull(it.getHeader("Idempotency-Key")); assertNull(it.getHeader("x-hasura-admin-secret"))
        assertNull(it.getHeader("x-hasura-farm-id")); it.body.readUtf8()
    }
    @After fun close() { client.close(); server.shutdown() }

    @Test fun executePreservesTargetOrderAndTimeWhileFinishUsesItsOwnTimeField() = runTest {
        val execute = ExecuteVaccinationTaskInput("task", listOf("B", "A", "B"), at)
        response("execute_vaccination_task", "task")
        VaccinationWriteReceipt.decode((gateway.execute(execute) as CommandOutcome.Success).receipt).validate(execute)
        val sent = body(); assertTrue(sent.contains("\"target_ids\":[\"B\",\"A\",\"B\"]"))
        assertTrue(sent.contains("\"executed_at\":\"$at\"")); assertFalse(sent.contains("finished_at"))
        val finish = FinishVaccinationTaskInput("task", null)
        response("finish_vaccination_task", "task")
        VaccinationWriteReceipt.decode((gateway.execute(finish) as CommandOutcome.Success).receipt).validate(finish)
        val finishWire = body(); assertTrue(finishWire.contains("\"finished_at\":null"))
        assertFalse(finishWire.contains("target_ids")); assertFalse(finishWire.contains("executed_at"))
    }
    @Test fun rejectedOrWrongTaskReceiptCannotConfirmAWrite() = runTest {
        val input = ExecuteVaccinationTaskInput("task", listOf("pig"), at)
        response("execute_vaccination_task", "", "FAILURE")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.execute(input)); body()
        response("execute_vaccination_task", "other")
        try { gateway.execute(input); fail() } catch (_: ApiFailure.Protocol) { }; body()
        server.enqueue(MockResponse().setResponseCode(401))
        try { gateway.execute(input); fail() } catch (_: ApiFailure.Authentication) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun receiptRejectsMalformedFieldsAndWrongOperation() {
        val input = ExecuteVaccinationTaskInput("task", listOf("B", "A", "B"), null)
        val receipt = VaccinationWriteReceipt("ExecuteVaccinationTask", "task")
        val encoded = receipt.encode(); assertEquals(receipt, VaccinationWriteReceipt.decode(encoded))
        receipt.validate(input)
        for (bad in listOf("{}", encoded + "true", encoded.replace("\"format\":1", "\"format\":\"1\""),
            encoded.replace("\"record\":\"task\"", "\"record\":\"task\",\"record\":\"other\""))) {
            try { VaccinationWriteReceipt.decode(bad); fail() } catch (_: Exception) { }
        }
        try { receipt.validate(FinishVaccinationTaskInput("task", null)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun finishDependsOnEveryPendingDoseAndBlocksLaterExecutions() {
        fun queued(id: String, input: VaccinationCommandInput, at: Long, status: CommandStatus = CommandStatus.QUEUED) =
            QueuedCommand(CommandRecord(id, id, scope, input.operationName, null, input.groupKeys, createdAtMillis = at, status = status), input)
        val a = queued("a", ExecuteVaccinationTaskInput("task", listOf("pig-a"), at), 100)
        val b = queued("b", ExecuteVaccinationTaskInput("task", listOf("pig-b"), at), 101)
        val other = queued("other", ExecuteVaccinationTaskInput("other-task", listOf("pig-c"), at), 1)
        val finish = FinishVaccinationTaskInput("task", at)
        assertEquals(setOf("a", "b"), CommandDependencies.required(finish, listOf(a, b, other)))
        val barrier = queued("finish", finish, 0)
        try { CommandDependencies.validateNew(ExecuteVaccinationTaskInput("task", listOf("pig-a"), at), emptySet(), listOf(barrier)); fail() }
        catch (failure: VaccinationQueueConflict) { assertEquals(VaccinationQueueConflictReason.FINISH_PENDING, failure.reason) }
        try { CommandDependencies.validateNew(finish, emptySet(), listOf(barrier)); fail() }
        catch (failure: VaccinationQueueConflict) { assertEquals(VaccinationQueueConflictReason.DUPLICATE_FINISH, failure.reason) }
        assertTrue(CommandDependencies.required(finish, listOf(a.copy(record = a.record.copy(status = CommandStatus.SYNCED)), b)).contains("b"))
        assertFalse(CommandDependencies.required(finish, listOf(a.copy(record = a.record.copy(status = CommandStatus.SYNCED)), b)).contains("a"))
        assertEquals(setOf("vaccination:task", "pig:pig-a"), a.record.groups)
    }
}
