package com.sentri.nativeapp.pigs

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val remote = GraphqlPigRemote(GraphqlExecutor(client, auth))
    private val scope = DataScope("dev", "test-user", "user", "farm")
    private val pig = """{"__typename":"farm_view_pig","id":"p1","farm_id":"farm","ear_tag":"TagA","ear_notch":null,"location_code":"Z-S-U-1","role":"sow","sex":null,"lifecycle_status":"alive","last_production_status":null,"sow_parity":null,"weight":123.123456789,"birthday":"2026-01-01T00:00:00.123456+08:00","entry_date":null,"remark":null,"current_batch":null,"location":null,"healthy":null}"""
    private fun enqueue(body: String) = server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(body))
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `query combines scope literal search deterministic paging and typed scalars`() = runTest {
        enqueue("""{"data":{"pigs":[$pig],"count":{"aggregate":{"count":41}}}}""")
        val page = remote.page(scope, PigFilter("Z-S-U", search = "TagA"), 20)
        assertEquals(BigDecimal("123.123456789"), page.pigs.single().weight)
        assertTrue(page.hasNext)
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertTrue(body.contains("NativePigList"));assertTrue(body.contains("\"offset\":20"));assertTrue(body.contains("\"limit\":20"));assertTrue(body.contains("TagA"));assertTrue(body.contains("farm_id"))
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertNull(request.getHeader("x-hasura-admin-secret"))
    }
    @Test fun `cross farm response is rejected`() = runTest {
        enqueue("""{"data":{"pigs":[${pig.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\"")}],"count":{"aggregate":{"count":1}}}}""")
        try { remote.page(scope, PigFilter(), 0); fail("Cross farm response accepted") } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun `malformed timestamp is protocol failure instead of network fallback`() = runTest {
        enqueue("""{"data":{"pigs":[${pig.replace("2026-01-01T00:00:00.123456+08:00", "invalid-date")}],"count":{"aggregate":{"count":1}}}}""")
        try { remote.page(scope, PigFilter(), 0); fail("Bad timestamp accepted") } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun `list weight and detail temperature and health json never pass through rounded doubles`() = runTest {
        val weight = "123456789.12345678900"
        val temperature = "38.1234567890123456789"
        val symptoms = """{"future":[0.1234567890123456789,123456789012345678901]}"""
        val row = pig.replace("123.123456789", weight)
        enqueue("""{"data":{"pigs":[$row],"count":{"aggregate":{"count":1}}}}""")
        assertEquals(BigDecimal(weight), remote.page(scope, PigFilter(), 0).pigs.single().weight)
        val detailRow = row.replace("\"healthy\":null", """"death_image_urls":[],"healthy":{"triage_level":null,"body_temperature_celsius":$temperature,"symptoms":[],"diseases":[],"healthy_records":[{"id":1,"created_at":"2026-10-09T00:00:00Z","healthy_event":"SIMULATED","remark":null,"symptoms":$symptoms,"diseases":null}]}""")
        enqueue("""{"data":{"pigs":[$detailRow]}}""")
        val detail = requireNotNull(remote.detail(scope, "p1"))
        assertEquals(BigDecimal(weight), detail.pig.weight); assertEquals(BigDecimal(temperature), detail.pig.temperature)
        assertEquals(symptoms, detail.healthRecords.single().symptoms?.encoded)
        assertEquals("[]", detail.deathImages?.encoded)
    }
}
