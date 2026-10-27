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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeTransferRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val remote = GraphqlHomeTransferRemote(executor) { }

    @After fun close() { client.close(); server.shutdown() }

    private fun enqueue(outFarm: String = "farm") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"data":{"rows":[{
              "transfer_id":"tr-31","farm_id":"farm","status":"IN_PROGRESS",
              "transfer_type":"PRODUCTION_SOW","planned_transfer_time":"2026-10-20T08:00:00+08:00",
              "batch_id":"batch-31","batch":{"farm_id":"farm","sn":"31"},
              "transfer_out_unit":[{"farm_id":"$outFarm","transfer_id":"tr-31","unit_code":"Z-A-7"}],
              "transfer_out_unit_aggregate":{"aggregate":{"count":1}},
              "transfer_in_unit":[{"farm_id":"farm","transfer_id":"tr-31","unit_code":"Z-B-9"}],
              "transfer_in_unit_aggregate":{"aggregate":{"count":1}}
            }],"total":{"aggregate":{"count":1}}}}
        """.trimIndent()))
    }

    @Test fun returnsExplicitRouteAndQueriesOnlyCurrentFarmOpenStates() = runTest {
        enqueue()
        val page = remote.page(lease, 0, 200)
        assertEquals(1, page.total)
        assertEquals(listOf("Z-A-7"), page.rows.single().fromUnitCodes)
        assertEquals(listOf("Z-B-9"), page.rows.single().toUnitCodes)
        assertEquals("31", page.rows.single().batchSerial)
        val request = server.takeRequest().body.readUtf8()
        assertTrue(request, request.contains("\"farmId\":\"farm\""))
        assertTrue(request, request.contains("PLANNED"))
        assertTrue(request, request.contains("IN_PROGRESS"))
    }

    @Test fun foreignRelationCannotBecomeCurrentFarmRoute() = runTest {
        enqueue("another")
        try { remote.page(lease, 0, 200); fail("Expected protocol rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
}
