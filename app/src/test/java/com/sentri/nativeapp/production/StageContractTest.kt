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

class StageContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val executor = GraphqlExecutor(client, auth) { "zh-CN" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 0)
    private val at = "2026-10-09T09:00:00.123456789+08:00"
    private val weight = BigDecimal("123456789.12345678900")
    private val assessment = SowAssessment(ActivityLevel.LAME, BackfatThickness.THIN, BodyConditionScore.SCORE_2,
        FeedingSituation.REDUCED_INTAKE, MastitisSeverity.NO_MASSTITIS, MilkProductionCapacity.POOR, SecretionStatus.NORMAL, ProlapseType.RECTAL, false, null)
    private val growing = GrowingAssessment(2, 0, 1, 1, weight, 0)
    private val identity = GrowingIdentityInput("pig", "z.u.p", true, false, "TagCase:A", null, PigletSex.FEMALE, weight)
    private val rawAssessment = """{"body_condition_score":"SCORE_2","prolapse_type":"RECTAL","prolapse_recurred":false,"prolapse_resolved":null,"future":{"precision":0.1234567890123456789}}"""
    private val sowFields = """"task_id":"task","pig_id":"pig","is_disabled":false,"check_at":"$at","check_status":$rawAssessment,"description":"desc","farrowing_at":null,"is_farrowed":true,"is_obsoleted":false,"obsoleted_reason":null,"operator_id":"operator""""
    private val postpartumResponse = task("POSTPARTUM", "postpartum_pig", "{$sowFields}")
    private val lactationFields = """{$sowFields,"female_count":1,"male_count":1,"no_identity_count":1,"init_total_count":null,"total_weight":123456789.12345678900,"identity_piglets":[{"task_id":"task","pig_id":"pig","piglet_seq":1,"birth_weight":123456789.12345678900,"ear_tag":"TagCase:A","ear_notch":null,"sex":"FEMALE","is_breeder":true}],"piglet_counts":[{"task_id":"task","pig_id":"pig","piglet_type":"HEALTHY","count":2}]}"""
    private val growingFields = """{"task_id":"task","pig_id":"pig","has_identity":true,"is_breeder":false,"location_code":"z.u.p"}"""
    private val locationFields = """, "nursery_locations":[{"task_id":"task","location_code":"z.u.p","check_at":"$at","created_at":"$at","operator_id":"operator","total_count":2,"total_weight":123456789.12345678900}]"""
    private fun task(type: String, field: String, result: String, extra: String = "") = """{"data":{"tasks":[{"id":"task","batch_id":"batch","status":"RUNNING","task_type":"$type","batch":{"farm_id":"farm"},"pigs":[{"pig_id":"pig","disabled":false,"pig":{"id":"pig","farm_id":"farm","ear_tag":"TagCase:A","ear_notch":null,"weight":123456789.12345678900,"sex":"FEMALE","location_code":"z.u.p","location":{"unit_id":"unit","unit_location":"z.u","pen_location":"z.u.p"}},"$field":$result}]$extra}]}}"""
    private fun enqueue(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    private fun success(field: String) = enqueue("""{"data":{"production":{"$field":{"status":"SUCCESS","errors":[]}}}}""")
    private fun body(): String = server.takeRequest().let { assertEquals("zh-CN", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8() }
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `postpartum wire preserves nullable prolapse and explicit obsoletion`() = runTest {
        val input = SubmitPostpartumInput("task", "pig", assessment, null, null, false, null)
        success("submit_postpartum_pig_status")
        assertEquals("SUCCESS", executor.mutation(NativeSubmitPostpartumPigStatusMutation(input.graphql())).production.submit_postpartum_pig_status.status.rawValue)
        val sent = body()
        assertTrue(sent.contains("\"prolapse_type\":\"RECTAL\"")); assertTrue(sent.contains("\"prolapse_recurred\":false")); assertTrue(sent.contains("\"prolapse_resolved\":null"))
        assertTrue(sent.contains("\"is_obsoleted\":false")); assertTrue(sent.contains("\"check_at\":null")); assertTrue(sent.contains("NO_MASSTITIS"))
        success("submit_postpartum_pig_status")
        executor.mutation(NativeSubmitPostpartumPigStatusMutation(input.copy(assessment = assessment.copy(prolapse = null, recurred = null, resolved = false), obsoleted = true, obsoletedReason = "ReasonCase", checkedAt = at).graphql()))
        val next = body(); assertTrue(next.contains("\"prolapse_type\":null")); assertTrue(next.contains("\"prolapse_resolved\":false")); assertTrue(next.contains("ReasonCase")); assertTrue(next.contains(at))
    }
    @Test fun `lactation allows absent sow assessment and retains empty lists differently from piglet info`() = runTest {
        val input = SubmitLactationInput("task", "pig", null, at, "", false, null, 1, 1, weight, emptyList(), emptyList())
        success("submit_lactation_pig_status"); executor.mutation(NativeSubmitLactationPigStatusMutation(input.graphql()))
        val sent = body(); assertTrue(sent.contains("\"check_status\":null")); assertTrue(sent.contains("\"identity_piglets\":[]")); assertTrue(sent.contains("\"piglet_counts\":[]"))
        assertTrue(sent.contains("\"total_weight\":\"123456789.12345678900\"")); assertTrue(sent.contains("\"description\":\"\""))
        success("submit_lactation_pig_status")
        executor.mutation(NativeSubmitLactationPigStatusMutation(input.copy(assessment = assessment, identities = listOf(IdentityPigletInput(weight, "TagCase:A", null, true, PigletSex.FEMALE)), counts = listOf(PigletCount(FarrowingPigletType.HEALTHY, 2))).graphql()))
        val next = body(); assertTrue(next.contains("\"is_breeder\":true")); assertTrue(next.contains("\"birth_weight\":\"123456789.12345678900\"")); assertTrue(next.contains("\"count\":2"))
    }
    @Test fun `growing pair sends entire location aggregate identities decimal zero and null`() = runTest {
        val input = CheckGrowingInput("task", "z.u.p", GrowingStage.NURSERY, growing, listOf(identity, identity.copy(pigId = "pig2", sex = null, weight = BigDecimal.ZERO)), null)
        success("check_nursery_pig_status"); executor.mutation(NativeCheckNurseryPigStatusMutation(input.nurseryGraphql()))
        val sent = body(); assertTrue(sent.contains("\"location_code\":\"z.u.p\"")); assertTrue(sent.contains("\"healthy_count\":2")); assertTrue(sent.contains("\"total_weight\":\"123456789.12345678900\""))
        assertTrue(sent.indexOf("\"pig_id\":\"pig\"") < sent.indexOf("\"pig_id\":\"pig2\"")); assertTrue(sent.contains("\"weight\":\"0\"")); assertTrue(sent.contains("\"sex\":null"))
        success("check_fattening_pig_status")
        executor.mutation(NativeCheckFatteningPigStatusMutation(input.copy(stage = GrowingStage.FATTENING, identities = emptyList()).fatteningGraphql()))
        assertTrue(body().contains("\"identity_pigs\":[]"))
    }
    @Test fun `queries preserve sow raw json lactation identity and nursery location aggregates`() = runTest {
        enqueue(postpartumResponse)
        val postpartum = requireNotNull(GraphqlPostpartumReads(executor) { }.task(lease, "task")).pigs.single().result!!
        assertEquals(at, postpartum.checkedAt); assertEquals(rawAssessment, postpartum.assessment?.encoded); assertFalse(postpartum.obsoleted); body()
        enqueue(task("LACTATION", "lactation_pig", lactationFields))
        val lactation = requireNotNull(GraphqlLactationReads(executor) { }.task(lease, "task")).pigs.single().result!!
        assertEquals(weight, lactation.totalWeight); assertTrue(lactation.identities.single().breeder); assertNull(lactation.initialTotalCount); assertEquals(weight, lactation.identities.single().piglet.birthWeight); body()
        enqueue(task("NURSERY", "nursery_pig", growingFields, locationFields))
        val nursery = requireNotNull(GraphqlNurseryReads(executor) { }.task(lease, "task"))
        assertEquals(2, nursery.locations.single().totalCount); assertEquals(weight, nursery.locations.single().totalWeight); assertEquals(weight, nursery.pigs.single().weight); body()
        enqueue(task("FATTENING", "fattening_pig", growingFields))
        val fattening = requireNotNull(GraphqlFatteningReads(executor) { }.task(lease, "task"))
        assertTrue(fattening.locations.isEmpty()); assertTrue(fattening.pigs.single().result!!.hasIdentity); body()
    }
    @Test fun `foreign ownership negative quantities and unknown identity sex reject snapshots`() = runTest {
        for (bad in listOf(postpartumResponse.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            postpartumResponse.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""))) {
            enqueue(bad); try { GraphqlPostpartumReads(executor) { }.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        }
        for (bad in listOf(lactationFields.replace("\"female_count\":1", "\"female_count\":-1"),
            lactationFields.replace("\"pig_id\":\"pig\",\"piglet_seq\"", "\"pig_id\":\"other\",\"piglet_seq\""), lactationFields.replace("HEALTHY", "FUTURE"))) {
            enqueue(task("LACTATION", "lactation_pig", bad)); try { GraphqlLactationReads(executor) { }.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        }
        for (bad in listOf(locationFields.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""), locationFields.replace("\"total_count\":2", "\"total_count\":-2"))) {
            enqueue(task("NURSERY", "nursery_pig", growingFields, bad)); try { GraphqlNurseryReads(executor) { }.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        }
        enqueue(task("FATTENING", "fattening_pig", growingFields).replace("\"sex\":\"FEMALE\"", "\"sex\":\"FUTURE\""))
        try { GraphqlFatteningReads(executor) { }.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
    }
    @Test fun `missing task absent check and context switch remain separate`() = runTest {
        enqueue("""{"data":{"tasks":[]}}"""); assertNull(GraphqlNurseryReads(executor) { }.task(lease, "missing"))
        enqueue(task("POSTPARTUM", "postpartum_pig", "null")); assertNull(GraphqlPostpartumReads(executor) { }.task(lease, "task")!!.pigs.single().result)
        var checks = 0
        val reads = GraphqlLactationReads(executor) { if (++checks == 2) throw RequestContextChanged() }
        enqueue(task("LACTATION", "lactation_pig", lactationFields))
        try { reads.task(lease, "task"); fail() } catch (_: RequestContextChanged) { }
    }
    @Test fun `invalid location identity quantities and stage cannot become network input`() {
        val invalid: List<() -> Any> = listOf({ GrowingAssessment(-1, 0, 0, 0, weight, 0) },
            { GrowingIdentityInput(" ", "z.u.p", false, false, null, null, null, null) },
            { CheckGrowingInput("task", " ", GrowingStage.NURSERY, growing, null, null) },
            { CheckGrowingInput("task", "z.u.p", GrowingStage.FATTENING, growing, null, null).nurseryGraphql() },
            { SubmitLactationInput("task", "pig", null, "bad-date", null, false, null, 0, 0, weight, null, null) })
        invalid.forEach { try { it(); fail() } catch (_: IllegalArgumentException) { } }
        assertEquals(0, server.requestCount)
    }
}
