package com.sentri.nativeapp.pigs

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.production.PigletSex
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigManagementContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val at = "2026-10-10T08:00:00.1234567890123+08:00"
    private fun response(field: String, status: String = "SUCCESS", errors: String = "[]") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"production":{"$field":{"status":"$status","errors":$errors}}}}"""))
    }
    private fun body() = server.takeRequest().let {
        assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8()
    }
    @After fun close() { client.close(); server.shutdown() }
    @Test fun propertyWireKeepsNullEmptyZeroAndPreciseDecimalString() = runTest {
        response("update_pig_property")
        executor.mutation(NativeUpdatePigPropertyMutation(UpdatePigPropertyInput("pig").graphql()))
        val empty = body()
        for (field in listOf("lifecycle_status", "sex", "teats", "weight", "remark")) assertTrue(field, empty.contains("\"" + field + "\":null"))
        response("update_pig_property")
        executor.mutation(NativeUpdatePigPropertyMutation(UpdatePigPropertyInput("pig", PigLifecycle.MISSING, PigletSex.FEMALE, 0, BigDecimal("123456789.12345678900"), "").graphql()))
        val explicit = body()
        for (part in listOf("\"pig_id\":\"pig\"", "\"lifecycle_status\":\"MISSING\"", "\"sex\":\"FEMALE\"", "\"teats\":0", "\"weight\":\"123456789.12345678900\"", "\"remark\":\"\""))
            assertTrue(part, explicit.contains(part))
        response("update_pig_property")
        executor.mutation(NativeUpdatePigPropertyMutation(UpdatePigPropertyInput("pig", weight = BigDecimal("1E+3")).graphql()))
        assertTrue(body().contains("\"weight\":\"1000\""))
    }
    @Test fun allBreederActionsKeepIdentityParentIdsSelectionOrderAndCapturedTime() = runTest {
        val pigs = listOf(BreederPigInput("B", "", null, PigletSex.MALE, "mother", null), BreederPigInput("A", null, "NotchCase:A", null, null, "father"))
        for (action in BreederRoleAction.entries) {
            response("update_breeder_role")
            executor.mutation(NativeUpdateBreederRoleMutation(UpdateBreederRoleInput(action, pigs, at).graphql()))
            val sent = body()
            for (part in listOf("\"action\":\"" + action.name + "\"", "\"mother_id\":\"mother\"", "\"father_id\":\"father\"", "\"ear_tag\":\"\"", "\"marked_at\":\"" + at + "\"")) assertTrue(part, sent.contains(part))
            assertTrue(sent.indexOf("\"pig_id\":\"B\"") < sent.indexOf("\"pig_id\":\"A\""))
        }
        response("update_breeder_role")
        executor.mutation(NativeUpdateBreederRoleMutation(UpdateBreederRoleInput(BreederRoleAction.CANCEL_TO_PIGLET, emptyList(), null).graphql()))
        val empty = body(); assertTrue(empty.contains("\"breeder_pigs\":[]")); assertTrue(empty.contains("\"marked_at\":null"))
    }
    @Test fun backendRefusalRemainsARefusalAndNewOperationsDoNotRetryWrites() = runTest {
        response("update_pig_property", "FAILURE", """[{"err_msg":"SIMULATED_REFUSAL"}]""")
        val response = executor.mutation(NativeUpdatePigPropertyMutation(UpdatePigPropertyInput("pig", lifecycle = PigLifecycle.DEAD).graphql()))
        assertEquals("FAILURE", response.production.update_pig_property.status.rawValue)
        assertEquals("SIMULATED_REFUSAL", response.production.update_pig_property.errors.single().err_msg); body()
        server.enqueue(MockResponse().setResponseCode(401))
        try { executor.mutation(NativeUpdateBreederRoleMutation(UpdateBreederRoleInput(BreederRoleAction.MARK_AS_BREEDER, emptyList(), null).graphql())); fail() } catch (_: ApiFailure.Authentication) { }
        assertEquals(2, server.requestCount)
    }
}
