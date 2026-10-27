package com.sentri.nativeapp.inspection

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class UnitInspectionContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlUnitInspectionGateway(executor)
    private val reads = GraphqlUnitInspectionReads(executor) { }
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private fun body(json: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun submitSendsNullableFieldsAndChecksExplicitId() = runTest {
        val input = SubmitUnitInspectionInput("unit", "client-id", temperature = BigDecimal("21.2500"),
            humidity = BigDecimal.ZERO, illumination = "sufficient", cleanliness = "excellent", compliance = "yes")
        body("""{"data":{"biosafety":{"submit_unit_inspection":{"status":"SUCCESS","recordId":"client-id","errors":[]}}}}""")
        assertEquals(UnitInspectionWriteReceipt("SubmitUnitInspection", "client-id"),
            UnitInspectionWriteReceipt.decode((gateway.submit(input) as CommandOutcome.Success).receipt))
        val request = server.takeRequest()
        assertEquals("en", request.getHeader("sentri-language"))
        val sent = request.body.readUtf8()
        for (fragment in listOf("\"unit_code\":\"unit\"", "\"unit_inspection_id\":\"client-id\"",
            "\"temperature\":21.25", "\"humidity\":0", "\"ammonia\":null",
            "\"cleanliness\":\"excellent\"", "\"compliance\":\"yes\"")) assertTrue(fragment, sent.contains(fragment))
        body("""{"data":{"biosafety":{"submit_unit_inspection":{"status":"SUCCESS","recordId":"other","errors":[]}}}}""")
        try { gateway.submit(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
    @Test fun remarkMayUseGeneratedIdAndReceiptMustStayNonempty() = runTest {
        val input = SubmitUnitRemarkInput("unit", "交班备注", null)
        body("""{"data":{"biosafety":{"submit_unit_remark":{"status":"SUCCESS","recordId":"generated-id","errors":[]}}}}""")
        assertEquals("generated-id", UnitInspectionWriteReceipt.decode((gateway.remark(input) as CommandOutcome.Success).receipt).recordId)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"remark\":\"交班备注\"")); assertTrue(sent.contains("\"unit_inspection_id\":null"))
        body("""{"data":{"biosafety":{"submit_unit_remark":{"status":"SUCCESS","recordId":"","errors":[]}}}}""")
        try { gateway.remark(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
    @Test fun readsRetainDecimalAndSeparateDayAndUnitFilters() = runTest {
        val row = """{"id":"inspection","farm_id":"farm","unit_code":"unit","created_at":"2026-10-10T08:00:00.123456789+08:00","operator_id":"worker","temperature":21.2500,"humidity":0,"ammonia":null,"co2":null,"illumination":"normal","ventilation":null,"water_consumption":123.4500,"feed_consumption":null,"env_remarks":null,"cleanliness":null,"compliance":null,"bio_remarks":null}"""
        body("""{"data":{"farm_view_unit_inspection":[$row]}}""")
        val value = reads.inspections(lease, "2026-10-10T00:00:00+08:00").single()
        assertEquals(BigDecimal("21.2500"), value.temperature)
        assertEquals(BigDecimal("123.4500"), value.waterConsumption)
        assertEquals("2026-10-10T08:00:00.123456789+08:00", value.createdAt)
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(query.contains("\"created_at\":{\"_gte\":\"2026-10-10T00:00:00+08:00\"}"))
        body("""{"data":{"farm_view_unit_remark":[{"farm_id":"farm","unit_code":"unit","remark":"交班备注","created_at":null,"operator_id":null}]}}""")
        assertEquals("交班备注", reads.remarks(lease, "unit").single().remark)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"unit_code\":{\"_eq\":\"unit\"}"))
    }
    @Test fun checkInConfirmationQueriesOnlyRequestedIdsAndExactUnit() = runTest {
        val cache = object : SnapshotCache {
            override fun read(scope: DataScope, kind: String, key: String): StoredSnapshot? = null
            override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) = error("unused")
            override fun readAccount(environment: String, oauthId: String, farmId: String, language: String?): StoredAccount? = null
            override fun clear(scope: DataScope) = Unit
        }
        val scoped = UnitInspectionReads(reads, cache, { })
        val row = """{"id":"client-id","farm_id":"farm","unit_code":"other-unit","created_at":null,"operator_id":null,"temperature":null,"humidity":null,"ammonia":null,"co2":null,"illumination":null,"ventilation":null,"water_consumption":null,"feed_consumption":null,"env_remarks":null,"cleanliness":null,"compliance":null,"bio_remarks":null}"""
        body("""{"data":{"farm_view_unit_inspection":[$row]}}""")
        assertTrue(scoped.confirmedIds(lease, mapOf("client-id" to "unit")).isEmpty())
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(query.contains("\"id\":{\"_in\":[\"client-id\"]}"))
        body("""{"data":{"farm_view_unit_inspection":[$row]}}""")
        assertEquals(setOf("client-id"), scoped.confirmedIds(lease, mapOf("client-id" to "other-unit")))
        server.takeRequest()
    }
    @Test fun latestCheckInUsesExactUnitAndServerOrdering() = runTest {
        val latest = GraphqlUnitCheckInHistoryRemote(executor) { }
        body("""{"data":{"farm_view_unit_inspection":[{"id":"check-in","farm_id":"farm","unit_code":"Z-S-U","created_at":"2026-10-21T09:41:00+08:00","operator":{"display_name":"Worker"}}]}}""")
        assertEquals(UnitCheckInSummary("check-in", "farm", "Z-S-U", "2026-10-21T09:41:00+08:00", "Worker"),
            latest.latest(lease, "Z-S-U"))
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(query.contains("\"unit_code\":{\"_eq\":\"Z-S-U\"}"))
        assertTrue(query.contains("limit: 1"))
        assertTrue(query.contains("created_at: desc_nulls_last"))
        body("""{"data":{"farm_view_unit_inspection":[{"id":"check-in","farm_id":"farm","unit_code":"other","created_at":null,"operator":null}]}}""")
        try { latest.latest(lease, "Z-S-U"); fail() } catch (_: ApiFailure.Protocol) { }
        server.takeRequest()
    }
    @Test fun recentCheckInsReadOneOrderedUnitPageAndRejectForeignRows() = runTest {
        val recent = GraphqlRecentUnitCheckInsRemote(executor) { }
        val row = { id: Int -> """{"id":"check-$id","farm_id":"farm","unit_code":"Z-S-U","created_at":"2026-10-21T09:41:00+08:00","operator":{"display_name":"Worker"}}""" }
        body("""{"data":{"farm_view_unit_inspection":[${(0..20).joinToString(",", transform = row)}]}}""")
        val page = recent.page(lease, "Z-S-U", 20)
        assertEquals(20, page.entries.size)
        assertEquals(20, page.offset)
        assertTrue(page.hasMore)
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(query.contains("\"unit_code\":{\"_eq\":\"Z-S-U\"}"))
        assertTrue(query.contains("\"offset\":20"))
        assertTrue(query.contains("limit: 21"))
        assertTrue(query.contains("created_at: desc_nulls_last"))
        body("""{"data":{"farm_view_unit_inspection":[{"id":"foreign","farm_id":"farm","unit_code":"OTHER","created_at":null,"operator":null}]}}""")
        try { recent.page(lease, "Z-S-U", 0); fail() } catch (_: ApiFailure.Protocol) { }
        server.takeRequest()
    }
    @Test fun valuesOutsideGraphqlFloatRangeAreRejectedBeforeQueueing() {
        for (value in listOf("1E+10000", "1E-10000")) {
            try { SubmitUnitInspectionInput("unit", temperature = BigDecimal(value)); fail() }
            catch (_: IllegalArgumentException) { }
        }
        val receipt = UnitInspectionWriteReceipt("SubmitUnitRemark", "id")
        for (bad in listOf("{}", receipt.encode() + "false", receipt.encode().replace("\"format\":1", "\"format\":2"))) {
            try { UnitInspectionWriteReceipt.decode(bad); fail() } catch (_: Exception) { }
        }
    }
}
