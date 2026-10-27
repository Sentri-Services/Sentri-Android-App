package com.sentri.nativeapp.production

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.NativeCheckEstrusMutation
import com.sentri.nativeapp.graphql.NativeSubmitEstrusStatusMutation
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class EstrusContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val language = AtomicReference("zh-CN")
    private val executor = GraphqlExecutor(client, auth) { language.get() }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 0)
    private val reads = GraphqlEstrusReads(executor) { }
    private val response = """{"data":{"tasks":[{"id":"task","batch_id":"batch","status":"RUNNING","task_type":"ESTRUS","batch":{"farm_id":"farm"},"pigs":[{"pig_id":"pig","disabled":false,"pig":{"id":"pig","farm_id":"farm","ear_tag":"TagA","ear_notch":null},"estrus_pig":{"task_id":"task","pig_id":"pig","check_status":"IN_ESTRUS","check_at":"2026-10-09T08:00:00+08:00","is_in_estrus":true}}]}]}}"""
    private fun enqueue(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `slice query scopes task and pigs preserves enum date and language`() = runTest {
        enqueue(response)
        val task = requireNotNull(reads.task(lease, "task"))
        assertEquals(EstrusStatus.IN_ESTRUS, task.pigs.single().checkStatus)
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertTrue(body.contains("farm_id")); assertTrue(body.contains("pig_id")); assertTrue(body.contains("NativeEstrusTask"))
        assertEquals("zh-CN", request.getHeader("sentri-language"))
        assertNull(request.getHeader("x-hasura-admin-secret"))
        enqueue(response.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""))
        try { reads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun `absent task differs from malformed response and input keeps explicit null`() = runTest {
        enqueue("""{"data":{"tasks":[]}}"""); assertNull(reads.task(lease, "missing"))
        enqueue(response.replace("IN_ESTRUS", "FUTURE_STATUS"))
        try { reads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        val input = CheckEstrusInput("task", "pig", EstrusStatus.NOT_IN_ESTRUS, null).graphql()
        assertEquals(Optional.Present<String?>(null), input.check_at)
        assertEquals("NOT_IN_ESTRUS", input.check_status.rawValue)
        try { CheckEstrusInput("task", "pig", EstrusStatus.IN_ESTRUS, "2026-02-30T00:00:00Z"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `language switch rejects delayed response without clearing authenticated session`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("zh-CN", request.getHeader("sentri-language"))
                language.set("en")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(response)
            }
        }
        try { reads.task(lease, "task"); fail() } catch (_: RequestContextChanged) { }
        assertNotNull(auth.session.value)
    }
    @Test fun `compiled mutation sends canonical input explicit null and no assumed dedupe header`() = runTest {
        enqueue("""{"data":{"production":{"check_estrus":{"status":"SUCCESS","errors":[]}}}}""")
        val result = executor.mutation(NativeCheckEstrusMutation(CheckEstrusInput("task", "pig", EstrusStatus.IN_ESTRUS, null).graphql()))
        assertEquals("SUCCESS", result.production.check_estrus.status.rawValue)
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertTrue(body.contains("\"check_at\":null"))
        assertTrue(body.contains("\"check_status\":\"IN_ESTRUS\""))
        assertTrue(body.contains("\"task_id\":\"task\""))
        assertTrue(body.contains("\"pig_id\":\"pig\""))
        assertNull(request.getHeader("Idempotency-Key"))
        assertEquals("zh-CN", request.getHeader("sentri-language"))
    }
    @Test fun `submit contract keeps ordered IDs explicit null default and override`() = runTest {
        val input = SubmitEstrusInput("task", listOf("pig2", "pig1"), null)
        assertEquals(Optional.Present<String?>(null), input.graphql().submit_at)
        assertEquals(Optional.Present(false), input.graphql().auto_estrus)
        enqueue("""{"data":{"production":{"submit_estrus_status":{"status":"SUCCESS","errors":[]}}}}""")
        val result = executor.mutation(NativeSubmitEstrusStatusMutation(input.graphql()))
        assertEquals("SUCCESS", result.production.submit_estrus_status.status.rawValue)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"pig_ids\":[\"pig2\",\"pig1\"]"))
        assertTrue(body.contains("\"submit_at\":null"))
        assertTrue(body.contains("\"auto_estrus\":false"))
        assertEquals("zh-CN", request.getHeader("sentri-language"))
        assertEquals(Optional.Present(true), input.copy(autoEstrus = true).graphql().auto_estrus)
        try { input.copy(pigIds = emptyList()); fail() } catch (_: IllegalArgumentException) { }
        try { input.copy(submitAt = "2026-10-09"); fail() } catch (_: IllegalArgumentException) { }
    }

}
