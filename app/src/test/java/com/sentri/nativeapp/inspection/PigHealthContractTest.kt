package com.sentri.nativeapp.inspection

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigHealthContractTest {
    private val server = MockWebServer()
    private val client = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build()
    private val executor = GraphqlExecutor(client,
        AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }) { "en" }
    private val gateway = GraphqlPigHealthGateway(executor)
    private val imageGateway = GraphqlHealthyImageGateway(executor)
    private val references = GraphqlHealthReferenceReads(executor) { }
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private fun body(json: String) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    private fun success(field: String, id: String = "pig") = body("""{"data":{"biosafety":{"$field":{"status":"SUCCESS","recordId":"$id","errors":[]}}}}""")
    @After fun close() { client.close(); server.shutdown() }

    @Test fun allSixMutationsPreserveOperationAndPigReceipt() = runTest {
        val cases = listOf(
            AddPigDiseaseInput("pig", listOf("disease", "disease"), "", listOf("https://image"), null, "", PigTriageLevel.Priority) to "add_pig_disease",
            AddPigSymptomInput("pig", listOf("symptom"), null, emptyList(), "pen", null, PigTriageLevel.Normal) to "add_pig_symptom",
            RemovePigDiseaseInput("pig", listOf("disease"), true, null) to "remove_pig_disease",
            RemovePigSymptomInput("pig", listOf("symptom"), false, "pen") to "remove_pig_symptom",
            SetPigTriageLevelInput("pig", "", PigTriageLevel.Emergency, null, "reason") to "set_pig_triage_level",
            SetPigBodyTemperatureInput("pig", "tag", BigDecimal("39.2500"), TemperatureUnit.Celsius, PigTriageLevel.Priority, null) to "set_pig_body_temperature",
        )
        for ((input, field) in cases) {
            success(field)
            val receipt = PigHealthWriteReceipt.decode((gateway.send(input) as CommandOutcome.Success).receipt)
            receipt.validate(input)
            val request = server.takeRequest()
            assertEquals("en", request.getHeader("sentri-language"))
            assertNull(request.getHeader("x-hasura-farm-id"))
            val sent = request.body.readUtf8()
            assertTrue(sent.contains("\"pig_id\":\"pig\""))
            assertTrue(sent.contains(field))
            if (input is AddPigDiseaseInput) {
                assertTrue(sent.contains("\"disease_ids\":[\"disease\",\"disease\"]"))
                assertTrue(sent.contains("\"image_urls\":[\"https://image\"]"))
                assertTrue(sent.contains("\"ear_tag\":\"\""))
            }
            if (input is SetPigBodyTemperatureInput) {
                assertTrue(sent.contains("\"temperature\":\"39.2500\""))
                assertTrue(sent.contains("\"unit\":\"Celsius\""))
            }
        }
    }
    @Test fun contradictoryAndFailedResponsesDoNotConfirmHealthWrite() = runTest {
        val input = AddPigSymptomInput("pig", listOf("symptom"))
        success("add_pig_symptom", "other")
        try { gateway.send(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
        body("""{"data":{"biosafety":{"add_pig_symptom":{"status":"FAILURE","recordId":"","errors":[{"err_msg":"denied"}]}}}}""")
        assertEquals(CommandOutcome.Rejected("BACKEND_REFUSED"), gateway.send(input)); server.takeRequest()
    }
    @Test fun bilingualDiseaseAndSymptomCatalogIsTyped() = runTest {
        body("""{"data":{"farm_view_disease":[{"id":"d","name_cn":"疾病","name_en":"Disease","abbr_cn":"病","abbr_en":"D","category_cn":"分类","category_en":"Category","description_cn":"说明","description_en":"Description"}],"farm_view_symptom":[{"id":"s","name_cn":"症状","name_en":"Symptom","part_cn":"部位","part_en":"Part","description_cn":"说明","description_en":"Description"}]}}""")
        val result = references.catalog(lease)
        assertEquals("Disease", result.diseases.single().nameEn)
        assertEquals("部位", result.symptoms.single().partCn)
        assertTrue(server.takeRequest().body.readUtf8().contains("NativeHealthReferenceCatalog"))
    }
    @Test fun receiptRequiresMatchingOperationAndPig() {
        val input = SetPigTriageLevelInput("pig", "tag", PigTriageLevel.Normal)
        PigHealthWriteReceipt("SetPigTriageLevel", "pig").validate(input)
        for (bad in listOf("{}", PigHealthWriteReceipt("AddPigDisease", "pig").encode(),
            PigHealthWriteReceipt("SetPigTriageLevel", "other").encode())) {
            try { PigHealthWriteReceipt.decode(bad).validate(input); fail() } catch (_: Exception) { }
        }
    }
    @Test fun imageUploadKeepsDataAndRequiresBothServerReceiptFields() = runTest {
        val input = UploadHealthyImageInput("data:image/png;base64,AAAA")
        body("""{"data":{"biosafety":{"upload_healthy_image":{"status":"SUCCESS","file_key":"key","file_url":"https://cdn/image","errors":[]}}}}""")
        assertEquals(HealthyImageReceipt("key", "https://cdn/image"),
            HealthyImageReceipt.decode((imageGateway.upload(input) as CommandOutcome.Success).receipt))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"data_url\":\"data:image/png;base64,AAAA\""))
        body("""{"data":{"biosafety":{"upload_healthy_image":{"status":"SUCCESS","file_key":"","file_url":"https://cdn/image","errors":[]}}}}""")
        try { imageGateway.upload(input); fail() } catch (_: ApiFailure.Protocol) { }; server.takeRequest()
    }
}
