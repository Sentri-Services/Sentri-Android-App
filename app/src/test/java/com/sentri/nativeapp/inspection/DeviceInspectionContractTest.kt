package com.sentri.nativeapp.inspection

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeviceInspectionContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlDeviceInspectionGateway(executor)
    private val reads = GraphqlDeviceInspectionReads(executor) { }
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private fun mutation(field: String, status: String, id: String) = server.enqueue(MockResponse()
        .setHeader("Content-Type", "application/json").setBody(
            """{"data":{"biosafety":{"$field":{"status":"$status","recordId":"$id","errors":[]}}}}"""))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun reportAndResolveUseBackendFieldsAndServerIdOnly() = runTest {
        val report = ReportDeviceInspectionInput("pen", DeviceType.Water, DeviceStatus.Fault, null, "水压低")
        mutation("report_device_inspection", "SUCCESS", "server-id")
        assertEquals(DeviceInspectionWriteReceipt("ReportDeviceInspection", "server-id"),
            DeviceInspectionWriteReceipt.decode((gateway.report(report) as CommandOutcome.Success).receipt))
        val body = server.takeRequest().body.readUtf8()
        for (part in listOf("\"pen_code\":\"pen\"", "\"device_type\":\"Water\"",
            "\"device_status\":\"Fault\"", "\"remark\":\"水压低\"", "\"inspection_id\":null"))
            assertTrue(part, body.contains(part))
        val resolve = ResolveDeviceInspectionInput(DeviceInspectionReference.Server("server-id"))
        mutation("resolve_device_inspection", "SUCCESS", "server-id")
        assertEquals(DeviceInspectionWriteReceipt("ResolveDeviceInspection", "server-id"),
            DeviceInspectionWriteReceipt.decode((gateway.resolve(resolve) as CommandOutcome.Success).receipt))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"inspection_id\":\"server-id\""))
        try { ResolveDeviceInspectionInput(DeviceInspectionReference.LocalReport("local-id")).graphql(); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun failureAndEmptySuccessfulIdDoNotCreateReceipt() = runTest {
        val report = ReportDeviceInspectionInput("pen", DeviceType.Feed)
        mutation("report_device_inspection", "FAILURE", "")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.report(report)); server.takeRequest()
        mutation("report_device_inspection", "SUCCESS", "")
        try { gateway.report(report); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
    @Test fun readRetainsBothResolvedAndUnresolvedRowsWithinFarm() = runTest {
        val row = """{"id":"id","farm_id":"farm","pen_code":"pen","device_type":"Water","device_name":null,"device_status":"Fault","remark":"水压低","reported_at":"2026-10-10T08:00:00.123456789+08:00","reporter_id":"worker","resolved_at":null,"resolver_id":null}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"farm_view_device_inspection":[$row],"count":{"aggregate":{"count":1}}}}"""))
        val value = reads.list(lease).single()
        assertEquals("2026-10-10T08:00:00.123456789+08:00", value.reportedAt)
        assertNull(value.resolvedAt)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"farm_id\":{\"_eq\":\"farm\"}"))
    }
    @Test fun penFaultReadUsesExactPenCodeAndRejectsOutsideRows() = runTest {
        val row = """{"id":"id","farm_id":"farm","pen_code":"pen-7","device_type":"Water","device_name":null,"device_status":"Fault","remark":"水压低","reported_at":null,"reporter_id":null,"resolved_at":null,"resolver_id":null}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"farm_view_device_inspection":[$row],"count":{"aggregate":{"count":1}}}}"""))
        assertEquals("id", reads.forPen(lease, "pen-7").single().id)
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(query.contains("\"pen_code\":{\"_eq\":\"pen-7\"}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"farm_view_device_inspection":[$row],"count":{"aggregate":{"count":1}}}}"""))
        try { reads.forPen(lease, "another-pen"); fail() } catch (_: ApiFailure.Protocol) { }
        server.takeRequest()
    }
    @Test fun incompleteDevicePageDoesNotBecomeAConfirmedCount() = runTest {
        val row = """{"id":"id","farm_id":"farm","pen_code":"pen","device_type":"Water","device_name":null,"device_status":"Fault","remark":null,"reported_at":null,"reporter_id":null,"resolved_at":null,"resolver_id":null}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"farm_view_device_inspection":[$row],"count":{"aggregate":{"count":2}}}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":{"farm_view_device_inspection":[],"count":{"aggregate":{"count":2}}}}"""))
        try { reads.list(lease); fail() } catch (_: ApiFailure.Protocol) { }
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"offset\":0") && first.contains("\"limit\":200"))
        assertTrue(second.contains("\"offset\":1"))
    }
}
