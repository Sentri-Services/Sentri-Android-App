package com.sentri.nativeapp.sampling

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

class SamplingReadRemoteTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private val remote = GraphqlSamplingReads(GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }) { }
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private val pig = """{"id":"pig","farm_id":"farm","ear_tag":"P-1","ear_notch":null,"breeder_code":"B","breed_type":"YLL","lifecycle_status":"ACTIVE","location_code":"Z.U.P","origin":"SELFBRED","sex":"FEMALE","location":{"farm_id":"farm","zone_location":"Z","unit_location":"U","section_location":"S","row_location":"R","pen_location":"P","zone_id":"z","unit_id":"u","section_id":"s","row_id":"r","group_id":"g","id":1,"pen_number":0,"zone":{"code":"Z","name_cn":"区","name_en":"Zone"},"unit":{"code":"U","name_cn":"单元","name_en":"Unit"},"section":{"code":"S","name_cn":"段","name_en":"Section"},"row":{"code":"R","row_number":"01"}}}"""
    private val user = """{"id":"person","farm_id":"farm","first_name":"A","last_name":"B","username":"staff","display_name":"Worker","avatar_url":null}"""
    private fun target(id: Int, targetId: String, type: String, pigValue: String = "null", userValue: String = "null") =
        """{"id":$id,"farm_id":"farm","sampling_test_id":"test","target_id":"$targetId","target_type":"$type","pig":$pigValue,"user":$userValue}"""
    private fun row(targets: String = listOf(target(1,"pig","PIG",pig), target(2,"person","PERSON",userValue=user),
        target(3,"surface","ENVIRONMENT"), target(4,"feed","MATERIAL"), target(5,"misc","OTHER")).joinToString(","),
        count: Int = 5, sampled: Boolean = false, sampledAt: String = "null") =
        """{"id":"task","farm_id":"farm","sampling_id":"sampling","sampling_test_id":"test","created_at":"$at","created_by":"creator","disabled":false,"used":false,"is_sampled":$sampled,"sampled_at":$sampledAt,"sampled_by":null,"remark":null,"tested_at":null,"tested_by":null,"test_metric":null,"negative_value":null,"positive_value":null,"test_value":null,"test_result":null,"test_report_ids":[123456789.12345678900],"video_id":null,"targets":[$targets],"targets_aggregate":{"aggregate":{"count":$count}},"sampling_test":{"id":"test","farm_id":"farm","sampling_id":"sampling","sampling_code":"SC-1","project":"ASF","container":"BLOOD_TUBE","created_at":"$at","targets":[{"id":"target-plan","farm_id":"farm","sampling_test_id":"test","remark":null,"sample_type":"BLOOD","target_type":"PIG","target_ids":["pig",123456789.12345678900]}],"targets_aggregate":{"aggregate":{"count":1}}},"reports":[{"id":1,"farm_id":"farm","sampling_task_id":"task","report_id":"report","report":{"id":"report","farm_id":"farm","content_type":"application/pdf","created_at":"$at","name":"Result","operator_id":null}}],"reports_aggregate":{"aggregate":{"count":1}}}"""
    private fun send(value: String = row()) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"data":{"rows":[$value]}}"""))
    @After fun close() { client.close(); server.shutdown() }

    @Test fun allFiveTargetKindsAndNestedReferencesRetainIdentityAndJsonPrecision() = runTest {
        send()
        val page = remote.page(lease, SamplingPageRequest(limit = 5))
        val task = page.rows.single()
        assertEquals(listOf("PIG","PERSON","ENVIRONMENT","MATERIAL","OTHER"), task.targets.map { it.type })
        assertEquals("P-1", task.targets[0].pig?.earTag)
        assertEquals("01", task.targets[0].pig?.location?.rowNumber)
        assertEquals("Worker", task.targets[1].user?.displayName)
        assertNull(task.targets[2].pig); assertNull(task.targets[3].user)
        assertEquals("[123456789.12345678900]", task.testReportIds?.encoded)
        assertEquals("[\"pig\",123456789.12345678900]", task.samplingTest?.targets?.single()?.targetIds?.encoded)
        assertEquals("application/pdf", task.reports.single().contentType)
        assertFalse(page.hasMore)
        val request = server.takeRequest()
        assertEquals("en", request.getHeader("sentri-language"))
        assertNull(request.getHeader("x-hasura-farm-id"))
        assertTrue(request.body.readUtf8().contains("\"limit\":6"))
    }
    @Test fun fullListHomeWindowAndFarmScopedDetailAreSeparate() = runTest {
        send(); remote.page(lease, SamplingPageRequest(offset = 2, limit = 5))
        val full = server.takeRequest().body.readUtf8()
        assertFalse(full.contains("_gte")); assertTrue(full.contains("\"offset\":2"))
        val prefetch = SamplingPageRequest(SamplingReadMode.UNSAMPLED_OR_SAMPLED_IN_WINDOW,
            "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z")
        send(); remote.page(lease, prefetch)
        val filtered = server.takeRequest().body.readUtf8()
        for (part in listOf("_or", "is_sampled", "_gte", "_lt")) assertTrue(part, filtered.contains(part))
        send(); assertEquals("task", remote.detail(lease, "task")?.id)
        val detail = server.takeRequest().body.readUtf8()
        assertTrue(detail.contains("\"farmId\":\"farm\"")); assertTrue(detail.contains("\"_eq\":\"task\""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"rows":[]}}"""))
        assertNull(remote.detail(lease, "missing"))
    }
    @Test fun foreignOrTruncatedPolymorphicRowsAreProtocolErrors() = runTest {
        val bad = listOf(row(count = 6),
            row().replace("\"farm_id\":\"farm\",\"ear_tag\"", "\"farm_id\":\"other\",\"ear_tag\""),
            row(targets = listOf(target(1,"pig","PIG",pig), target(1,"person","PERSON",userValue=user)).joinToString(","),count=2),
            row().replace("\"sampling_task_id\":\"task\",\"report_id\"", "\"sampling_task_id\":\"other\",\"report_id\""),
            row().replace("\"id\":\"report\",\"farm_id\":\"farm\",\"content_type\"",
                "\"id\":\"report\",\"farm_id\":\"other\",\"content_type\""))
        for (value in bad) {
            send(value)
            try { remote.page(lease, SamplingPageRequest()); fail() } catch (_: ApiFailure.Protocol) { }
            server.takeRequest()
        }
    }
}
