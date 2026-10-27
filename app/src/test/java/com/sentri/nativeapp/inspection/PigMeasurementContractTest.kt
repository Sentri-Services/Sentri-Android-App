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
import java.math.BigDecimal

class PigMeasurementContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlPigMeasurementGateway(executor)
    private val reads = GraphqlPigMeasurementReads(executor) { }
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private fun body(json: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun weightRecordsKeepOrderDuplicatesAndDecimalText() = runTest {
        val input = RecordPigWeightsInput(listOf(PigWeightItem("a", BigDecimal("12.3400")),
            PigWeightItem("a", null, "", null), PigWeightItem("b", BigDecimal("9.5"))), BigDecimal("21.8400"))
        body("""{"data":{"production":{"record_pig_weights":{"status":"SUCCESS","recordIds":["a","a","b"],"errors":[]}}}}""")
        PigMeasurementReceipt.decode((gateway.send(input) as CommandOutcome.Success).receipt).validate(input)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"weight\":\"12.3400\""))
        assertTrue(sent.contains("\"total_weight\":\"21.8400\""))
        assertTrue(sent.contains("\"ear_tag\":\"\""))
        assertTrue(sent.indexOf("\"pig_id\":\"a\"") < sent.lastIndexOf("\"pig_id\":\"b\""))
    }
    @Test fun totalOnlyAndWrongReceiptAreDistinct() = runTest {
        val input = RecordPigWeightsInput(null, BigDecimal("100.000"))
        body("""{"data":{"production":{"record_pig_weights":{"status":"SUCCESS","recordIds":[],"errors":[]}}}}""")
        assertEquals(emptyList<String>(), PigMeasurementReceipt.decode((gateway.send(input) as CommandOutcome.Success).receipt).recordIds)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"records\":null"))
        body("""{"data":{"production":{"record_pig_weights":{"status":"SUCCESS","recordIds":["wrong"],"errors":[]}}}}""")
        try { gateway.send(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
    @Test fun backfatTemperatureAndFarmScopedRead() = runTest {
        val backfat = RecordPigBackfatInput("pig", BigDecimal("15.00"), PigBackfatLevel.MODERATE)
        body("""{"data":{"production":{"record_pig_backfat":{"status":"SUCCESS","recordId":"pig","errors":[]}}}}""")
        PigMeasurementReceipt.decode((gateway.send(backfat) as CommandOutcome.Success).receipt).validate(backfat)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"backfat_mm\":\"15.00\""))
        val temperature = RecordPigTemperatureInput("pig", BigDecimal("39.2500"), TemperatureUnit.Celsius)
        body("""{"data":{"production":{"record_pig_temperature":{"status":"SUCCESS","recordId":"pig","errors":[]}}}}""")
        PigMeasurementReceipt.decode((gateway.send(temperature) as CommandOutcome.Success).receipt).validate(temperature)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"temperature\":\"39.2500\""))
        body("""{"data":{"farm_view_pig":[{"id":"pig","farm_id":"farm","weight":"12.3400","backfat_mm":"15.00","backfat_level":"MODERATE","backfat_recorded_at":"2026-10-19T12:00:00+08:00","body_temperature":"39.2500","body_temperature_recorded_at":"2026-10-19T12:01:00+08:00"}]}}""")
        val rows = reads.measurements(lease, setOf("pig"))
        assertEquals("12.3400", rows.single().weight.toString())
        assertEquals("39.2500", rows.single().bodyTemperature.toString())
        val query = server.takeRequest().body.readUtf8()
        assertTrue(query.contains("\"farm_id\""))
        assertTrue(query.contains("\"_in\":[\"pig\"]"))
    }
    @Test fun wrongFarmReadIsRejected() = runTest {
        body("""{"data":{"farm_view_pig":[{"id":"pig","farm_id":"other","weight":null,"backfat_mm":null,"backfat_level":null,"backfat_recorded_at":null,"body_temperature":null,"body_temperature_recorded_at":null}]}}""")
        try { reads.measurements(lease, setOf("pig")); fail() } catch (_: ApiFailure.Protocol) { }
    }
}
