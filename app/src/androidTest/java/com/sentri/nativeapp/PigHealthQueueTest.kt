package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class PigHealthQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    @Test fun sixPayloadsRetainNullableListsRecoveryAndDecimal() {
        val inputs: List<PigHealthCommandInput> = listOf(
            AddPigDiseaseInput("pig", listOf("a", "a"), "", emptyList(), null, "", PigTriageLevel.Priority),
            AddPigSymptomInput("pig", emptyList(), null, listOf("url"), "pen", null, null),
            RemovePigDiseaseInput("pig", listOf("a"), true),
            RemovePigSymptomInput("pig", emptyList(), false, ""),
            SetPigTriageLevelInput("pig", "", PigTriageLevel.Healthy, "pen", null),
            SetPigBodyTemperatureInput("pig", "tag", BigDecimal("39.2500"), TemperatureUnit.Fahrenheit),
        )
        inputs.forEach { input -> assertEquals(input, CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, CommandPayloadCodec.encode(input))) }
        assertNotEquals(CommandPayloadCodec.encode(inputs[0]), CommandPayloadCodec.encode(inputs[1]))
    }
    @Test fun samePigHealthStepsStayOrderedAfterReopenAndUnknownBlocks() {
        val name = "health-${UUID.randomUUID()}"
        try {
            SqliteCommandQueue(context, name).use { queue ->
                val first = queue.enqueue(scope, "add", AddPigDiseaseInput("pig", listOf("d")), emptySet(), 100)
                val second = queue.enqueue(scope, "triage", SetPigTriageLevelInput("pig", "tag", PigTriageLevel.Priority), emptySet(), 0)
                queue.enqueue(scope, "other", SetPigTriageLevelInput("other", "tag", PigTriageLevel.Normal), emptySet(), 200)
                assertEquals(setOf(first.record.id), second.record.dependsOn)
            }
            SqliteCommandQueue(context, name).use { reopened ->
                val first = reopened.list(scope).single { it.record.operationId == "add" }
                assertEquals(first.record.id, reopened.claim(lease, 101)?.record?.id)
                val attempted = reopened.list(scope).single { it.record.id == first.record.id }
                reopened.finish(attempted, CommandOutcome.Uncertain, 102)
                val independent = reopened.claim(lease, 103)
                assertEquals("other", independent?.record?.operationId)
                assertNull(reopened.claim(lease, 104))
                try { reopened.finish(attempted, CommandOutcome.Success(PigHealthWriteReceipt("AddPigDisease", "wrong").encode()), 105); fail() }
                catch (_: IllegalArgumentException) { }
                reopened.finish(attempted, CommandOutcome.Success(PigHealthWriteReceipt("AddPigDisease", "pig").encode()), 106)
                assertEquals("triage", reopened.claim(lease, 107)?.record?.operationId)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun bilingualCatalogSurvivesCacheReopenAndScopeIsolation() = runBlocking {
        val name = "health-catalog-${UUID.randomUUID()}"
        val catalog = HealthReferenceCatalog(listOf(HealthDisease("d", "疾病", "Disease", "病", "D", "类", "Category", "说明", "Description")),
            listOf(HealthSymptom("s", "症状", "Symptom", "部位", "Part", "说明", "Description")))
        var calls = 0
        val remote = object : HealthReferenceRemote {
            override suspend fun catalog(lease: ScopeLease) = catalog.also { calls++ }
        }
        try {
            SqliteSnapshotCache(context, name).use { cache -> HealthReferenceReads(remote, cache, { }, { 100 })
                .catalog(ScopeLease(scope, 1)) }
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = HealthReferenceReads(remote, cache, { }, { 200 })
                assertEquals(catalog, reads.catalog(ScopeLease(scope, 1), true).value)
                assertEquals(1, calls)
                try { reads.catalog(ScopeLease(scope.copy(farmId = "other"), 1), true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun largeImageUploadIsDurableAndUnknownDoesNotReplay() {
        val name = "health-image-${UUID.randomUUID()}"
        val input = UploadHealthyImageInput("data:image/png;base64," + "A".repeat(2_700_000))
        try {
            SqliteCommandQueue(context, name).use { queue -> queue.enqueue(scope, "image", input, emptySet(), 1) }
            SqliteCommandQueue(context, name).use { reopened ->
                assertEquals(input, reopened.list(scope).single().input)
                val attempt = reopened.claim(lease, 2)!!
                reopened.finish(attempt, CommandOutcome.Uncertain, 3)
                assertNull(reopened.claim(lease, 4))
                reopened.finish(attempt, CommandOutcome.Success(HealthyImageReceipt("key", "https://cdn/image").encode()), 5)
                assertEquals(CommandStatus.SYNCED, reopened.list(scope).single().record.status)
                assertEquals(HealthyImageReceipt("key", "https://cdn/image"),
                    HealthyImageReceipt.decode(reopened.list(scope).single().receipt!!))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun measurementPayloadsAndOrderingSurviveReopen() {
        val name = "pig-measurements-${UUID.randomUUID()}"
        val totalOnly = RecordPigWeightsInput(null, BigDecimal("100.000"))
        val emptyRecords = RecordPigWeightsInput(emptyList(), BigDecimal("100.000"))
        assertNotEquals(CommandPayloadCodec.encode(totalOnly), CommandPayloadCodec.encode(emptyRecords))
        val multi = RecordPigWeightsInput(listOf(PigWeightItem("a", BigDecimal("12.3400")),
            PigWeightItem("a", null, ""), PigWeightItem("b", BigDecimal("9.5"))), BigDecimal("21.8400"))
        val backfat = RecordPigBackfatInput("a", BigDecimal("15.00"), PigBackfatLevel.MODERATE)
        val temperature = RecordPigTemperatureInput("b", BigDecimal("39.2500"), TemperatureUnit.Celsius)
        listOf(totalOnly, emptyRecords, multi, backfat, temperature).forEach { input ->
            assertEquals(input, CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, CommandPayloadCodec.encode(input)))
        }
        try {
            SqliteCommandQueue(context, name).use { queue ->
                val first = queue.enqueue(scope, "weight", multi, emptySet(), 1)
                val second = queue.enqueue(scope, "backfat", backfat, emptySet(), 2)
                val third = queue.enqueue(scope, "temperature", temperature, emptySet(), 3)
                assertEquals(setOf(first.record.id), second.record.dependsOn)
                assertEquals(setOf(first.record.id), third.record.dependsOn)
            }
            SqliteCommandQueue(context, name).use { reopened ->
                assertEquals(multi, reopened.list(scope).first().input)
                val weight = reopened.claim(lease, 4)!!
                reopened.finish(weight, CommandOutcome.Uncertain, 5)
                assertNull(reopened.claim(lease, 6))
                try { reopened.finish(weight, CommandOutcome.Success(PigMeasurementReceipt("RecordPigWeights", listOf("b", "a", "a")).encode()), 7); fail() }
                catch (_: IllegalArgumentException) { }
                reopened.finish(weight, CommandOutcome.Success(PigMeasurementReceipt("RecordPigWeights", listOf("a", "a", "b")).encode()), 8)
                assertNotNull(reopened.claim(lease, 9))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun measurementSnapshotKeepsDecimalScaleAcrossReopen() = runBlocking {
        val name = "pig-measurement-cache-${UUID.randomUUID()}"
        val rows = listOf(PigMeasurement("a", "farm", BigDecimal("12.3400"), BigDecimal("15.00"), "MODERATE",
            "2026-10-19T12:00:00+08:00", BigDecimal("39.2500"), "2026-10-19T12:01:00+08:00"))
        var calls = 0
        val remote = object : PigMeasurementRemote {
            override suspend fun measurements(lease: ScopeLease, pigIds: Set<String>) = rows.also { calls++ }
        }
        try {
            SqliteSnapshotCache(context, name).use { cache -> PigMeasurementReads(remote, cache, { }, { 100 })
                .measurements(ScopeLease(scope, 1), setOf("a")) }
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = PigMeasurementReads(remote, cache, { }, { 200 })
                assertEquals(rows, reads.measurements(ScopeLease(scope, 1), setOf("a"), true).value)
                assertEquals(1, calls)
                try { reads.measurements(ScopeLease(scope.copy(farmId = "other"), 1), setOf("a"), true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
