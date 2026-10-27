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

class HomeUnitPenRosterRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val remote = GraphqlHomeUnitPenRosterRemote(executor) { }

    @After fun close() { client.close(); server.shutdown() }

    private fun row(index: Int, pen: String?, number: Int): String = """{
        "id":"pig-$index","farm_id":"farm","lifecycle_status":"ACTIVE",
        "location_code":${pen?.let { "\"$it\"" } ?: "null"},
        "location":{"unit_id":"unit-7","pen_location":${pen?.let { "\"$it\"" } ?: "null"},"pen_number":$number}
    }"""

    private fun enqueue(rows: String, count: Int) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"pigs":[$rows],"count":{"aggregate":{"count":$count}}}}"""))
    }

    private fun enqueuePens(rows: String, count: Int) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"pens":[$rows],"count":{"aggregate":{"count":$count}}}}"""))
    }

    private fun pen(id: Int, code: String): String =
        """{"id":$id,"farm_id":"farm","unit_id":"unit-7","pen_location":"$code","pen_number":$id}"""

    @Test fun completePagesProducePenCountsAndSeparateUnassignedPigs() = runTest {
        enqueue((0 until 199).joinToString(",") { row(it, "Z-A-7-P1", 1) } + "," + row(199, "Z-A-7-P2", 2), 201)
        enqueue(row(200, null, 0), 201)
        enqueuePens(listOf(pen(1, "Z-A-7-P1"), pen(2, "Z-A-7-P2"), pen(3, "Z-A-7-P3")).joinToString(","), 3)
        val result = remote.current(lease, "unit-7")
        assertEquals(201, result.total)
        assertEquals(1, result.unassigned)
        assertEquals(listOf(HomePenCount("Z-A-7-P1", 1, 199), HomePenCount("Z-A-7-P2", 2, 1)), result.pens)
        assertEquals(listOf("Z-A-7-P1", "Z-A-7-P2", "Z-A-7-P3"), result.registeredPens?.map(HomeRegisteredPen::code))
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        val third = server.takeRequest().body.readUtf8()
        assertTrue(first, first.contains("\"unit_id\":{\"_eq\":\"unit-7\"}"))
        assertTrue(first, first.contains("\"lifecycle_status\":{\"_eq\":\"ACTIVE\"}"))
        assertTrue(first, first.contains("\"offset\":0"))
        assertTrue(second, second.contains("\"offset\":200"))
        assertTrue(third, third.contains("NativeHomeUnitRegisteredPens"))
        assertTrue(third, third.contains("\"unitId\":\"unit-7\""))
    }

    @Test fun changingTotalBetweenPagesIsRejected() = runTest {
        enqueue((0 until 200).joinToString(",") { row(it, "Z-A-7-P1", 1) }, 201)
        enqueue(row(200, "Z-A-7-P1", 1), 202)
        try { remote.current(lease, "unit-7"); fail("Expected protocol rejection") }
        catch (_: ApiFailure.Protocol) { }
    }

    @Test fun incompleteRegisteredPenListIsRejectedRatherThanCallingUnseenPensEmpty() = runTest {
        enqueue("", 0)
        enqueuePens(pen(1, "Z-A-7-P1"), 2)
        enqueuePens("", 2)
        try { remote.current(lease, "unit-7"); fail("Expected incomplete registry rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
}
