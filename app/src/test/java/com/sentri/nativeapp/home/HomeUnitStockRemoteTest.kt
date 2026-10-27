package com.sentri.nativeapp.home

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.FakeAuthGateway
import com.sentri.nativeapp.auth.MemorySessionStore
import com.sentri.nativeapp.auth.validSession
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeUnitStockRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val remote = GraphqlHomeUnitStockRemote(executor) { }

    @After fun close() { client.close(); server.shutdown() }

    @Test fun exactUnitActiveStockCountsOnlyPensWithPigs() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"current":{"aggregate":{"count":86}},"occupied":{"aggregate":{"pens":8}}}}"""))
        assertEquals(HomeUnitStock("unit-7", 86, 8), remote.current(lease, "unit-7"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(body, body.contains("\"unit_id\":{\"_eq\":\"unit-7\"}"))
        assertTrue(body, body.contains("\"lifecycle_status\":{\"_eq\":\"ACTIVE\"}"))
        assertTrue(body, body.contains("\"pen_location\""))
        assertTrue(body, body.contains("\"_is_null\":false"))
        assertTrue(body, body.contains("\"_neq\":\"\""))
        assertTrue(body, body.contains("pens: count(columns: [location_code], distinct: true)"))
        assertFalse(body, body.contains("_iregex"))
    }

    @Test fun impossibleCountsAreRejected() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"current":{"aggregate":{"count":2}},"occupied":{"aggregate":{"pens":3}}}}"""))
        try { remote.current(lease, "unit-7"); fail("Expected protocol rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
}
