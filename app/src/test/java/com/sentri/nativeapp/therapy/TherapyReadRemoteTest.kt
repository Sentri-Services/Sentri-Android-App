package com.sentri.nativeapp.therapy

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TherapyReadRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val remote = GraphqlTherapyReads(GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }) { }
    private val at = "2026-10-10T10:00:00.1234567890123+08:00"
    private val pig = """{"id":"pig","farm_id":"farm","breeder_code":"B","ear_notch":null,"ear_tag":"","lifecycle_status":"ACTIVE","location_code":"Z.U.P","origin":"SELFBRED","sex":"MALE","breed_type":"YLL","birthday":"$at","teat_count":0,"role":"PRODUCTION_BOAR","current_batch_id":null,"location":{"farm_id":"farm","pen_location":"P","row_location":"R","section_location":"S","unit_location":"U","zone_location":"Z","pen_number":0,"zone":{"name_cn":"区","name_en":"Zone"},"section":{"name_cn":"段","name_en":"Section"},"unit":{"name_cn":"单元","name_en":"Unit"},"row":{"code":"R"}}}"""
    private val row = """{"id":"therapy","farm_id":"farm","prescription_id":"rx","created_at":"$at","dosage":123456789.12345678900,"interval":0,"medication_id":"med","medication_catalog_id":"cat","operator_id":"operator","remark":"","start_date":"$at","status":"InProgress","times":2,"treatment_id":"treatment","unit":"ml","medication":{"id":"med","farm_id":"farm","name_cn":"药","name_en":"Medication"},"medication_catalog":{"id":"cat","farm_id":"farm","medication_id":"med","name_cn":"剂型","name_en":"Form","form_cn":"口服","form_en":"Oral"},"treatment":{"id":"treatment","name_cn":"治疗","name_en":"Treatment","detail_cn":"详情","detail_en":"Detail"},"prescription":{"id":"rx","farm_id":"farm","created_at":"$at","operator_id":"operator","symptoms":{"level":0.1234567890123456789},"diseases":[],"remark":null,"status":"InProgress","operator":{"display_name":"医生","avatar_url":null},"therapies_aggregate":{"aggregate":{"count":1}}},"tasks":[{"farm_id":"farm","prescription_id":"rx","therapy_id":"therapy","task_id":"task","target_id":"pig","times":1,"status":"InProgress","scheduled_at":"$at","executed_at":null,"executed_by":null,"operator_id":"operator","remark":null,"pig":$pig}]}"""
    private fun enqueue(rows: String = row) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"data":{"rows":[$rows]}}"""))
    private fun enqueueContext(targets: String = """{"farm_id":"farm","prescription_id":"rx","target_id":"pig"}""",
        targetCount: Int = 1, therapyCount: Int = 1) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"data":{"value":{"id":"rx","farm_id":"farm","status":"InProgress","targets":[$targets],"targets_aggregate":{"aggregate":{"count":$targetCount}},"therapies":[{"id":"therapy","farm_id":"farm","prescription_id":"rx","status":"InProgress"}],"therapies_aggregate":{"aggregate":{"count":$therapyCount}}}}}"""))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun fullTherapyWireRetainsDecimalJsonTimeAndNestedIdentity() = runTest {
        enqueue()
        val page = remote.page(lease, TherapyPageRequest(limit = 1))
        val value = page.rows.single()
        assertEquals(BigDecimal("123456789.12345678900"), value.dosage)
        assertEquals(at, value.createdAt); assertEquals("{\"level\":0.1234567890123456789}", value.prescription?.symptoms?.encoded)
        assertEquals("Oral", value.medicationCatalog?.formEnglish); assertEquals("Unit", value.tasks.single().pig?.location?.unit?.english)
        assertEquals(0, value.tasks.single().pig?.teatCount); assertFalse(page.hasMore)
        val request = server.takeRequest()
        assertEquals("en", request.getHeader("sentri-language")); assertNull(request.getHeader("x-hasura-role"))
        assertTrue(request.body.readUtf8().contains("\"limit\":2"))
    }
    @Test fun prefetchAndFullListUseDistinctFiltersAndDetailUsesFarmAndId() = runTest {
        val prefetch = TherapyPageRequest(mode = TherapyReadMode.ACTIVE_OR_EXECUTED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00.000Z", windowEnd = "2026-10-11T00:00:00.000Z")
        enqueue(); remote.page(lease, prefetch)
        val body = server.takeRequest().body.readUtf8()
        for (part in listOf("_or", "InProgress", "NotStarted", "_gte", "_lt", "2026-10-11T00:00:00.000Z")) assertTrue(part, body.contains(part))
        enqueue(); remote.page(lease, TherapyPageRequest(prescriptionId = "rx", statuses = setOf("InProgress"), offset = 2, limit = 3))
        val full = server.takeRequest().body.readUtf8()
        assertFalse(full.contains("_gte")); assertTrue(full.contains("\"offset\":2")); assertTrue(full.contains("prescription_id"))
        enqueue(); assertEquals("therapy", remote.detail(lease, "therapy")?.id)
        val detail = server.takeRequest().body.readUtf8()
        assertTrue(detail.contains("\"farmId\":\"farm\"")); assertTrue(detail.contains("\"id\":{\"_eq\":\"therapy\"}"))
    }
    @Test fun referencesAreFarmScopedWhileTreatmentIsGlobal() = runTest {
        enqueue("""{"id":"med","farm_id":"farm","name_cn":"药","name_en":"Medication"}""")
        assertEquals("med", remote.medications(lease).single().id)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"farmId\":\"farm\""))
        enqueue("""{"id":"cat","farm_id":"farm","medication_id":"med","name_cn":"剂型","name_en":"Form","form_cn":null,"form_en":""}""")
        assertEquals("", remote.catalogs(lease).single().formEnglish); server.takeRequest()
        enqueue("""{"id":"treatment","name_cn":"治疗","name_en":"Treatment","detail_cn":"详情","detail_en":"Detail"}""")
        assertEquals("treatment", remote.treatments(lease).single().id)
        assertFalse(server.takeRequest().body.readUtf8().contains("farmId"))
    }
    @Test fun invalidForeignFarmAndRelationsDoNotBecomeTrustedRows() = runTest {
        val badRows = listOf(row.replaceFirst("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            row.replace("\"therapy_id\":\"therapy\"", "\"therapy_id\":\"other\""),
            row.replace("\"medication_id\":\"med\"", "\"medication_id\":\"other\""), "$row,$row")
        for (bad in badRows) {
            enqueue(bad)
            try { remote.page(lease, TherapyPageRequest()); fail("Expected protocol rejection") } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
    }
    @Test fun unknownReadStatusAndOffsetAwareDailyMembershipArePreserved() = runTest {
        enqueue(row.replace("\"status\":\"InProgress\"", "\"status\":\"FutureStatus\""))
        val value = remote.page(lease, TherapyPageRequest()).rows.single()
        assertEquals("FutureStatus", value.status)
        server.takeRequest()
        val request = TherapyPageRequest(mode = TherapyReadMode.ACTIVE_OR_EXECUTED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00.000Z", windowEnd = "2026-10-11T00:00:00.000Z")
        val finished = value.copy(status = "Finished", tasks = value.tasks.map { it.copy(executedAt = at) })
        assertTrue(request.accepts(finished))
        assertFalse(request.accepts(finished.copy(tasks = finished.tasks.map { it.copy(executedAt = "2026-10-09T23:59:59.999Z") })))
        try { request.copy(windowEnd = "2026-10-09T00:00:00.000Z"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun prescriptionContextRequiresCompleteFarmScopedMembership() = runTest {
        enqueueContext()
        assertEquals(TherapyPrescriptionContext("rx", "farm", "InProgress", setOf("pig"), mapOf("therapy" to "InProgress")),
            remote.context(lease, "rx"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"prescriptionId\":\"rx\""))
        for (bad in listOf({ enqueueContext(targetCount = 2) },
            { enqueueContext(targets = """{"farm_id":"other","prescription_id":"rx","target_id":"pig"}""") },
            { enqueueContext(therapyCount = 2) })) {
            bad()
            try { remote.context(lease, "rx"); fail() } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"value":null}}"""))
        assertNull(remote.context(lease, "missing"))
    }
}
