package com.sentri.nativeapp.sampling

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

class SamplingContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val gateway = GraphqlSamplingGateway(GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" })
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private fun response(status: String, id: String, record: String = "null") = server.enqueue(MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"data":{"biosafety":{"submit_sampled":{"status":"$status","recordId":"$id","record":$record,"errors":[]}}}}"""))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun wireContainsOnlyTaskAndNullableRemarkAndAcceptsRealResolverShape() = runTest {
        val input = SubmitSampledInput("task", null)
        response("SUCCESS", "task")
        SamplingWriteReceipt.decode((gateway.submit(input) as CommandOutcome.Success).receipt).validate(input)
        val request = server.takeRequest()
        assertEquals("en", request.getHeader("sentri-language"))
        assertNull(request.getHeader("x-hasura-farm-id"))
        assertNull(request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"sampling_task_id\":\"task\""))
        assertTrue(body.contains("\"remark\":null"))
        assertFalse(body.contains("sampled_at"))
        assertFalse(body.contains("sampled_by"))
    }
    @Test fun wrongOrContradictorySuccessIdsAndAuthenticationFailureAreRejected() = runTest {
        val input = SubmitSampledInput("task", "原始备注")
        response("FAILURE", "")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.submit(input)); server.takeRequest()
        response("SUCCESS", "other")
        try { gateway.submit(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
        response("SUCCESS", "task", """{"sampling_task_id":"other"}""")
        try { gateway.submit(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(401))
        try { gateway.submit(input); fail() } catch (_: ApiFailure.Authentication) { }
    }
    @Test fun receiptRejectsWrongOperationAndMalformedFields() {
        val input = SubmitSampledInput("task", "")
        val receipt = SamplingWriteReceipt("SubmitSampled", "task")
        assertEquals(receipt, SamplingWriteReceipt.decode(receipt.encode()))
        receipt.validate(input)
        for (bad in listOf("{}", receipt.encode() + "true",
            receipt.encode().replace("\"format\":1", "\"format\":\"1\""),
            receipt.encode().replace("\"task\":\"task\"", "\"task\":\"task\",\"task\":\"other\""))) {
            try { SamplingWriteReceipt.decode(bad); fail() } catch (_: Exception) { }
        }
        try { receipt.validate(SubmitSampledInput("other", null)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun sameTaskDuplicateIsBlockedButOtherTaskIsIndependent() {
        val input = SubmitSampledInput("task", null)
        fun queued(id: String, status: CommandStatus) = QueuedCommand(CommandRecord(id, id, scope,
            input.operationName, null, input.groupKeys, createdAtMillis = 0, status = status), input)
        for (status in listOf(CommandStatus.QUEUED, CommandStatus.FAILED, CommandStatus.IN_FLIGHT,
            CommandStatus.UNCERTAIN, CommandStatus.SYNCED)) {
            try { CommandDependencies.validateNew(input, emptySet(), listOf(queued("previous", status))); fail() }
            catch (failure: SamplingQueueConflict) {
                assertEquals(if (status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.SYNCED))
                    SamplingQueueConflictReason.RESULT_UNCONFIRMED else SamplingQueueConflictReason.DUPLICATE_SUBMISSION,
                    failure.reason)
            }
        }
        CommandDependencies.validateNew(input, emptySet(), listOf(queued("cancelled", CommandStatus.CANCELLED)))
        CommandDependencies.validateNew(SubmitSampledInput("other", null), emptySet(), listOf(queued("previous", CommandStatus.QUEUED)))
        assertEquals(setOf("sampling:task"), input.groupKeys)
    }
}
