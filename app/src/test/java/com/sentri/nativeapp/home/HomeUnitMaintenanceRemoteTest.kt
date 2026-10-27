package com.sentri.nativeapp.home

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.FakeAuthGateway
import com.sentri.nativeapp.auth.MemorySessionStore
import com.sentri.nativeapp.auth.validSession
import com.sentri.nativeapp.inspection.DeviceInspection
import com.sentri.nativeapp.inspection.DeviceInspectionRemote
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

class HomeUnitMaintenanceRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val deviceRows = listOf(
        DeviceInspection("fault-1", "farm", "P1", "Feed", null, "Fault", null, null, null, null, null),
        DeviceInspection("fault-2", "farm", "P2", "Water", null, "Normal", null, null, null, null, null),
        DeviceInspection("elsewhere", "farm", "P9", "Feed", null, "Fault", null, null, null, null, null))
    private val devices = object : DeviceInspectionRemote {
        override suspend fun list(lease: ScopeLease) = deviceRows
        override suspend fun forPen(lease: ScopeLease, penCode: String) = error("Not used")
    }
    private val remote = GraphqlHomeUnitMaintenanceRemote(executor, devices) { }

    @After fun close() { client.close(); server.shutdown() }

    private fun pen(id: Int, code: String) =
        """{"id":$id,"farm_id":"farm","unit_id":"unit-7","pen_location":"$code","pen_number":$id}"""

    private fun enqueuePens(rows: String, count: Int) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"pens":[$rows],"count":{"aggregate":{"count":$count}}}}"""))
    }

    @Test fun onlyExactRegisteredPensContributeOpenIssues() = runTest {
        enqueuePens("${pen(1, "P1")},${pen(2, "P2")}", 2)
        val result = remote.current(lease, "unit-7")
        assertEquals(setOf("P1", "P2"), result.penCodes)
        assertEquals(listOf("fault-1", "fault-2"), result.records.map(DeviceInspection::id))
        assertEquals(listOf("fault-1"), result.open.map(DeviceInspection::id))
        val request = server.takeRequest().body.readUtf8()
        assertTrue(request, request.contains("\"unitId\":\"unit-7\""))
    }

    @Test fun incompleteRegistryCannotProduceAFalseZero() = runTest {
        enqueuePens(pen(1, "P1"), 2)
        enqueuePens("", 2)
        try { remote.current(lease, "unit-7"); fail("Expected protocol rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
}
