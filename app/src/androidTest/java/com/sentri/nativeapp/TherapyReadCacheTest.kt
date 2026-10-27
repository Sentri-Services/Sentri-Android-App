package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.therapy.*
import com.sentri.nativeapp.offline.SqliteCommandQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class TherapyReadCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private val at = "2026-10-10T10:00:00.1234567890123+08:00"
    private val row = TherapyRow("therapy", "farm", "rx", at, BigDecimal("123.4500"), 0,
        "med", TherapyMedication("med", "farm", "药", "Medication"), "cat",
        TherapyMedicationCatalog("cat", "farm", "med", "剂型", "Form", null, ""), null, "",
        TherapyPrescription("rx", "farm", at, null, TherapyOperator("医生", null),
            JsonValueAdapter.validate("{\"ratio\":0.1234567890123456789}"), JsonValueAdapter.validate("[]"), null, "InProgress", 1),
        at, "InProgress", 2, "treatment", TherapyTreatment("treatment", "治疗", "Treatment", "详情", "Detail"), "ml",
        listOf(TherapyTargetTask("farm", "rx", "therapy", "task", "pig", 1, "InProgress", at, null, null, null, null,
            TherapyPig("pig", "farm", "B", null, "", "ACTIVE", "Z.U.P", "SELFBRED", "MALE", "YLL", at, 0,
                "PRODUCTION_BOAR", null, TherapyPigLocation("farm", "P", "R", "S", "U", "Z", 0,
                    TherapyName("区", "Zone"), null, TherapyName("单元", "Unit"), "R")))))
    private val prescriptionContext = TherapyPrescriptionContext("rx", "farm", "InProgress", setOf("pig"), mapOf("therapy" to "InProgress"))
    private val full = TherapyPageRequest(limit = 1)
    private val prefetch = TherapyPageRequest(mode = TherapyReadMode.ACTIVE_OR_EXECUTED_IN_WINDOW,
        windowStart = "2026-10-10T00:00:00.000Z", windowEnd = "2026-10-11T00:00:00.000Z", limit = 1)
    private fun remote(failure: Exception? = null, called: () -> Unit = {}) = object : TherapyReadRemote {
        private fun <T> answer(value: T): T { called(); failure?.let { throw it }; return value }
        override suspend fun page(lease: ScopeLease, request: TherapyPageRequest) = answer(TherapyPage(request, listOf(row), false))
        override suspend fun detail(lease: ScopeLease, id: String) = answer(if (id == row.id) row else null)
        override suspend fun medications(lease: ScopeLease) = answer(listOf(requireNotNull(row.medication)))
        override suspend fun catalogs(lease: ScopeLease) = answer(listOf(requireNotNull(row.medicationCatalog)))
        override suspend fun treatments(lease: ScopeLease) = answer(listOf(requireNotNull(row.treatment)))
        override suspend fun context(lease: ScopeLease, prescriptionId: String) = answer(if (prescriptionId == "rx") prescriptionContext else null)
    }
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "therapy-read-" + UUID.randomUUID()
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun pageDetailAndThreeReferencesReopenWithProvenanceAndExactValues() = database { cache, name ->
        val reads = TherapyReads(remote(), cache, {}, { 123 })
        assertEquals(DataSource.NETWORK, reads.page(lease, full).source)
        reads.page(lease, prefetch); reads.detail(lease, "therapy"); reads.detail(lease, "missing")
        reads.medications(lease); reads.catalogs(lease); reads.treatments(lease); reads.context(lease, "rx")
        SqliteSnapshotCache(context, name).use { reopened ->
            var called = 0
            val offline = TherapyReads(remote(ApiFailure.Network()) { called++ }, reopened, {})
            val cached = offline.page(lease, full)
            assertEquals(row, cached.value.rows.single()); assertEquals(DataSource.CACHE, cached.source)
            assertEquals(123L, cached.fetchedAt); assertTrue(cached.refreshFailure is ApiFailure.Network)
            assertEquals(row, offline.page(lease, prefetch, true).value.rows.single())
            assertEquals(row, offline.detail(lease, "therapy", true).value)
            assertNull(offline.detail(lease, "missing", true).value)
            assertEquals("Medication", offline.medications(lease, true).value.single().english)
            assertEquals("", offline.catalogs(lease, true).value.single().formEnglish)
            assertEquals("Detail", offline.treatments(lease, true).value.single().detailEnglish)
            assertEquals(prescriptionContext, offline.context(lease, "rx", true).value)
            assertEquals(1, called)
        }
    }
    @Test fun filterAndScopeKeysAreIndependentAndCorruptRelationsAreRejected() = database { cache, _ ->
        val reads = TherapyReads(remote(), cache, {})
        reads.page(lease, full); reads.page(lease, prefetch); reads.detail(lease, "therapy"); reads.medications(lease); reads.context(lease, "rx")
        for (request in listOf(full.copy(offset = 1), full.copy(limit = 2), full.copy(prescriptionId = "rx"),
            full.copy(statuses = setOf("InProgress")), full.copy(order = TherapyReadOrder.ID_ASC),
            prefetch.copy(windowEnd = "2026-10-12T00:00:00.000Z"))) {
            try { reads.page(lease, request, true); fail() } catch (_: CacheMiss) { }
        }
        for (foreign in listOf(scope.copy(environment = "other"), scope.copy(oauthId = "other"),
            scope.copy(userId = "other"), scope.copy(farmId = "other"))) {
            try { reads.page(ScopeLease(foreign, 2), full, true); fail() } catch (_: CacheMiss) { }
            try { reads.medications(ScopeLease(foreign, 2), true); fail() } catch (_: CacheMiss) { }
            try { reads.context(ScopeLease(foreign, 2), "rx", true); fail() } catch (_: CacheMiss) { }
        }
        val saved = TherapyReadCodec.encodePage(TherapyPage(full, listOf(row), false))
        val invalid = listOf(JSONObject(saved).put("future", true).toString(),
            saved.replace("\"format\":1", "\"format\":2"),
            saved.replace("\"dosage\":\"123.4500\"", "\"dosage\":123.45"),
            saved.replace("\"therapy\":\"therapy\"", "\"therapy\":\"other\""),
            saved.replace("\"farm\":\"farm\"", "\"farm\":\"other\""),
            JSONObject(saved).apply { put("rows", org.json.JSONArray()); put("more", true) }.toString())
        for (bad in invalid) {
            assertNotEquals(saved, bad)
            cache.write(scope, "therapy_page", TherapyReadCodec.key(full), StoredSnapshot(bad, 1))
            try { reads.page(lease, full, true); fail() } catch (_: CacheInvalid) { }
        }
        val wrongWindow = TherapyReadCodec.encodePage(TherapyPage(prefetch, listOf(row.copy(status = "Finished")), false))
        cache.write(scope, "therapy_page", TherapyReadCodec.key(prefetch), StoredSnapshot(wrongWindow, 1))
        try { reads.page(lease, prefetch, true); fail() } catch (_: CacheInvalid) { }
        val contextText = TherapyReadCodec.encodeContext(prescriptionContext)
        for (bad in listOf(JSONObject(contextText).put("unexpected", true).toString(),
            contextText.replace("\"farm\":\"farm\"", "\"farm\":\"other\""),
            contextText.replace("\"id\":\"rx\"", "\"id\":\"other\""))) {
            assertNotEquals(contextText, bad)
            cache.write(scope, "therapy_context", "rx", StoredSnapshot(bad, 1))
            try { reads.context(lease, "rx", true); fail() } catch (_: CacheInvalid) { }
        }
    }
    @Test fun onlyNetworkFailureUsesSavedSnapshotAndKindsParticipateInEviction() = database { cache, _ ->
        TherapyReads(remote(), cache, {}, { 123 }).detail(lease, "therapy")
        assertEquals(row, TherapyReads(remote(ApiFailure.Network()), cache, {}).detail(lease, "therapy").value)
        for (failure in listOf(ApiFailure.Business("simulated"), ApiFailure.Authentication(), ApiFailure.Protocol())) {
            try { TherapyReads(remote(failure), cache, {}).detail(lease, "therapy"); fail() } catch (actual: Exception) { assertSame(failure, actual) }
        }
        cache.write(scope, "account", "current:en", StoredSnapshot("account", 0))
        val kinds = listOf("therapy_page", "therapy_detail", "therapy_medications", "therapy_catalogs", "therapy_treatments", "therapy_context")
        repeat(260) { n -> cache.write(scope, kinds[n % kinds.size], "key$n", StoredSnapshot("payload", n.toLong())) }
        assertNull(cache.read(scope, kinds[0], "key0"))
        assertNotNull(cache.read(scope, "account", "current:en"))
        val count = cache.readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots WHERE kind LIKE 'therapy_%'", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(256, count)
    }
    @Test fun workspaceReadsCompletePrescriptionAndRebuildsQueuePreviewFromSavedSnapshots() = database { cache, name ->
        val queueName = "$name-commands"
        try {
            SqliteCommandQueue(context, queueName).use { queue ->
                val command = CompleteTherapyTargetInput("rx", "therapy", "task", listOf("pig"), at)
                queue.enqueue(scope, "complete", command, emptySet(), 1)
                val workspace = TherapyWorkspaceReads(TherapyReads(remote(), cache, {}), queue, {}).prescription(lease, "rx")
                assertEquals(1, workspace.pages.size)
                assertEquals(DataSource.NETWORK, workspace.context.source)
                assertEquals(TherapyPreviewState.PREVIEW, workspace.projection?.effects?.single()?.state)
                assertEquals(listOf(1, 2), workspace.projection?.previewRows?.get("therapy")?.tasks?.map { it.times })
                assertEquals(1, workspace.projection?.serverRows?.get("therapy")?.tasks?.size)
            }
            SqliteSnapshotCache(context, name).use { reopened ->
                SqliteCommandQueue(context, queueName).use { queue ->
                    var calls = 0
                    val offline = TherapyWorkspaceReads(TherapyReads(remote(ApiFailure.Network()) { calls++ }, reopened, {}), queue, {})
                        .prescription(lease, "rx", cacheOnly = true)
                    assertEquals(TherapyPreviewState.PREVIEW, offline.projection?.effects?.single()?.state)
                    assertEquals(DataSource.CACHE, offline.pages.single().source)
                    assertEquals(0, calls)
                }
            }
        } finally { context.deleteDatabase(queueName) }
    }
    @Test fun workspaceRequiresEveryPrescriptionTherapyPageBeforeProjecting() = database { cache, name ->
        val ids = (0..200).map { "therapy-%03d".format(it) }
        val contextValue = TherapyPrescriptionContext("rx", "farm", "InProgress", emptySet(), ids.associateWith { "InProgress" })
        val all = ids.map { id -> row.copy(id = id, tasks = emptyList(), prescription = row.prescription?.copy(therapyCount = ids.size)) }
        fun paged(dropLast: Boolean) = object : TherapyReadRemote {
            override suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage {
                val available = if (dropLast) all.dropLast(1) else all
                val chunk = available.drop(request.offset).take(request.limit)
                return TherapyPage(request, chunk, request.offset + chunk.size < available.size)
            }
            override suspend fun detail(lease: ScopeLease, id: String) = null
            override suspend fun medications(lease: ScopeLease) = emptyList<TherapyMedication>()
            override suspend fun catalogs(lease: ScopeLease) = emptyList<TherapyMedicationCatalog>()
            override suspend fun treatments(lease: ScopeLease) = emptyList<TherapyTreatment>()
            override suspend fun context(lease: ScopeLease, prescriptionId: String) = contextValue
        }
        val queueName = "$name-commands"
        try { SqliteCommandQueue(context, queueName).use { queue ->
            val complete = TherapyWorkspaceReads(TherapyReads(paged(false), cache, {}), queue, {}).prescription(lease, "rx")
            assertEquals(listOf(200, 1), complete.pages.map { it.value.rows.size })
            assertEquals(201, complete.projection?.previewRows?.size)
            try { TherapyWorkspaceReads(TherapyReads(paged(true), cache, {}), queue, {}).prescription(lease, "rx"); fail() }
            catch (_: ApiFailure.Protocol) { }
        } } finally { context.deleteDatabase(queueName) }
    }
}
