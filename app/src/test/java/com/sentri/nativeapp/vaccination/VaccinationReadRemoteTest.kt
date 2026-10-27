package com.sentri.nativeapp.vaccination

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class VaccinationReadRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val remote = GraphqlVaccinationReads(GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }) { }
    private val at = "2026-10-10T08:00:00.1234567890123+08:00"
    private val pig = """{"id":"A","farm_id":"farm","ear_tag":"","ear_notch":null,"birthday":"$at","breed_type":"YLL","role":"PRODUCTION_BOAR","lifecycle_status":"ACTIVE","location_code":"Z.U.P","current_batch_id":"batch","current_batch":{"id":"batch","farm_id":"farm","sn":"B-1"},"location":{"farm_id":"farm","pen_location":"P","row_location":"R","section_location":"S","unit_location":"U","zone_location":"Z","pen_number":0,"zone":{"code":"Z","name_cn":"区","name_en":"Zone"},"section":{"code":"S","name_cn":"段","name_en":"Section"},"unit":{"code":"U","name_cn":"单元","name_en":"Unit"},"row":{"code":"R"}}}"""
    private fun dose(target: String, index: Int, status: String, pigValue: String = if (target == "A") pig else "null",
        exemption: String = if (target == "A") """{"farm_id":"farm","pig_id":"A","exemption_hit":true,"rule_codes":["HEALTH_ABNORMAL"],"reasons":["当前处于疾病/异常状态"],"evaluated_at":"$at","effective_until":null,"modified_at":"$at","operator_id":"operator"}""" else "null") =
        """{"farm_id":"farm","task_id":"task","target_id":"$target","vaccine_id":"vaccine","brand_id":"brand","dose_index":$index,"status":"$status","scheduled_at":"$at","executed_at":${if (status == "COMPLETED") "\"$at\"" else "null"},"executed_by":null,"operator_id":null,"vaccination_exemption":$exemption,"pig":$pigValue}"""
    private fun row(targets: String = listOf(dose("A", 1, "COMPLETED"), dose("A", 2, "PENDING"), dose("B", 1, "PENDING")).joinToString(","),
        count: Int = 3) = """{"__typename":"farm_view_vaccination_task","id":"task","farm_id":"farm","vaccine_id":"vaccine","brand_id":"brand","status":"IN_PROGRESS","scheduled_at":"$at","modified_at":"$at","created_at":"$at","completed_at":null,"executed_at":"$at","executed_by":null,"operator_id":null,"route":"IM","interval_value":7,"interval_unit":"DAY","dose_unit":"mL","dose_count":2,"dose":123456789.12345678900,"completed_target_dose_count":1,"exempted_target_count":0,"settled_target_count":0,"target_count":2,"task_type":"PLAN","plan_id":"plan","related_task_id":null,"effect_review":{"ratio":0.1234567890123456789},"is_deleted":false,"deleted_at":null,"vaccine":{"id":"vaccine","farm_id":"farm","name_cn":"疫苗","name_en":"Vaccine","status":"ACTIVE"},"brand":{"id":"brand","farm_id":"farm","vaccine_id":"vaccine","brand_name_cn":"品牌","brand_name_en":"Brand","dosage_form":"液体","status":"ACTIVE"},"targets":[$targets],"targets_aggregate":{"aggregate":{"count":$count}}}"""
    private fun list(value: String = row()) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"data":{"rows":[$value]}}"""))
    private fun detail(value: String = row()) = list(value)
    @After fun close() { client.close(); server.shutdown() }

    @Test fun multiDoseRowsAndPreciseValuesRemainDistinct() = runTest {
        list()
        val page = remote.page(lease, VaccinationPageRequest(limit = 1))
        val task = page.rows.single()
        assertEquals(BigDecimal("123456789.12345678900"), task.dose)
        assertEquals("{\"ratio\":0.1234567890123456789}", task.effectReview?.encoded)
        assertEquals(listOf(1, 2), task.targets.filter { it.targetId == "A" }.map { it.doseIndex })
        assertEquals(listOf("COMPLETED", "PENDING"), task.targets.filter { it.targetId == "A" }.map { it.status })
        assertEquals("B-1", task.targets.first().pig?.currentBatchSerial)
        assertEquals("Unit", task.targets.first().pig?.location?.unit?.english)
        assertTrue(task.targets.all { it.exemptionQueried })
        assertTrue(task.targets.first().exemption!!.hit)
        assertEquals("[\"HEALTH_ABNORMAL\"]", task.targets.first().exemption?.ruleCodes?.encoded)
        assertEquals(at, task.targets.first().exemption?.evaluatedAt)
        assertNull(task.targets.last().exemption)
        assertFalse(page.hasMore)
        val request = server.takeRequest(); assertEquals("en", request.getHeader("sentri-language"))
        assertNull(request.getHeader("x-hasura-farm-id")); assertTrue(request.body.readUtf8().contains("\"limit\":2"))
    }
    @Test fun foreignOptionalBatchSerialIsNotTrustedOrStored() = runTest {
        val foreignBatchPig = pig.replace("\"current_batch\":{\"id\":\"batch\",\"farm_id\":\"farm\"",
            "\"current_batch\":{\"id\":\"batch\",\"farm_id\":\"other\"")
        list(row(targets = listOf(dose("A", 1, "COMPLETED", pigValue = foreignBatchPig),
            dose("A", 2, "PENDING", pigValue = foreignBatchPig), dose("B", 1, "PENDING")).joinToString(",")))
        val task = remote.page(lease, VaccinationPageRequest()).rows.single()
        assertEquals("batch", task.targets.first().pig?.currentBatchId)
        assertNull(task.targets.first().pig?.currentBatchSerial)
        assertEquals("farm", task.targets.first().pig?.farmId)
    }
    @Test fun completeListPrefetchAndDetailUseDifferentFarmScopedRequests() = runTest {
        list(); remote.page(lease, VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE))
        val active = server.takeRequest().body.readUtf8()
        assertTrue(active.contains("\"_in\":[\"PENDING\",\"IN_PROGRESS\"]"))
        assertFalse(active.contains("_gte"))
        val prefetch = VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00Z", windowEnd = "2026-10-11T00:00:00Z")
        list(); remote.page(lease, prefetch)
        val filtered = server.takeRequest().body.readUtf8()
        for (part in listOf("_or", "DELETED", "COMPLETED", "_gte", "_lt", "2026-10-11T00:00:00Z")) assertTrue(part, filtered.contains(part))
        list(); remote.page(lease, VaccinationPageRequest(offset = 2, limit = 3))
        val full = server.takeRequest().body.readUtf8(); assertFalse(full.contains("_gte")); assertTrue(full.contains("\"offset\":2"))
        detail(); assertEquals("task", remote.detail(lease, "task")?.id)
        val byId = server.takeRequest().body.readUtf8(); assertTrue(byId.contains("\"farmId\":\"farm\"")); assertTrue(byId.contains("\"_eq\":\"task\""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"rows":[]}}"""))
        assertNull(remote.detail(lease, "missing"))
    }
    @Test fun truncatedOrForeignDoseHistoriesAreProtocolErrors() = runTest {
        val invalid = listOf(row(count = 4), row().replaceFirst("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            row().replace("\"dose_index\":2", "\"dose_index\":3"), row().replace("\"brand_id\":\"brand\"", "\"brand_id\":\"other\""))
        for (bad in invalid) {
            list(bad)
            try { remote.page(lease, VaccinationPageRequest()); fail() } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
    }
    @Test fun foreignOrConflictingExemptionsAreProtocolErrors() = runTest {
        val wrong = """{"farm_id":"farm","pig_id":"other","exemption_hit":true,"rule_codes":[],"reasons":[],"evaluated_at":"$at","effective_until":null,"modified_at":"$at","operator_id":null}"""
        val foreign = row(targets = listOf(dose("A", 1, "COMPLETED", exemption = wrong),
            dose("A", 2, "PENDING", exemption = wrong), dose("B", 1, "PENDING")).joinToString(","))
        val conflicting = row(targets = listOf(dose("A", 1, "COMPLETED"),
            dose("A", 2, "PENDING", exemption = "null"), dose("B", 1, "PENDING")).joinToString(","))
        for (bad in listOf(foreign, conflicting)) {
            list(bad)
            try { remote.page(lease, VaccinationPageRequest()); fail() } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
    }
}
