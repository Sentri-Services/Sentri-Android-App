package com.sentri.nativeapp.pigs

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigProfileRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val remote = GraphqlPigProfiles(executor) { }
    private val at = "2026-10-10T10:00:00.1234567890123+08:00"
    private val operator = """{"id":"operator","display_name":"名字Case","avatar_url":""}"""
    private val row = """{
      "id":"pig","farm_id":"farm","ear_tag":"","ear_notch":"Notch:A","ear_tag_ex":"TagEx:A","role":"PRODUCTION_BOAR","sex":"MALE","lifecycle_status":"ACTIVE","origin":"SELFBRED",
      "location_code":"Z-S-U-R-1","birthday":"$at","entry_date":null,"remark":"","weight":123456789.12345678900,"sow_parity":0,"breed_type":"YLL","breeder_code":"B:Case","teat_count":0,
      "first_estrus_date":null,"last_estrus_date":"$at","last_farrowing_at":null,"last_lactation_at":null,"last_mating_at":"$at","last_production_status":null,"current_batch_id":"batch","death_image_urls":["https://fixture.invalid/death"],
      "current_breeder_status":{"production_status":"MATED","status_updated_at":"$at"},
      "current_batch":{"id":"batch","farm_id":"farm","sn":"SN:Case","status":"STARTED","line":{"name":"LineCase"},"tasks":[{"id":"task","status":"RUNNING","task_type":"MATING"}]},
      "semen_control":{"boar_id":"pig","farm_id":"farm","collection_status":"COOLDOWN","cooldown_until":"$at","last_collected_at":"$at","manually_marked_to_collect":false,"marked_to_collect_since":null,"modified_at":"$at","off_production":true,"off_production_since":"$at"},
      "location":{"zone_id":"zone","unit_id":"unit","zone_location":"Z","unit_location":"Z-S-U","section_location":null,"row_location":"Z-S-U-R","pen_location":"Z-S-U-R-1","pen_number":0,
        "zone":{"code":"Z","name_cn":"区","name_en":"Zone"},"unit":{"code":"U","name_cn":"单元","name_en":"Unit"},"section":null,"row":{"code":"R","row_number":"01"}},
      "healthy":{"id":"health","farm_id":"farm","triage_level":"Priority","triage_changed_at":"$at","modified_at":"$at","body_temperature_celsius":38.1234567890123456789,"body_temperature_operator_id":"operator","body_temperature_measured_at":"$at","triage_operator":$operator,"body_temperature_operator":$operator,
        "prescriptions_aggregate":{"aggregate":null},"prescriptions":[{"prescription_id":"hidden","prescription":null},{"prescription_id":"empty","prescription":{"therapies":[]}},{"prescription_id":"rx","prescription":{"therapies":[{"treatment":{"id":"t","name_cn":"治疗","name_en":"Treatment","detail_cn":"详情","detail_en":"Detail"},"medication":{"id":"m","name_cn":"药","name_en":"Medication"},"medication_catalog":null}]}}],
        "diseases":[{"disease_id":"d","created_at":"$at","operator":$operator,"disease":{"id":"d","name_cn":"病","name_en":"Disease","abbr_cn":"缩","abbr_en":"D","category_cn":"类","category_en":"Category","description_cn":"描述","description_en":"Description"}}],
        "symptoms":[{"target_id":"pig","symptom_id":"s","created_at":"$at","operator":null,"symptom":{"id":"s","name_cn":"症","name_en":"Symptom","part_cn":"部位","part_en":"Part","description_cn":"描述","description_en":"Description"}},{"target_id":"pig","symptom_id":"missing","created_at":null,"operator":null,"symptom":null}],
        "healthy_records":[{"id":7,"created_at":"$at","healthy_event":"FUTURE_EVENT","triage_level":"FUTURE_TRIAGE","remark":"","symptoms":{"number":0.1234567890123456789},"diseases":[],"operator_id":"operator","operator":$operator}]}
    }"""
    private fun enqueue(pigs: String = row, count: String = "\"count\":{\"aggregate\":{\"count\":1}}") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"pigs":[$pigs]${if(count.isEmpty()) "" else ",$count"}}}"""))
    }
    @After fun close() { client.close(); server.shutdown() }
    @Test fun fullWireMapsPreciseScalarsRelationsReferencesAndMissingValues() = runTest {
        enqueue()
        val page = remote.page(lease, PigProfileRequest())
        val pig = page.pigs.single(); val health = requireNotNull(pig.health)
        assertEquals(BigDecimal("123456789.12345678900"), pig.weight); assertEquals(BigDecimal("38.1234567890123456789"), health.temperature)
        assertEquals(at, pig.birthday); assertEquals(at, pig.lastMatingAt); assertEquals("TagEx:A", pig.extendedEarTag)
        assertEquals(0, pig.teatCount); assertEquals("", pig.earTag); assertEquals("B:Case", pig.breederCode); assertEquals("MATED", pig.breederProductionStatus)
        assertEquals(at, pig.breederStatusUpdatedAt)
        assertEquals("01", pig.location?.rowNumber); assertEquals("单元", pig.location?.unit?.nameCn); assertEquals("Unit", pig.location?.unit?.nameEn)
        assertEquals("MATING", pig.batch?.tasks?.single()?.kind); assertEquals("farm", pig.batch?.farmId)
        assertEquals(at, pig.semen?.cooldownUntil); assertEquals(false, pig.semen?.manuallyMarked); assertEquals(true, pig.semen?.offProduction)
        assertNull(health.prescriptionCount); assertNull(health.prescriptions[0].therapies); assertEquals(emptyList<PigTherapyReference>(), health.prescriptions[1].therapies)
        assertEquals("Detail", health.prescriptions[2].therapies?.single()?.treatment?.detailEn); assertEquals("m", health.prescriptions[2].therapies?.single()?.medication?.id)
        assertEquals("Category", health.diseases.single().detail?.categoryEn); assertNull(health.symptoms[1].detail)
        assertEquals("名字Case", health.triageOperator?.name); assertEquals("", health.triageOperator?.avatar)
        assertEquals("FUTURE_TRIAGE", health.history.single().triage); assertEquals("{\"number\":0.1234567890123456789}", health.history.single().symptoms?.encoded)
        assertEquals("[\"https://fixture.invalid/death\"]", pig.deathImages?.encoded)
        val request = server.takeRequest(); assertEquals("en", request.getHeader("sentri-language")); assertNull(request.getHeader("x-hasura-admin-secret")); assertNull(request.getHeader("x-hasura-role"))
    }
    @Test fun crossFarmOrMismatchedCurrentBatchDoesNotExposeItsSerial() = runTest {
        enqueue(pigs = row.replace("\"current_batch\":{\"id\":\"batch\",\"farm_id\":\"farm\"",
            "\"current_batch\":{\"id\":\"batch\",\"farm_id\":\"other\""))
        assertNull(remote.page(lease, PigProfileRequest()).pigs.single().batch)
        enqueue(pigs = row.replace("\"current_batch\":{\"id\":\"batch\"",
            "\"current_batch\":{\"id\":\"other\""))
        assertNull(remote.page(lease, PigProfileRequest()).pigs.single().batch)
    }
    @Test fun typedFiltersOrderingCountOptOutAndEmptyIdsHaveDistinctWireMeaning() = runTest {
        enqueue(pigs = row.replace("\"pen_location\":\"Z-S-U-R-1\"", "\"pen_location\":\"Pen:Case\""), count = "")
        val request = PigProfileRequest(PigFilter("Z-S-U", "Pen:Case", "Tag%_", 1), setOf("pig"), setOf(PigRole.PRODUCTION_BOAR), setOf(PigLifecycle.ACTIVE), "batch", PigProfileOrder.LOCATION_IDENTITY, 3, false)
        assertNull(remote.page(lease, request).total)
        val body = server.takeRequest().body.readUtf8()
        for (part in listOf("\"includeCount\":false", "\"offset\":3", "\"limit\":1", "PRODUCTION_BOAR", "ACTIVE", "current_batch_id", "_like", "_iregex", "Pen:Case")) assertTrue(part, body.contains(part))
        assertTrue(body.indexOf("\"location_code\":\"asc\"") < body.indexOf("\"ear_tag\":\"asc\""))
        enqueue(pigs = "", count = "")
        assertTrue(remote.page(lease, request.copy(ids = emptySet())).pigs.isEmpty())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"id\":{\"_in\":[]}"))
    }
    @Test fun inspectionRosterUsesExactUnitIdentityInsteadOfLocationRegex() = runTest {
        val request = PigProfileRequest(filter = PigFilter(pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = "unit")
        enqueue()
        val page = remote.page(lease, request)
        assertEquals("unit", page.pigs.single().location?.unitId)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"unit_id\":{\"_eq\":\"unit\"}"))
        assertFalse(body, body.contains("_iregex"))
        enqueue(row.replace("\"unit_id\":\"unit\"", "\"unit_id\":\"other\""))
        try { remote.page(lease, request); fail("Expected scope rejection") } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun inspectionSearchKeepsExactUnitAndMatchesTagNotchOrPen() = runTest {
        val request = PigProfileRequest(filter = PigFilter(search = "C1", pageSize = 100),
            unitId = "unit")
        enqueue()
        remote.page(lease, request)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"unit_id\":{\"_eq\":\"unit\"}"))
        assertTrue(body, body.contains("\"ear_tag\""))
        assertTrue(body, body.contains("\"ear_notch\""))
        assertTrue(body, body.contains("\"pen_location\":{\"_like\":\"%C1%\"}"))
        assertFalse(body, body.contains("_iregex"))
    }
    @Test fun inspectionSearchCoversCurrentFarmAcrossUnitsAndMatchesPenAndExtendedTag() = runTest {
        val request = PigProfileRequest(filter = PigFilter(search = "C1", pageSize = 30),
            lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY)
        enqueue(pigs = row.replace("\"unit_id\":\"unit\"", "\"unit_id\":\"other\""))
        assertEquals("other", remote.page(lease, request).pigs.single().location?.unitId)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(body, body.contains("\"lifecycle_status\":{\"_in\":[\"ACTIVE\"]}"))
        assertTrue(body, body.contains("\"ear_tag_ex\":{\"_like\":\"%C1%\"}"))
        assertTrue(body, body.contains("\"pen_location\":{\"_like\":\"%C1%\"}"))
        assertFalse(body, body.contains("\"unit_id\":{\"_eq\""))
        assertFalse(body, body.contains("_iregex"))
    }
    @Test fun scannerLookupUsesExactFarmEarIdentityAcrossUnits() = runTest {
        enqueue(pigs = row, count = "")
        assertEquals("pig", remote.exactEarCode(lease, "Notch:A").single().id)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"farm_id\":{\"_eq\":\"farm\"}"))
        assertTrue(body, body.contains("\"ear_notch\":{\"_eq\":\"Notch:A\"}"))
        assertTrue(body, body.contains("\"ear_tag_ex\":{\"_eq\":\"Notch:A\"}"))
        assertTrue(body, body.contains("\"limit\":2"))
        assertFalse(body, body.contains("_like"))
        assertFalse(body, body.contains("\"unit_id\":{\"_eq\""))
        enqueue(pigs = row.replace("\"ear_notch\":\"Notch:A\"", "\"ear_notch\":\"Notch:AB\""), count = "")
        try { remote.exactEarCode(lease, "Notch:A"); fail("Reject a partial match") }
        catch (_: ApiFailure.Protocol) { }
        server.takeRequest()
    }
    @Test fun inspectionPenUsesExactUnitAndPenIdentity() = runTest {
        val code = "Z-S-U-R-1"
        val request = PigProfileRequest(filter = PigFilter(penLocation = code, pageSize = 100),
            order = PigProfileOrder.LOCATION_IDENTITY, unitId = "unit")
        enqueue()
        assertEquals(code, remote.page(lease, request).pigs.single().location?.penLocation)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"unit_id\":{\"_eq\":\"unit\"}"))
        assertTrue(body, body.contains("\"pen_location\":{\"_eq\":\"$code\"}"))
        enqueue(row.replace("\"pen_location\":\"$code\"", "\"pen_location\":\"other\""))
        try { remote.page(lease, request); fail("Expected pen scope rejection") }
        catch (_: ApiFailure.Protocol) { }
    }
    @Test fun wrongScopeReferencesDuplicateRowsMissingCountAndMalformedDatesAreRejected() = runTest {
        val badRows = listOf(row.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"foreign\""),
            row.replace("\"boar_id\":\"pig\"", "\"boar_id\":\"foreign\""),
            row.replace("\"disease_id\":\"d\"", "\"disease_id\":\"foreign\""), row.replace(at, "bad-date"), "$row,$row")
        for (bad in badRows) {
            enqueue(bad)
            try { remote.page(lease, PigProfileRequest()); fail("Expected protocol rejection") } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
        enqueue(count = "\"count\":{\"aggregate\":null}")
        try { remote.page(lease, PigProfileRequest()); fail() } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun unknownReadCodesAreRetainedAndUnavailablePigRemainsAbsent() = runTest {
        enqueue(row.replace("PRODUCTION_BOAR", "FUTURE_ROLE").replace("COOLDOWN", "FUTURE_COLLECTION"))
        val pig = remote.page(lease, PigProfileRequest()).pigs.single()
        assertEquals("FUTURE_ROLE", pig.role); assertEquals("FUTURE_COLLECTION", pig.semen?.collectionStatus); server.takeRequest()
        enqueue("", "")
        val request = PigProfileRequest(ids = setOf("missing"), includeCount = false)
        assertTrue(remote.page(lease, request).pigs.isEmpty())
    }
    @Test fun contextChangedAfterResponseIsRejectedBeforeReturningProfiles() = runTest {
        var checks = 0
        val scoped = GraphqlPigProfiles(executor) { if (++checks == 2) throw RequestContextChanged() }
        enqueue()
        try { scoped.page(lease, PigProfileRequest()); fail() } catch (_: RequestContextChanged) { }
        assertEquals(1, server.requestCount)
    }
}
