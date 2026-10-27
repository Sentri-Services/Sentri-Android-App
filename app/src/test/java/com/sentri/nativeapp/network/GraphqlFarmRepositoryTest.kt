package com.sentri.nativeapp.network

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.FakeAuthGateway
import com.sentri.nativeapp.auth.MemorySessionStore
import com.sentri.nativeapp.auth.SessionExpired
import com.sentri.nativeapp.auth.validSession
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GraphqlFarmRepositoryTest {
    private val server = MockWebServer()
    private val gateway = FakeAuthGateway()
    private val auth = AuthRepository(MemorySessionStore(validSession()), gateway) { 0 }
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val repository = GraphqlFarmRepository(client, auth) { "zh-CN" }
    private fun enqueue(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/json").setBody(body))
    private val farms = """{"data":{"biosafety":{"farm_selection_list":{"status":"SUCCESS","records":[{"oauth_id":"test-user","user_id":"u","farm_id":"farm","is_choosed":true,"is_default":false}],"errors":[]}}}}"""
    private val me = """{"data":{"biosafety":{"me":{"status":"SUCCESS","record":{"user_id":"u","username":"test","display_name":"Tester","role":"user","access_modules":[]},"errors":[]}}}}"""
    private val farmInfo = """{"data":{"biosafety":{"farm_info":{"id":"farm","name":"Farm","domain":"","is_initialized":true}}}}"""
    private val expired = """{"errors":[{"message":"JWTExpired","extensions":{"code":"invalid-jwt"}}]}"""

    @After fun close() { client.close(); server.shutdown() }

    @Test fun `native request sends current token language and exact operation`() = runTest {
        enqueue(farms)
        assertEquals("farm", repository.choices("test-user").single().farmId)
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("zh-CN", request.getHeader("sentri-language"))
        assertNull(request.getHeader("x-hasura-admin-secret"))
        assertNull(request.getHeader("x-hasura-role"))
        assertTrue(request.body.readUtf8().contains("GetFarmSelectionList"))
    }

    @Test fun `query authentication failure refreshes and retries once`() = runTest {
        enqueue(expired); enqueue(farms)
        repository.choices("test-user")
        assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-1", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, gateway.refreshCalls)
        assertEquals(2, server.requestCount)
    }

    @Test fun `HTTP 401 has the same refresh behavior`() = runTest {
        enqueue("{}", 401); enqueue(farms)
        repository.choices("test-user")
        assertEquals(1, gateway.refreshCalls)
        assertEquals(2, server.requestCount)
    }

    @Test fun `repeated authentication failure clears session instead of looping`() = runTest {
        enqueue(expired); enqueue(expired)
        try { repository.choices("test-user"); fail("Expected expired session") } catch (_: SessionExpired) { }
        assertEquals(2, server.requestCount)
        assertNull(auth.session.value)
    }

    @Test fun `mutation is never replayed after authentication failure`() = runTest {
        enqueue(expired)
        try { repository.choose(FarmChoice("farm", "test-user", false, false)); fail("Expected failure") }
        catch (_: ApiFailure.Authentication) { }
        assertEquals(1, server.requestCount)
        assertEquals(1, gateway.refreshCalls)
    }

    @Test fun `business failure preserves session without refreshing`() = runTest {
        enqueue("""{"data":{"biosafety":{"farm_selection_list":{"status":"FAILURE","records":[],"errors":[{"code":"permission","err_msg":"No permission"}]}}}}""")
        try { repository.choices("test-user"); fail("Expected business failure") }
        catch (error: ApiFailure.Business) { assertEquals("No permission", error.detail) }
        assertNotNull(auth.session.value)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test fun `HTTP 503 is a network failure and preserves session`() = runTest {
        enqueue("{}", 503)
        try { repository.choices("test-user"); fail("Expected network failure") } catch (_: ApiFailure.Network) { }
        assertNotNull(auth.session.value)
        assertEquals(0, gateway.refreshCalls)
        assertEquals(1, server.requestCount)
    }

    @Test fun `account reads an authoritative farm timezone without changing farm identity`() = runTest {
        enqueue(me); enqueue(farmInfo)
        enqueue("""{"data":{"biosafety":{"farm_info":{"id":"farm","timezone":"Asia/Shanghai"}}}}""")
        val account = repository.account()
        assertEquals("farm", account.farm.id)
        assertEquals("Asia/Shanghai", account.farm.timezone)
        assertTrue(server.takeRequest().body.readUtf8().contains("GetMe"))
        assertTrue(server.takeRequest().body.readUtf8().contains("GetFarmInfo"))
        assertTrue(server.takeRequest().body.readUtf8().contains("GetFarmTimezone"))
    }

    @Test fun `older farm schema does not prevent account access or supply a guessed timezone`() = runTest {
        enqueue(me); enqueue(farmInfo)
        enqueue("""{"errors":[{"message":"Cannot query field 'timezone' on type 'FarmInfo'"}]}""")
        val account = repository.account()
        assertEquals("farm", account.farm.id)
        assertNull(account.farm.timezone)
        assertNotNull(auth.session.value)
    }
}
