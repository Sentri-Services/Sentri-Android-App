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

class HomeTransferDetailRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val remote = GraphqlHomeTransferDetailRemote(executor) { }

    @After fun close() { client.close(); server.shutdown() }

    private fun enqueue(itemFarm: String = "farm") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"data":{"farm_view_transfer_task_by_pk":{
              "transfer_id":"tr-31","farm_id":"farm","batch_id":"batch-31",
              "transfer_type":"PRODUCTION_SOW","status":"IN_PROGRESS",
              "planned_transfer_time":"2026-10-20T08:00:00+08:00",
              "actual_start_time":null,"completed_at":null,"created_at":null,"modified_at":null,
              "remark":null,"operator_id":null,"batch":{"farm_id":"farm","sn":"31"},
              "transfer_out_unit":[{"farm_id":"farm","transfer_id":"tr-31","unit_code":"Z-A-7",
                "transfer_out_count":2,"transfer_total_count":5,"last_operation_time":null,"operator_id":null}],
              "transfer_in_unit":[{"farm_id":"farm","transfer_id":"tr-31","unit_code":"Z-B-9",
                "transfer_in_count":1,"last_operation_time":null,"operator_id":null}],
              "items":[{"farm_id":"$itemFarm","transfer_id":"tr-31","pig_id":"pig-1",
                "batch_id":"batch-31","status":"TRANSFER_OUT","source_location_code":"Z-A-7-P1",
                "destination_location_code":"Z-B-9-P1","actual_transfer_time":null,
                "failure_reason":null,"remark":null,"created_at":null,"modified_at":null,
                "operator_id":null,"pig":{"id":"pig-1","farm_id":"farm","ear_tag":"008",
                  "ear_notch":null,"location_code":"Z-A-7-P1","role":"SOW","lifecycle_status":"ACTIVE"}}]
            }}}
        """.trimIndent()))
    }

    @Test fun readsRecordedCountsAndItemStatusForExactlyOneTask() = runTest {
        enqueue()
        val detail = remote.task(lease, "tr-31")!!
        assertEquals(2, detail.from.single().recorded)
        assertEquals(1, detail.to.single().recorded)
        assertEquals("TRANSFER_OUT", detail.pigs.single().status)
        assertEquals("008", detail.pigs.single().earTag)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"id\":\"tr-31\""))
    }

    @Test fun rejectsForeignItemInsteadOfShowingItInsideThisFarm() = runTest {
        enqueue(itemFarm = "other")
        try { remote.task(lease, "tr-31"); fail("Expected foreign item rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
}
