package com.sentri.nativeapp.production

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProductionAuxiliaryContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 1)
    private val remote = GraphqlProductionAuxiliary(executor) { }
    private val at = "2026-10-09T09:00:00.1234567890123+08:00"
    private val batch = """{"id":"batch","farm_id":"farm","sn":"BatchCase","status":"STARTED","line_id":"line","line":{"id":"line","farm_id":"farm","name":"LineCase","status":"PAUSED"},"tasks":[{"id":"re","batch_id":"batch","task_type":"RE_ESTRUS","status":"RUNNING","batch_task":{"task_id":"re","batch_id":"batch","task_type":"RE_ESTRUS","planned_start_time":"$at","planned_duration_days":null}}]}"""
    private val recheck = """{"task_id":"re","pig_id":"pig","is_in_re_estrus":false,"check_status":"SIGNS_OF_RE_ESTRUS","check_at":"$at","re_estrus_at":null,"mating_at":"$at","mating_by":"operator","submitted_at":null,"operator_id":"operator","task":{"id":"re","batch_id":"batch","task_type":"RE_ESTRUS","batch":{"farm_id":"farm"}},"pig":{"id":"pig","farm_id":"farm","ear_tag":"TagCase:A","ear_notch":null,"location_code":"Z.U.P","location":{"farm_id":"farm","unit_id":"unit","unit_location":"单元Case","pen_location":"栏Case","pen_number":0,"zone":{"code":"Z","name_cn":"区Case","name_en":"ZoneCase"},"unit":{"code":"U","name_cn":"单元Case","name_en":"UnitCase"},"section":{"code":"S","name_cn":"分区Case","name_en":"SectionCase"},"row":{"code":"R","row_number":"01"}}}}"""
    private fun response(key: String, rows: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"$key":[$rows]}}""")) }
    private fun body(): String = server.takeRequest().let {
        assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8()
    }
    @After fun close() { client.close(); server.shutdown() }

    @Test fun batchPaginationKeepsLineStatePlansAndExtraRowProof() = runTest {
        response("batches", batch + "," + batch.replace("\"batch\"", "\"batch2\""))
        val request = BatchRequest.active().copy(lineId = "line", offset = 5, limit = 1)
        val value = remote.batches(lease, request)
        assertTrue(value.hasMore); assertEquals("BatchCase", value.batches.single().serial)
        assertEquals(ProductionLineStatus.PAUSED, value.batches.single().line?.status)
        assertEquals(at, value.batches.single().tasks.single().plan?.startedAt); assertNull(value.batches.single().tasks.single().plan?.durationDays)
        val sent = body()
        for (part in listOf("\"farmId\":\"farm\"", "\"offset\":5", "\"limit\":2", "\"_nlike\":\"LINE%\"",
            "\"STARTED\"", "\"PLANNING\"", "\"line_id\":{\"_eq\":\"line\"}")) assertTrue(part, sent.contains(part))
        assertFalse(sent.contains("aggregate")); assertTrue(sent.contains("farm_id"))
    }
    @Test fun absentAndDefaultBatchDetailsRemainDistinct() = runTest {
        response("batches", ""); assertNull(remote.batch(lease, "missing")); assertTrue(body().contains("\"id\":{\"_eq\":\"missing\"}"))
        response("batches", batch.replace("\"batch\"", "\"LINE-case\""))
        assertEquals("LINE-case", remote.batch(lease, "LINE-case")?.id); body()
        assertFalse(BatchRequest.active().accepts(ProductionBatch("LINE-case", "farm", "s", ProductionBatchStatus.STARTED, "line", null, emptyList())))
        assertTrue(BatchRequest.unfinished().accepts(ProductionBatch("batch", "farm", "s", ProductionBatchStatus.SKIPPED, "line", null, emptyList())))
    }
    @Test fun recheckViewKeepsLocalizedLocationExactTimeAndTaskPigIdentity() = runTest {
        response("pigs", recheck + "," + recheck.replace("\"re\"", "\"re2\""))
        val request = RecheckRequest(batchId = "batch", unitId = "unit", inReEstrus = false, limit = 1)
        val value = remote.rechecks(lease, request); assertTrue(value.hasMore)
        val pig = value.pigs.single()
        assertEquals("TagCase:A", pig.earTag); assertNull(pig.earNotch); assertEquals(at, pig.result.checkedAt)
        assertFalse(pig.result.isInReEstrus); assertEquals(ReEstrusStatus.SIGNS_OF_RE_ESTRUS, pig.result.status)
        assertEquals("区Case", pig.location?.zone?.chinese); assertEquals("UnitCase", pig.location?.unit?.english)
        assertEquals("01", pig.location?.rowNumber); assertEquals(0, pig.location?.penNumber)
        val sent = body()
        for (part in listOf("\"is_in_re_estrus\":{\"_eq\":false}", "\"limit\":2", "\"unit_id\":{\"_eq\":\"unit\"}",
            "task:{batch:{farm_id:", "pig:{farm_id:")) assertTrue(part, sent.replace(Regex("\\s+"), "").contains(part))
        val noLocation = recheck.substringBefore(",\"location\":") + ",\"location\":null}}"
        response("pigs", noLocation)
        assertNull(remote.rechecks(lease, RecheckRequest()).pigs.single().location); body()
    }
    @Test fun invalidRelationshipsCodesFiltersAndDuplicateRowsAreRefused() = runTest {
        val badBatches = listOf(batch.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""), batch.replace("\"task_id\":\"re\"", "\"task_id\":\"other\""),
            batch.replace("\"batch_id\":\"batch\"", "\"batch_id\":\"other\""), batch.replace("\"PAUSED\"", "\"FUTURE\""),
            batch.replace("\"batch\"", "\"LINE-case\""), "$batch,$batch", batch.replace("\"STARTED\"", "\"COMPLETED\""))
        for (bad in badBatches) { response("batches", bad); try { remote.batches(lease, BatchRequest.active()); fail() } catch (_: ApiFailure.Protocol) { }; body() }
        for (bad in listOf(recheck.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            recheck.replace("\"task_type\":\"RE_ESTRUS\"", "\"task_type\":\"PREGNANCY\""),
            recheck.replace("\"id\":\"pig\"", "\"id\":\"other\""), recheck.replace("\"SIGNS_OF_RE_ESTRUS\"", "\"FUTURE\""), "$recheck,$recheck")) {
            response("pigs", bad); try { remote.rechecks(lease, RecheckRequest()); fail() } catch (_: ApiFailure.Protocol) { }; body()
        }
        response("pigs", recheck); try { remote.rechecks(lease, RecheckRequest(inReEstrus = true)); fail() } catch (_: ApiFailure.Protocol) { }; body()
        response("batches", "$batch,$batch,$batch"); try { remote.batches(lease, BatchRequest(limit = 1)); fail() } catch (_: ApiFailure.Protocol) { }; body()
    }
    @Test fun changedScopeAndInvalidRequestCannotYieldUsableData() = runTest {
        response("batches", batch); var gates = 0
        try { GraphqlProductionAuxiliary(executor) { if (++gates == 2) throw RequestContextChanged() }.batches(lease, BatchRequest()); fail() } catch (_: RequestContextChanged) { }; body()
        for (invalid in listOf<() -> Any>({ BatchRequest(offset = -1) }, { BatchRequest(limit = 201) }, { RecheckRequest(unitId = " ") }, { RecheckRequest(limit = 0) })) {
            try { invalid(); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, server.requestCount)
    }
}
