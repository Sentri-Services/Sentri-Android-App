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

class CareContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val executor = GraphqlExecutor(client, auth) { "en" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 0)
    private val at = "2026-10-09T09:00:00.123456789+08:00"
    private val weight = BigDecimal("123456789.12345678900")
    private fun enqueue(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    private fun success(field: String) = enqueue("""{"data":{"production":{"$field":{"status":"SUCCESS","errors":[]}}}}""")
    private fun body(): String = server.takeRequest().let { assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8() }
    private fun task(type: String, field: String, result: String) = """{"data":{"tasks":[{"id":"task","batch_id":"batch","status":"RUNNING","task_type":"$type","batch":{"farm_id":"farm"},"pigs":[{"pig_id":"sow","disabled":false,"pig":{"id":"sow","farm_id":"farm","ear_tag":"SowTag","ear_notch":null,"location_code":"z.u.p","location":{"unit_id":"unit","unit_location":"z.u","pen_location":"z.u.p"}},"$field":$result}]}]}}"""
    private val identity = """{"task_id":"task","pig_id":"sow","piglet_seq":1,"birth_weight":123456789.12345678900,"ear_tag":"TagCase:A","ear_notch":null,"sex":"FEMALE"}"""
    private val farrowingResult = """{"task_id":"task","pig_id":"sow","status":"FARROWING","is_disabled":false,"adopt_count":1,"foster_count":0,"no_identity_count":2,"first_farrowing_at":"$at","last_farrowing_at":"$at","mating_at":null,"mating_by":null,"re_check_at":null,"operator_id":"operator","identity_piglets":[$identity],"piglet_statistic":[{"task_id":"task","pig_id":"sow","piglet_type":"HEALTHY","count":3}],"records":[{"task_id":"task","pig_id":"sow","id":7,"alive_count":3,"dead_count":0,"submitted_at":"$at","operator_id":"operator"}]}"""
    private val pigletResult = """{"task_id":"task","pig_id":"sow","is_disabled":false,"is_farrowed":true,"adopt_count":1,"foster_count":0,"no_identity_count":2,"female_count":2,"male_count":1,"litter_number":3,"last_farrowing_at":"$at","total_weight":123456789.12345678900,"items":{"fraction":0.1234567890123456789},"identity_piglets":[$identity],"todo_items":[{"task_id":"task","pig_id":"sow","item_type":"LIGAMENT_CUTTING","plan_days":0,"completed_at":"$at","operator_id":"operator"}]}"""
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `farrowing mutation retains partial state zero and null versus empty counts`() = runTest {
        val input = SubmitFarrowingInput("task", "sow", false, listOf(PigletCount(FarrowingPigletType.MUMMY, 0)), null)
        success("submit_farrowing_piglet")
        assertEquals("SUCCESS", executor.mutation(NativeSubmitFarrowingPigletMutation(input.graphql())).production.submit_farrowing_piglet.status.rawValue)
        var sent = body(); assertTrue(sent.contains("\"is_completed\":false")); assertTrue(sent.contains("\"count\":0")); assertTrue(sent.contains("\"submit_at\":null"))
        for ((counts, expected) in listOf(null to "null", emptyList<PigletCount>() to "[]")) {
            success("submit_farrowing_piglet"); executor.mutation(NativeSubmitFarrowingPigletMutation(input.copy(counts = counts).graphql()))
            sent = body(); assertTrue(sent.contains("\"piglet_counts\":$expected"))
        }
    }
    @Test fun `piglet item and information inputs preserve selections identity and decimal wire strings`() = runTest {
        success("submit_piglet_handling_item")
        executor.mutation(NativeSubmitPigletHandlingItemMutation(SubmitPigletItemsInput("task", "sow", listOf(PigletHandlingItem.TEETH_CLIPPING, PigletHandlingItem.LIGAMENT_CUTTING), at).graphql()))
        assertTrue(body().contains("\"items\":[\"TEETH_CLIPPING\",\"LIGAMENT_CUTTING\"]"))
        val input = SubmitPigletInfoInput("task", "sow", 2, 1, listOf(IdentityPigletInput(weight, "TagCase:A", null, false, PigletSex.FEMALE)), weight, null, "reason", null)
        success("submit_piglet_info"); executor.mutation(NativeSubmitPigletInfoMutation(input.graphql()))
        val sent = body(); assertTrue(sent.contains("\"birth_weight\":\"123456789.12345678900\"")); assertTrue(sent.contains("\"total_weight\":\"123456789.12345678900\""))
        assertTrue(sent.contains("\"is_breeder\":false")); assertTrue(sent.contains("TagCase:A")); assertTrue(sent.contains("\"increase_reason\":null"))
        success("submit_piglet_info"); executor.mutation(NativeSubmitPigletInfoMutation(input.copy(identities = emptyList()).graphql()))
        assertTrue(body().contains("\"identity_piglets\":null"))
    }
    @Test fun `weight mutation keeps exact decimal and business refusal`() = runTest {
        enqueue("""{"data":{"production":{"submit_piglet_total_weight":{"status":"FAILURE","errors":[{"err_msg":"invalid_count"}]}}}}""")
        val result = executor.mutation(NativeSubmitPigletTotalWeightMutation(SubmitPigletWeightInput("task", "sow", weight, at).graphql())).production.submit_piglet_total_weight
        assertEquals("invalid_count", result.errors.single().err_msg)
        val sent = body(); assertTrue(sent.contains("\"total_weight\":\"123456789.12345678900\"")); assertTrue(sent.contains(at))
    }
    @Test fun `unified foster derives stage at backend while retaining recipient selection and zero`() = runTest {
        for (stage in FosterStage.entries) {
            val input = FosterPigletInput("task", "sow", "adopt", stage, listOf("TagB", "TagA"), null, 0, null)
            success("foster_piglet"); executor.mutation(NativeFosterPigletMutation(input.graphql()))
            val sent = body(); assertTrue(sent.contains("\"adopt_pig_id\":\"adopt\"")); assertTrue(sent.contains("\"ear_tags\":[\"TagB\",\"TagA\"]"))
            assertTrue(sent.contains("\"ear_notches\":null")); assertTrue(sent.contains("\"no_identity_count\":0")); assertFalse(sent.contains("\"stage\""))
        }
        val input = FosterPigletInput("task", "sow", "adopt", FosterStage.FARROWING, null, emptyList(), 1, at)
        success("foster_farrowing_piglet"); executor.mutation(NativeFosterFarrowingPigletMutation(input.legacyFarrowingGraphql())); assertTrue(body().contains("\"ear_notches\":[]"))
        success("foster_piglet_handling"); executor.mutation(NativeFosterPigletHandlingMutation(input.copy(stage = FosterStage.PIGLET_PROCESSING).legacyPigletGraphql())); assertTrue(body().contains(at))
    }
    @Test fun `queries preserve nested piglet data numeric precision time and raw json`() = runTest {
        enqueue(task("FARROWING", "farrowing_pig", farrowingResult))
        val farrowing = requireNotNull(GraphqlFarrowingReads(executor) { }.task(lease, "task"))
        val result = requireNotNull(farrowing.pigs.single().result)
        assertEquals(FarrowingStatus.FARROWING, result.status); assertEquals(at, result.firstAt); assertEquals(weight, result.identities.single().birthWeight)
        assertEquals(3, result.records.single().aliveCount); assertEquals(FarrowingPigletType.HEALTHY, result.counts.single().type)
        assertTrue(body().contains("farm_id"))
        enqueue(task("PIGLET_PROCESSING", "piglet_handling_pig", pigletResult))
        val piglet = requireNotNull(GraphqlPigletReads(executor) { }.task(lease, "task")).pigs.single().result!!
        assertEquals(weight, piglet.totalWeight); assertEquals(PigletHandlingItem.LIGAMENT_CUTTING, piglet.todo.single().type)
        assertEquals("{\"fraction\":0.1234567890123456789}", piglet.items?.encoded); assertEquals(at, piglet.todo.single().completedAt)
        body()
    }
    @Test fun `foreign nested owners unknown codes duplicate identities and negative quantities reject whole query`() = runTest {
        val reads = GraphqlFarrowingReads(executor) { }
        val response = task("FARROWING", "farrowing_pig", farrowingResult)
        for (bad in listOf(response.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            response.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""),
            response.replace("\"pig_id\":\"sow\",\"piglet_seq\"", "\"pig_id\":\"other\",\"piglet_seq\""),
            response.replace("\"FARROWING\"", "\"FUTURE\""), response.replace("\"HEALTHY\"", "\"FUTURE\""),
            response.replace("\"count\":3", "\"count\":-3"), response.replace("[$identity]", "[$identity,$identity]"))) {
            enqueue(bad); try { reads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        }
        enqueue(task("PIGLET_PROCESSING", "piglet_handling_pig", pigletResult.replace("LIGAMENT_CUTTING", "FUTURE")))
        try { GraphqlPigletReads(executor) { }.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        enqueue("""{"data":{"tasks":[]}}"""); assertNull(reads.task(lease, "missing"))
        enqueue(task("FARROWING", "farrowing_pig", "null")); assertNull(reads.task(lease, "task")!!.pigs.single().result)
    }
    @Test fun `invalid care IDs weights and timestamp fail before network`() {
        val invalid: List<() -> Any> = listOf(
            { SubmitPigletWeightInput("task", " ", weight, null) }, { SubmitPigletWeightInput("task", "sow", BigDecimal("-1"), null) },
            { SubmitPigletInfoInput("task", "sow", -1, 0, null, null, null, null, null) },
            { SubmitFarrowingInput("task", "sow", true, emptyList(), "2026-02-30T00:00:00Z") },
            { FosterPigletInput("task", "sow", "sow", FosterStage.FARROWING, null, null, 0, null) })
        invalid.forEach { try { it(); fail() } catch (_: IllegalArgumentException) { } }
        assertEquals(0, server.requestCount)
    }
}
