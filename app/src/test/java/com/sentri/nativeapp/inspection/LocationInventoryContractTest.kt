package com.sentri.nativeapp.inspection

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.CommandOutcome
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocationInventoryContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlLocationInventoryGateway(executor)
    private val reads = GraphqlLocationInventoryReads(executor) { }
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private fun body(json: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun reconciliationPreservesAllocationOrderAndNullableReason() = runTest {
        val input = ReconcileLocationInventoryInput("pen", 5,
            listOf(NonIdentityBatchAllocation("b2", 2), NonIdentityBatchAllocation("b1", 1)), "")
        body("""{"data":{"production":{"reconcile_location_inventory":{"status":"SUCCESS","location_code":"pen","errors":[]}}}}""")
        LocationInventoryReceipt.decode((gateway.reconcile(input) as CommandOutcome.Success).receipt).validate(input)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"target_count\":5"))
        assertTrue(sent.indexOf("\"batch_id\":\"b2\"") < sent.indexOf("\"batch_id\":\"b1\""))
        assertTrue(sent.contains("\"reason\":\"\""))
    }
    @Test fun contradictoryLocationAndWrongFarmAreRejected() = runTest {
        val input = ReconcileLocationInventoryInput("pen", 1, emptyList())
        body("""{"data":{"production":{"reconcile_location_inventory":{"status":"SUCCESS","location_code":"other","errors":[]}}}}""")
        try { gateway.reconcile(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
        body("""{"data":{"farm_view_pig":[{"id":"pig","farm_id":"other","location_code":"pen","lifecycle_status":"ACTIVE","ear_tag":"tag","ear_notch":null,"current_batch_id":"batch","location":{"unit_id":"unit"}}]}}""")
        try { reads.current(lease, "unit", "pen"); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
        body("""{"data":{"farm_view_pig":[{"id":"pig","farm_id":"farm","location_code":"pen","lifecycle_status":"ACTIVE","ear_tag":"tag","ear_notch":null,"current_batch_id":"batch","location":{"unit_id":"other"}}]}}""")
        try { reads.current(lease, "unit", "pen"); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
    @Test fun headcountReadMatchesBackendIdentityRule() = runTest {
        body("""{"data":{"farm_view_pig":[{"id":"a","farm_id":"farm","location_code":"pen","lifecycle_status":"ACTIVE","ear_tag":"tag","ear_notch":null,"current_batch_id":"b1","location":{"unit_id":"unit"}},{"id":"b","farm_id":"farm","location_code":"pen","lifecycle_status":"ACTIVE","ear_tag":null,"ear_notch":null,"current_batch_id":"b1","location":{"unit_id":"unit"}},{"id":"c","farm_id":"farm","location_code":"pen","lifecycle_status":"ACTIVE","ear_tag":"","ear_notch":null,"current_batch_id":"b2","location":{"unit_id":"unit"}}]}}""")
        val snapshot = reads.current(lease, "unit", "pen")
        assertEquals(3, snapshot.totalCount)
        assertEquals(1, snapshot.identityCount)
        assertEquals(mapOf("b1" to 1, "b2" to 1), snapshot.nonIdentityByBatch)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"farm_id\""))
        assertTrue(sent.contains("\"location_code\""))
        assertTrue(sent.contains("\"unit_id\""))
        assertTrue(sent.contains("\"ACTIVE\""))
    }
}
