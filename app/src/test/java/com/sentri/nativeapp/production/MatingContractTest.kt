package com.sentri.nativeapp.production

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.NativeMateWithBoarMutation
import com.sentri.nativeapp.graphql.NativeMateWithSemenMutation
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MatingContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val executor = GraphqlExecutor(client, auth) { "en" }
    private val lease = ScopeLease(DataScope("dev", "test-user", "user", "farm"), 0)
    private val reads = GraphqlMatingReads(executor) { }
    private val material = """{"__typename":"farm_view_task_mating_material","id":9,"task_id":"task","boar_id":"boar","semen_id":null,"semen_breeder_type":null,"semen_collect_at":null,"semen_count":null,"boar":{"id":"boar","farm_id":"farm","ear_tag":"BoarA","ear_notch":null}}"""
    private val response = """{"data":{"tasks":[{"id":"task","batch_id":"batch","status":"RUNNING","task_type":"MATING","batch":{"farm_id":"farm"},"pigs":[{"pig_id":"pig","disabled":false,"pig":{"id":"pig","farm_id":"farm","ear_tag":"TagA","ear_notch":null},"mating_pig":{"task_id":"task","pig_id":"pig","mating_count":1,"estrus_at":null,"created_at":null,"first_mating_at":"2026-10-09T08:00:00.123456+08:00","first_mating_with":"boar","last_mating_at":"2026-10-09T08:00:00.123456+08:00","last_mating_with":"boar"},"mating_pig_records":[{"task_id":"task","pig_id":"pig","mating_at":"2026-10-09T08:00:00.123456+08:00","mating_times":1,"mating_with":"boar","operator_id":"operator","mating_boar":$material,"mating_semen":null}]}]}]}}"""
    private fun enqueue(body: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    @After fun close() { client.close(); server.shutdown() }
    @Test fun `read keeps material history precision and farm scope`() = runTest {
        enqueue(response)
        val task = requireNotNull(reads.task(lease, "task"))
        assertEquals(1, task.pigs.single().summary?.count)
        val record = task.pigs.single().records.single()
        assertEquals("2026-10-09T08:00:00.123456+08:00", record.at)
        assertEquals("BoarA", record.boar?.boarEarTag)
        val request = server.takeRequest()
        assertEquals("en", request.getHeader("sentri-language"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("farm_id")); assertTrue(body.contains("NativeMatingTask")); assertTrue(body.contains("mating_times"))
    }
    @Test fun `cross farm material and foreign task or pig records are rejected`() = runTest {
        val invalid = listOf(
            response.replace("\"id\":\"boar\",\"farm_id\":\"farm\"", "\"id\":\"boar\",\"farm_id\":\"other\""),
            response.replace("\"task_id\":\"task\"", "\"task_id\":\"other\""),
            response.replace("\"pig_id\":\"pig\"", "\"pig_id\":\"other\""),
            response.replace("\"task_type\":\"MATING\"", "\"task_type\":\"ESTRUS\""),
        )
        invalid.forEach { enqueue(it); try { reads.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { } }
        enqueue("""{"data":{"tasks":[]}}"""); assertNull(reads.task(lease, "missing"))
    }
    @Test fun `both mutation contracts use correct IDs and explicitly disable automatic estrus`() = runTest {
        val boar = MateWithBoarInput("task", "pig", "boar").graphql()
        val semen = MateWithSemenInput("task", "pig", "SemenCase:A").graphql()
        assertEquals(Optional.Present(false), boar.auto_check_estrus)
        assertEquals(Optional.Present(false), semen.auto_check_estrus)
        enqueue("""{"data":{"production":{"mate_with_boar":{"status":"SUCCESS","errors":[]}}}}""")
        assertEquals("SUCCESS", executor.mutation(NativeMateWithBoarMutation(boar)).production.mate_with_boar.status.rawValue)
        val boarBody = server.takeRequest().body.readUtf8()
        assertTrue(boarBody.contains("\"mating_pig_id\":\"pig\"")); assertTrue(boarBody.contains("\"boar_id\":\"boar\""))
        assertTrue(boarBody.contains("\"auto_check_estrus\":false"))
        enqueue("""{"data":{"production":{"mate_with_semen":{"status":"FAILURE","errors":[{"err_msg":"rejected"}]}}}}""")
        val result = executor.mutation(NativeMateWithSemenMutation(semen)).production.mate_with_semen
        assertEquals("rejected", result.errors.single().err_msg)
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("\"semen_id\":\"SemenCase:A\""))
        assertNull(request.getHeader("Idempotency-Key"))
    }
    @Test fun `invalid identity and local batch placeholders cannot become server semen IDs`() {
        try { MateWithBoarInput("task", "", "boar"); fail() } catch (_: IllegalArgumentException) { }
        try { MateWithSemenInput("task", "pig", "local-semen-batch:op"); fail() } catch (_: IllegalArgumentException) { }
        try { MateWithSemenInput("task", "pig", "local-semen-storage:op"); fail() } catch (_: IllegalArgumentException) { }
    }
}
