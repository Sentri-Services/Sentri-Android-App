package com.sentri.nativeapp.production

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UnitRoundContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val executor = GraphqlExecutor(client, auth) { "zh-CN" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 0)
    private val reReads = GraphqlReEstrusReads(executor) { }
    private val pregReads = GraphqlPregnancyReads(executor) { }
    private val at = "2026-10-09T09:00:00.123456+08:00"
    private fun task(type: String, field: String, result: String) = """{"data":{"tasks":[{"id":"task","batch_id":"batch","status":"RUNNING","task_type":"$type","batch":{"farm_id":"farm"},"pigs":[{"pig_id":"pig","disabled":false,"pig":{"id":"pig","farm_id":"farm","ear_tag":"TagA","ear_notch":null,"location_code":"z.u.p","location":{"unit_id":"unit","unit_location":"z.u","pen_location":"z.u.p"}},"$field":$result}]}]}}"""
    private val reResponse = task("RE_ESTRUS", "re_estrus_pig", """{"task_id":"task","pig_id":"pig","check_status":"RE_ESTRUS","check_at":"$at","is_in_re_estrus":true,"re_estrus_at":"$at","mating_at":null,"mating_by":null,"submitted_at":"$at","operator_id":"operator"}""")
    private val pregResponse = task("PREGNANCY", "pregnancy_pig", """{"task_id":"task","pig_id":"pig","check_status":"NOT_SURE","check_at":"$at","is_pregnant":null,"re_check_at":"2026-10-12T09:00:00.123456+08:00","mating_at":null,"mating_by":null,"submitted_at":null,"operator_id":"operator"}""")
    private fun enqueue(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `both queries preserve raw time enum flags and location`() = runTest {
        enqueue(reResponse)
        val re = requireNotNull(reReads.task(lease, "task"))
        assertEquals(ReEstrusStatus.RE_ESTRUS, re.pigs.single().result?.status)
        assertEquals(at, re.pigs.single().result?.checkedAt)
        assertEquals("z.u", re.pigs.single().location.unitLocation)
        assertTrue(re.pigs.single().result!!.isInReEstrus)
        val first = server.takeRequest()
        assertEquals("zh-CN", first.getHeader("sentri-language"))
        assertTrue(first.body.readUtf8().contains("farm_id"))
        enqueue(pregResponse)
        val preg = requireNotNull(pregReads.task(lease, "task"))
        assertEquals(PregnancyStatus.NOT_SURE, preg.pigs.single().result?.status)
        assertNull(preg.pigs.single().result?.isPregnant)
        assertEquals("2026-10-12T09:00:00.123456+08:00", preg.pigs.single().result?.reCheckAt)
    }
    @Test fun `foreign task farm pig and unknown enum cannot become valid records`() = runTest {
        for (bad in listOf(
            reResponse.replace("\"farm_id\":\"farm\"", "\"farm_id\":\"other\""),
            reResponse.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""),
            reResponse.replace("\"pig_id\":\"pig\"", "\"pig_id\":\"other\""),
            reResponse.replace("\"check_status\":\"RE_ESTRUS\"", "\"check_status\":\"FUTURE\""),
        )) { enqueue(bad); try { reReads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { } }
        enqueue(pregResponse.replace("NOT_SURE", "FUTURE"))
        try { pregReads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        enqueue("""{"data":{"tasks":[]}}"""); assertNull(pregReads.task(lease, "missing"))
    }
    @Test fun `re estrus mutation pair sends explicit time null and ordered pig IDs without unit metadata`() = runTest {
        val check = CheckReEstrusInput("task", "pig", ReEstrusStatus.NOT_RE_ESTRUS, null, "rawUnit.*")
        assertEquals(Optional.Present<String?>(null), check.graphql().check_at)
        enqueue("""{"data":{"production":{"check_re_estrus_pig":{"status":"SUCCESS","errors":[]}}}}""")
        assertEquals("SUCCESS", executor.mutation(NativeCheckReEstrusPigMutation(check.graphql())).production.check_re_estrus_pig.status.rawValue)
        var body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"check_at\":null")); assertTrue(body.contains("NOT_RE_ESTRUS")); assertFalse(body.contains("rawUnit"))
        enqueue("""{"data":{"production":{"submit_re_estrus_status":{"status":"SUCCESS","errors":[]}}}}""")
        val submit = SubmitReEstrusInput("task", listOf("pig2", "pig"), at, "rawUnit.*")
        executor.mutation(NativeSubmitReEstrusStatusMutation(submit.graphql()))
        body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"pig_ids\":[\"pig2\",\"pig\"]")); assertTrue(body.contains(at)); assertFalse(body.contains("rawUnit"))
    }
    @Test fun `pregnancy mutation pair keeps null zero and nonzero recheck input distinct`() = runTest {
        val check = CheckPregnancyInput("task", "pig", PregnancyStatus.NOT_SURE, null, null, "")
        assertEquals(Optional.Present<Int?>(null), check.graphql().re_check_days)
        assertEquals(Optional.Present<Int?>(0), check.copy(reCheckDays = 0).graphql().re_check_days)
        enqueue("""{"data":{"production":{"check_pregnancy_pig":{"status":"SUCCESS","errors":[]}}}}""")
        executor.mutation(NativeCheckPregnancyPigMutation(check.copy(reCheckDays = 3).graphql()))
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"re_check_days\":3")); assertTrue(body.contains("\"check_at\":null"))
        assertNull(request.getHeader("Idempotency-Key"))
        enqueue("""{"data":{"production":{"submit_pregnancy_status":{"status":"FAILURE","errors":[{"err_msg":"not_allowed"}]}}}}""")
        val result = executor.mutation(NativeSubmitPregnancyStatusMutation(SubmitPregnancyInput("task", listOf("pig"), null, "").graphql())).production.submit_pregnancy_status
        assertEquals("not_allowed", result.errors.single().err_msg)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"submit_at\":null"))
    }
    @Test fun `input rejects invalid IDs empty batch and malformed timestamp without forbidding all unit selector`() {
        CheckReEstrusInput("task", "pig", ReEstrusStatus.RE_ESTRUS, null, "")
        try { SubmitReEstrusInput("task", emptyList(), null, ""); fail() } catch (_: IllegalArgumentException) { }
        try { CheckPregnancyInput("task", " ", PregnancyStatus.PREGNANT, null, 0, "unit"); fail() } catch (_: IllegalArgumentException) { }
        try { SubmitPregnancyInput("task", listOf("pig"), "2026-02-30T00:00:00Z", "unit"); fail() } catch (_: IllegalArgumentException) { }
    }
}
