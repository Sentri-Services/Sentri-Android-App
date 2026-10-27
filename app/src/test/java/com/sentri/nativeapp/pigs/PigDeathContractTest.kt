package com.sentri.nativeapp.pigs

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PigDeathContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val gateway = GraphqlPigDeathGateway(GraphqlExecutor(client, AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" })
    private fun response(field: String, status: String = "SUCCESS", extra: String = "", errors: String = "[]") {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"production":{"$field":{"status":"$status","errors":$errors$extra}}}}"""))
    }
    private fun sent() = server.takeRequest().let {
        assertEquals("en", it.getHeader("sentri-language")); assertNull(it.getHeader("Idempotency-Key")); it.body.readUtf8()
    }
    @After fun close() { client.close(); server.shutdown() }
    @Test fun uploadWireRetainsOriginalDataAndBothReceiptFields() = runTest {
        response("upload_pig_death_image", extra = """, "file_key":"random-server-key", "file_url":"https://fixture.invalid/photo"""")
        val result = gateway.upload(UploadPigDeathImageInput("report", 0, 0, "data:image/png;base64,AA==", "pig")) as CommandOutcome.Success
        assertEquals(DeathImageReceipt("random-server-key", "https://fixture.invalid/photo"), DeathImageReceipt.decode(result.receipt))
        val body = sent(); assertTrue(body.contains("\"data_url\":\"data:image/png;base64,AA==\"")); assertFalse(body.contains("reportOperationId"))
    }
    @Test fun reportWirePreservesAnonymousMaternalAndActualPigsAndImageOrder() = runTest {
        response("report_pig_death")
        val input = ReportPigDeathInput(listOf(
            DeathPigInput("A", earTag = "", reason = "reason", images = listOf(DeathImage.Remote("https://fixture.invalid/remote"), DeathImage.Uploaded("upload"))),
            DeathPigInput(motherId = "mother", earNotch = "Notch:Case", locationCode = "pen"), DeathPigInput()), "task-local-only")
        assertTrue(gateway.report(input.graphql { "https://fixture.invalid/uploaded" }) is CommandOutcome.Success)
        val body = sent()
        for (part in listOf("\"pig_id\":\"A\"", "\"ear_tag\":\"\"", "\"pig_id\":null", "\"mother_id\":\"mother\"", "\"death_images\":[]", "\"death_reason\":null")) assertTrue(part, body.contains(part))
        assertTrue(body.indexOf("/remote") < body.indexOf("/uploaded")); assertFalse(body.contains("task-local-only")); assertFalse(body.contains("data:"))
    }
    @Test fun failureAndMalformedSuccessNeverBecomeConfirmedUploads() = runTest {
        response("upload_pig_death_image", "FAILURE", """, "file_key":"", "file_url":""""", """[{"err_msg":"REFUSED"}]""")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.upload(UploadPigDeathImageInput("r", 0, 0, "data:x", null))); sent()
        response("upload_pig_death_image", extra = """, "file_key":"key", "file_url":""""")
        try { gateway.upload(UploadPigDeathImageInput("r", 0, 0, "data:x", null)); fail() } catch (_: IllegalArgumentException) { }
        sent()
        response("report_pig_death", "FAILURE", errors = """[{"err_msg":"REFUSED"}]""")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.report(ReportPigDeathInput(listOf(DeathPigInput())).graphql { error("No upload") })); sent()
    }
    @Test fun receiptCodecPreservesEscapedServerFieldsAndRejectsIncompleteOrCoercedProof() {
        val receipt = DeathImageReceipt("key\"\n", "https://fixture.invalid/path?q=\"value\"")
        assertEquals(receipt, DeathImageReceipt.decode(receipt.encode()))
        for (bad in listOf("{}", "{\"version\":\"1\",\"key\":\"key\",\"url\":\"url\"}",
            "{\"version\":1,\"key\":\"key\",\"url\":\"data:image/png;base64,AA==\"}",
            "{\"version\":1,\"key\":\"a\",\"key\":\"b\",\"url\":\"url\"}", receipt.encode() + "true")) {
            try { DeathImageReceipt.decode(bad); fail("Expected invalid receipt") } catch (_: Exception) { }
        }
    }
    @Test fun authenticationFailureDoesNotReplayReport() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try { gateway.report(ReportPigDeathInput(listOf(DeathPigInput())).graphql { error("No upload") }); fail() } catch (_: ApiFailure.Authentication) { }
        assertEquals(1, server.requestCount)
    }
}
