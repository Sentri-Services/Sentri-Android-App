package com.sentri.nativeapp.production

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TaskCommonContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 1)
    private val at = "2026-10-09T09:00:00.123456789+08:00"
    private val profile = """{"future":{"precision":0.1234567890123456789},"enabled":false}"""
    private val statistics = """{"count":2,"future":123456789.12345678900}"""
    private val row = """{"__typename":"farm_view_task","id":"task","batch_id":"batch","task_type":"MATING","status":"RUNNING","operator_id":"operator","actual_start_time":"$at","actual_end_time":null,"created_at":"$at","modified_at":null,
      "operator":{"id":"operator","display_name":"NameCase","avatar_url":null},
      "batch":{"id":"batch","farm_id":"farm","sn":"BatchCase","profile_config":$profile,"line":{"id":"line","farm_id":"farm","name":"LineCase","max_breeding_sows_per_batch":120},"tasks":[{"id":"task","batch_id":"batch","task_type":"MATING","status":"RUNNING"},{"id":"next","batch_id":"batch","task_type":"PREGNANCY","status":"PLANNED"}]},
      "batch_task":{"task_id":"task","batch_id":"batch","task_type":"MATING","planned_start_time":"$at","planned_duration_days":null},
      "pigs":[{"task_id":"task","batch_id":"batch","pig_id":"pig","disabled":false,"mark":"RemarkCase","pig":{"id":"pig","farm_id":"farm","ear_tag":"TagCase:A","ear_notch":null,"location_code":"z.u.p"}}],
      "mating_materials":[{"task_id":"task","id":7,"boar_id":"boar","semen_id":"semen","semen_breeder_type":null,"semen_collect_at":"$at","semen_count":2,"boar":{"id":"boar","farm_id":"farm","ear_tag":null,"ear_notch":"NotchCase"}}],
      "re_estrus_statistics":[{"task_id":"task","statistics_date":"$at","statistics":$statistics}],
      "nursery_locations":[{"task_id":"task","location_code":"z.u.p","check_at":"$at","created_at":null,"operator_id":null,"total_count":2,"total_weight":123456789.12345678900}]}"""
    private fun enqueue(text: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(text))
    private fun response(value: String = row, count: Int = if (value.isEmpty()) 0 else 1) = """{"data":{"tasks":[$value],"count":{"aggregate":{"count":$count}}}}"""
    private fun success(field: String) = enqueue("""{"data":{"production":{"$field":{"status":"SUCCESS","errors":[]}}}}""")
    private fun body() = server.takeRequest().let { assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8() }
    @After fun close() { client.close(); server.shutdown() }

    @Test fun `pig membership filter is scoped ordered exact and distinguishes null from an empty set`() = runTest {
        val request = TaskCatalogRequest(pigIds = linkedSetOf("pig", "Z:Case"), statuses = setOf(ProductionTaskStatus.PLANNED, ProductionTaskStatus.RUNNING))
        enqueue(response()); assertEquals("task", GraphqlTaskCatalog(executor) {}.page(lease, request).tasks.single().id)
        val sent = body(); assertTrue(sent.contains("\"farmId\":\"farm\"")); assertTrue(sent.contains("\"pig_id\":{\"_in\":[\"Z:Case\",\"pig\"]}"))
        enqueue(response("")); assertTrue(GraphqlTaskCatalog(executor) {}.page(lease, request.copy(pigIds = emptySet())).tasks.isEmpty())
        assertTrue(body().contains("\"pig_id\":{\"_in\":[]}"))
        enqueue(response()); try { GraphqlTaskCatalog(executor) {}.page(lease, request.copy(pigIds = setOf("PIG"))); fail() } catch (_: ApiFailure.Protocol) { }
    }

    @Test fun `common mutations preserve batch kind null remark empty remark and exact time`() = runTest {
        val mark = MarkTaskPigInput("task", "pig", "batch", null)
        success("mark_task_pig"); executor.mutation(NativeMarkTaskPigMutation(mark.graphql()))
        val first = body(); assertTrue(first.contains("\"remark\":null")); assertTrue(first.contains("\"batch_id\":\"batch\"")); assertTrue(first.contains("\"pig_id\":\"pig\""))
        success("mark_task_pig"); executor.mutation(NativeMarkTaskPigMutation(mark.copy(remark = "").graphql()))
        assertTrue(body().contains("\"remark\":\"\""))
        success("complete_task"); executor.mutation(NativeCompleteProductionTaskMutation(CompleteTaskInput("task", "batch", ProductionTaskKind.MATING, at).graphql()))
        val end = body(); assertTrue(end.contains("\"task_type\":\"MATING\"")); assertTrue(end.contains(at))
        success("complete_task"); executor.mutation(NativeCompleteProductionTaskMutation(CompleteTaskInput("task", "batch", ProductionTaskKind.NURSERY, null).graphql()))
        assertTrue(body().contains("\"actual_end_time\":null"))
    }
    @Test fun `metadata includes plans siblings pig references materials raw json and exact decimal`() = runTest {
        enqueue(response())
        val value = requireNotNull(GraphqlTaskCatalog(executor) { }.metadata(lease, "task"))
        assertEquals(ProductionTaskKind.MATING, value.kind); assertEquals("BatchCase", value.batch.serial)
        assertEquals(profile, value.batch.profile?.encoded); assertEquals(statistics, value.recheckStatistics.single().value?.encoded)
        assertEquals(2, value.batch.tasks.size); assertEquals("LineCase", value.batch.line?.name)
        assertEquals(at, value.plan?.startedAt); assertNull(value.plan?.durationDays); assertNull(value.endedAt)
        assertEquals("TagCase:A", value.pigs.single().earTag); assertEquals("RemarkCase", value.pigs.single().mark); assertEquals("NotchCase", value.matingMaterials.single().boarEarNotch)
        assertEquals(BigDecimal("123456789.12345678900"), value.nurseryLocations.single().totalWeight)
        val sent = body(); assertTrue(sent.contains("\"farmId\":\"farm\"")); assertTrue(sent.contains("\"id\":{\"_eq\":\"task\"}"))
    }
    @Test fun `home catalog applies farm filters half open completed window and stable order`() = runTest {
        val request = TaskCatalogRequest.activeAndCompleted("2026-10-09T00:00:00+08:00", "2026-10-10T00:00:00+08:00")
            .copy(kinds = setOf(ProductionTaskKind.MATING), batchId = "batch", lineId = "line", operatorId = "operator", offset = 5, limit = 10)
        enqueue(response(count = 20)); val page = GraphqlTaskCatalog(executor) { }.page(lease, request)
        assertEquals(20, page.total); assertEquals(request, page.request)
        val sent = body()
        for (fragment in listOf("\"farmId\":\"farm\"", "_and:", "\"_or\"", "\"SCHEDULED\"", "\"RUNNING\"", "\"COMPLETED\"", "\"_gte\"", "\"_lt\"", "\"batch_id\"", "\"line_id\"", "\"operator_id\"", "\"offset\":5", "\"limit\":10", "\"created_at\":\"desc\"", "\"id\":\"desc\"")) assertTrue(fragment, sent.contains(fragment))
        enqueue(response(count = 20)); GraphqlTaskCatalog(executor) { }.page(lease, request.copy(order = TaskCatalogOrder.PLANNED_ASC))
        val ordered = body(); assertTrue(ordered.contains("\"planned_start_time\":\"asc\"")); assertTrue(ordered.contains("\"id\":\"asc\""))
    }
    @Test fun `catalog refuses foreign relationships duplicate rows wrong filters and totals`() = runTest {
        val invalid = listOf(row.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            row.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""),
            row.replace("\"batch_id\":\"batch\"", "\"batch_id\":\"other\""),
            row.replace("\"id\":\"operator\",\"display_name\"", "\"id\":\"other\",\"display_name\""),
            row.replace("\"total_count\":2", "\"total_count\":-1"), row.replace("RUNNING", "FUTURE"))
        for (bad in invalid) { enqueue(response(bad)); try { GraphqlTaskCatalog(executor) { }.metadata(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { } }
        for ((data, request) in listOf(response("$row,$row", 2) to TaskCatalogRequest(), response(count = 0) to TaskCatalogRequest(),
            response(count = 1) to TaskCatalogRequest(kinds = setOf(ProductionTaskKind.NURSERY)), response(count = 1) to TaskCatalogRequest(batchId = "other"))) {
            enqueue(data); try { GraphqlTaskCatalog(executor) { }.page(lease, request); fail() } catch (_: ApiFailure.Protocol) { }
        }
    }
    @Test fun `absence and context changes are separate from a valid metadata snapshot`() = runTest {
        enqueue(response("")); assertNull(GraphqlTaskCatalog(executor) { }.metadata(lease, "missing")); body()
        enqueue(response()); var checks = 0
        try { GraphqlTaskCatalog(executor) { if (++checks == 2) throw RequestContextChanged() }.metadata(lease, "task"); fail() } catch (_: RequestContextChanged) { }
    }
    @Test fun `completed window respects offsets submillisecond precision and every other filter`() = runTest {
        enqueue(response()); val value = requireNotNull(GraphqlTaskCatalog(executor) { }.metadata(lease, "task"))
        val start = "2026-10-09T09:00:00.123456789+08:00"
        val until = "2026-10-09T01:00:00.123456790Z"
        val request = TaskCatalogRequest.activeAndCompleted(start, until).copy(batchId = "batch", kinds = setOf(ProductionTaskKind.MATING))
        assertTrue(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = start)))
        assertFalse(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = until)))
        assertFalse(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = "2026-10-09T01:00:00.123456788Z")))
        assertFalse(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = start, batch = value.batch.copy(id = "other"))))
        assertFalse(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = start, kind = ProductionTaskKind.NURSERY)))
        assertFalse(request.accepts(value.copy(status = ProductionTaskStatus.COMPLETED, endedAt = null)))
        assertTrue(request.accepts(value)); assertFalse(request.copy(statuses = emptySet()).accepts(value))
    }
    @Test fun `invalid identities pagination and dates cannot become common requests`() {
        val invalid: List<() -> Any> = listOf({ MarkTaskPigInput("task", " ", "batch", null) }, { CompleteTaskInput("task", "batch", ProductionTaskKind.ESTRUS, "bad") },
            { TaskCatalogRequest(offset = -1) }, { TaskCatalogRequest(limit = 0) }, { TaskCatalogRequest(limit = 201) }, { TaskCatalogRequest(batchId = " ") },
            { TaskTimeWindow(at, at) }, { TaskTimeWindow("2026-02-30T00:00:00Z", "2026-03-01T00:00:00Z") },
            { TaskCatalogRequest(statuses = setOf(ProductionTaskStatus.COMPLETED), completedWindow = TaskTimeWindow("2026-10-09T00:00:00Z", "2026-10-10T00:00:00Z")) })
        invalid.forEach { try { it(); fail() } catch (_: IllegalArgumentException) { } }; assertEquals(0, server.requestCount)
    }
}
