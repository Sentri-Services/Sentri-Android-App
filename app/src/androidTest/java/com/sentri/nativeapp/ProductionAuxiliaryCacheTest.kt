package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object AuxiliaryFixture {
    val scope = TaskCatalogFixture.scope
    val lease = TaskCatalogFixture.lease
    val at = "2026-10-09T09:00:00.1234567890123+08:00"
    val batch = ProductionBatch("batch", scope.farmId, "BatchCase", ProductionBatchStatus.STARTED, "line",
        BatchLine("line", scope.farmId, "LineCase", ProductionLineStatus.PAUSED),
        listOf(BatchTaskReference("re", ProductionTaskKind.RE_ESTRUS, ProductionTaskStatus.RUNNING, TaskPlan(at, null))))
    val request = BatchRequest.active()
    val batches = BatchPage(request, listOf(batch), false)
    val location = RecheckLocation(TaskPigLocation("Z.U.P", "unit", "单元Case", "栏Case"),
        NamedProductionLocation("Z", "区Case", "ZoneCase"), NamedProductionLocation("U", "单元Case", "UnitCase"),
        NamedProductionLocation("S", "分区Case", "SectionCase"), "R", "01", 0)
    val pig = RecheckPig("re", "batch", scope.farmId, "pig", "TagCase:A", null, location,
        ReEstrusResult(ReEstrusStatus.SIGNS_OF_RE_ESTRUS, at, false, null, at, "operator", null, "operator"))
    val recheckRequest = RecheckRequest("re", "batch", "unit", false)
    val rechecks = RecheckPage(recheckRequest, listOf(pig), false)
    fun remote(failure: Exception? = null, called: () -> Unit = {}) = object : ProductionAuxiliaryRemote {
        override suspend fun batches(lease: ScopeLease, request: BatchRequest): BatchPage { called(); failure?.let { throw it }; return batches.copy(request = request) }
        override suspend fun batch(lease: ScopeLease, id: String): ProductionBatch? { called(); failure?.let { throw it }; return if (id == batch.id) batch else null }
        override suspend fun rechecks(lease: ScopeLease, request: RecheckRequest): RecheckPage { called(); failure?.let { throw it }; return rechecks.copy(request = request) }
    }
    suspend fun warm(reads: ProductionAuxiliaryReads) {
        reads.batches(lease, request); reads.batch(lease, "batch"); reads.batch(lease, "missing"); reads.rechecks(lease, recheckRequest)
    }
}
class ProductionAuxiliaryCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = AuxiliaryFixture
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "auxiliary-" + UUID.randomUUID()
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun auxiliarySnapshotsReopenWithPreciseTimeNamesNullAbsenceAndSource() = database { cache, name ->
        f.warm(ProductionAuxiliaryReads(f.remote(), cache, {}) { 123 })
        SqliteSnapshotCache(context, name).use { reopened ->
            val reads = ProductionAuxiliaryReads(f.remote(ApiFailure.Network()), reopened, {})
            val batch = reads.batch(f.lease, "batch")
            assertEquals(f.batch, batch.value); assertEquals(123L, batch.fetchedAt); assertEquals(DataSource.CACHE, batch.source); assertTrue(batch.refreshFailure is ApiFailure.Network)
            assertEquals(f.batches, reads.batches(f.lease, f.request).value); assertEquals(f.rechecks, reads.rechecks(f.lease, f.recheckRequest).value)
            assertNull(reads.batch(f.lease, "missing").value)
            try { reads.batch(f.lease, "never-read", true); fail() } catch (_: CacheMiss) { }
        }
    }
    @Test fun fullScopeAndEachFilterUseIndependentKeys() = database { cache, _ ->
        val reads = ProductionAuxiliaryReads(f.remote(), cache, {}); f.warm(reads)
        for (scope in listOf(f.scope.copy(environment = "other"), f.scope.copy(oauthId = "other"), f.scope.copy(userId = "other"), f.scope.copy(farmId = "other"))) {
            try { reads.batches(ScopeLease(scope, 2), f.request, true); fail() } catch (_: CacheMiss) { }
            try { reads.batch(ScopeLease(scope, 2), "batch", true); fail() } catch (_: CacheMiss) { }
            try { reads.rechecks(ScopeLease(scope, 2), f.recheckRequest, true); fail() } catch (_: CacheMiss) { }
        }
        for (request in listOf(f.request.copy(offset = 1), f.request.copy(limit = 1), f.request.copy(lineId = "line"),
            f.request.copy(excludeDefault = false), f.request.copy(statuses = emptySet()))) {
            try { reads.batches(f.lease, request, true); fail() } catch (_: CacheMiss) { }
        }
        for (request in listOf(f.recheckRequest.copy(offset = 1), f.recheckRequest.copy(limit = 1), f.recheckRequest.copy(taskId = null),
            f.recheckRequest.copy(batchId = null), f.recheckRequest.copy(unitId = null), f.recheckRequest.copy(inReEstrus = null))) {
            try { reads.rechecks(f.lease, request, true); fail() } catch (_: CacheMiss) { }
        }
        assertEquals(ProductionAuxiliaryCodecs.batchKey(f.request), ProductionAuxiliaryCodecs.batchKey(f.request.copy(statuses = f.request.statuses.reversed().toSet())))
    }
    @Test fun malformedSchemasEnumsTypesDuplicatedRowsAndForeignScopeAreRejected() = database { cache, _ ->
        val encoded = ProductionAuxiliaryCodecs.encodeRechecks(f.rechecks)
        val wrongRows = JSONObject(encoded).apply { getJSONArray("pigs").put(getJSONArray("pigs").getJSONObject(0)) }.toString()
        val invalid = listOf(encoded.replace("\"format\":1", "\"format\":2"), encoded.replace("SIGNS_OF_RE_ESTRUS", "FUTURE"),
            encoded.replace("\"inReEstrus\":false", "\"inReEstrus\":\"false\""), encoded.replace("\"penNumber\":0", "\"penNumber\":0.5"),
            encoded.replace("\"rowNumber\":\"01\"", "\"rowNumber\":1"), encoded.replace(f.at, "bad"),
            encoded.replace("\"farm\":\"farm\"", "\"farm\":\"other\""), encoded.replace("\"more\":false", "\"more\":true"),
            encoded.replace("\"task\":\"re\"", "\"task\":\"other\""), JSONObject(encoded).put("future", true).toString(), wrongRows)
        val reads = ProductionAuxiliaryReads(f.remote(ApiFailure.Network()), cache, {})
        for (bad in invalid) {
            assertNotEquals(encoded, bad)
            cache.write(f.scope, "production_recheck_page", ProductionAuxiliaryCodecs.recheckKey(f.recheckRequest), StoredSnapshot(bad, 1))
            try { reads.rechecks(f.lease, f.recheckRequest, true); fail() } catch (_: CacheInvalid) { }
        }
        val batches = ProductionAuxiliaryCodecs.encodeBatches(f.batches)
        for (bad in listOf(batches.replace("\"excludeDefault\":true", "\"excludeDefault\":false"), batches.replace("\"farm\":\"farm\"", "\"farm\":\"other\""),
            batches.replace("\"PAUSED\"", "\"FUTURE\""), batches.replace("\"batch\"", "\"LINE-case\""), batches.replace("\"offset\":0", "\"offset\":\"0\""))) {
            assertNotEquals(batches, bad)
            cache.write(f.scope, "production_batch_page", ProductionAuxiliaryCodecs.batchKey(f.request), StoredSnapshot(bad, 1))
            try { reads.batches(f.lease, f.request, true); fail() } catch (_: CacheInvalid) { }
        }
    }
    @Test fun onlyConnectivityAndUnavailableAuthFallbackAndScopeChangeCannotReplaceCache() = database { cache, _ ->
        f.warm(ProductionAuxiliaryReads(f.remote(), cache, {}) { 123 })
        assertEquals(f.batch, ProductionAuxiliaryReads(f.remote(AuthUnavailable()), cache, {}).batch(f.lease, "batch").value)
        for (failure in listOf(ApiFailure.Business("SIMULATED"), ApiFailure.Authentication(), ApiFailure.Protocol(), CancellationException("simulated"))) {
            try { ProductionAuxiliaryReads(f.remote(failure), cache, {}).batch(f.lease, "batch"); fail() } catch (actual: Exception) { assertSame(failure, actual) }
        }
        var checks = 0
        try { ProductionAuxiliaryReads(f.remote(), cache, { if (++checks == 2) throw RequestContextChanged() }) { 999 }.batch(f.lease, "batch"); fail() }
        catch (_: RequestContextChanged) { }
        val saved = ProductionAuxiliaryReads(f.remote(), cache, {}).batch(f.lease, "batch", true)
        assertEquals(123L, saved.fetchedAt); assertEquals(f.batch, saved.value)
    }
    @Test fun auxiliaryKindsShareBoundedReadRetentionWithoutEvictingAccountOrLocations() = database { cache, _ ->
        cache.write(f.scope, "account", "current:en", StoredSnapshot("account", 0)); cache.write(f.scope, "locations", "all", StoredSnapshot("locations", 0))
        val kinds = listOf("production_batch_page", "production_batch_detail", "production_recheck_page")
        repeat(260) { n -> cache.write(f.scope, kinds[n % kinds.size], "key" + n, StoredSnapshot("payload", n.toLong())) }
        assertNull(cache.read(f.scope, kinds[0], "key0")); assertNotNull(cache.read(f.scope, kinds[4 % kinds.size], "key4"))
        val count = cache.readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots WHERE kind LIKE 'production_%'", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(256, count); assertNotNull(cache.read(f.scope, "account", "current:en")); assertNotNull(cache.read(f.scope, "locations", "all"))
    }
    @Test fun codecsPreserveIndependentEnvelopeKindsAndExplicitNullableFields() {
        assertEquals(f.batches, ProductionAuxiliaryCodecs.decodeBatches(ProductionAuxiliaryCodecs.encodeBatches(f.batches)))
        assertEquals(f.rechecks, ProductionAuxiliaryCodecs.decodeRechecks(ProductionAuxiliaryCodecs.encodeRechecks(f.rechecks)))
        assertEquals(f.batch, ProductionAuxiliaryCodecs.decodeBatch(ProductionAuxiliaryCodecs.encodeBatch(f.batch)))
        assertNull(ProductionAuxiliaryCodecs.decodeBatch(ProductionAuxiliaryCodecs.encodeBatch(null)))
        val nullable = f.rechecks.copy(pigs = listOf(f.pig.copy(location = null, result = f.pig.result.copy(status = null, checkedAt = null))))
        assertEquals(nullable, ProductionAuxiliaryCodecs.decodeRechecks(ProductionAuxiliaryCodecs.encodeRechecks(nullable)))
        try { ProductionAuxiliaryCodecs.decodeRechecks(ProductionAuxiliaryCodecs.encodeBatches(f.batches)); fail() } catch (_: Exception) { }
    }
}
