package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

internal object TaskCatalogFixture {
    val scope = DataScope("simulated-dev", "simulated-oauth", "user", "farm")
    val lease = ScopeLease(scope, 1)
    val at = "2026-10-09T09:00:00.123456789+08:00"
    val weight = BigDecimal("123456789.12345678900")
    val metadata = TaskMetadata("task", scope.farmId, ProductionTaskKind.MATING, ProductionTaskStatus.RUNNING, at, null, at, null, "operator", TaskOperator("operator", "NameCase", null),
        TaskBatch("batch", "BatchCase", JsonValueAdapter.validate("{\"future\":0.1234567890123456789,\"enabled\":false}"), TaskLine("line", scope.farmId, "LineCase", 100), listOf(RelatedProductionTask("task", ProductionTaskKind.MATING, ProductionTaskStatus.RUNNING))),
        TaskPlan(at, null), listOf(TaskPigReference("pig", false, "TagCase:A", null, "z.u.p", "RemarkCase")),
        listOf(MatingMaterial(7, "boar", "semen", null, at, 2, null, "NotchCase")), listOf(TaskRecheckStatistic(at, JsonValueAdapter.validate("{\"future\":123456789.12345678900}"))),
        listOf(NurseryLocation("z.u.p", at, null, null, 2, weight)))
    val request = TaskCatalogRequest.activeAndCompleted("2026-10-09T00:00:00+08:00", "2026-10-10T00:00:00+08:00").copy(kinds = setOf(ProductionTaskKind.MATING), batchId = "batch", lineId = "line", operatorId = "operator")
    val page = TaskCatalogPage(request, listOf(metadata), 11)
    fun remote(failure: Exception? = null) = object : TaskCatalogRemote {
        override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? { if (failure != null) throw failure; return if (id == metadata.id) metadata else null }
        override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage { if (failure != null) throw failure; return page.copy(request = request) }
    }
}
class TaskCatalogCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = TaskCatalogFixture
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "task-catalog-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun metadataAndPagesReopenWithRawJsonDecimalTimeAndCachedAbsence() = database { cache, name ->
        val reads = TaskCatalogReads(f.remote(), cache, { }) { 123 }
        assertEquals(f.metadata, reads.metadata(f.lease, "task").value); assertEquals(f.page, reads.page(f.lease, f.request).value)
        assertNull(reads.metadata(f.lease, "missing").value)
        SqliteSnapshotCache(context, name).use { reopened ->
            val offline = TaskCatalogReads(f.remote(ApiFailure.Network()), reopened, { })
            val saved = offline.metadata(f.lease, "task"); assertEquals(DataSource.CACHE, saved.source); assertEquals(123L, saved.fetchedAt); assertEquals(f.metadata, saved.value)
            assertEquals(f.page, offline.page(f.lease, f.request).value); assertNull(offline.metadata(f.lease, "missing").value)
            try { offline.metadata(f.lease, "never-read", true); fail() } catch (_: CacheMiss) { }
            assertEquals(f.metadata, TaskCatalogReads(f.remote(AuthUnavailable()), reopened, { }).metadata(f.lease, "task").value)
        }
    }
    @Test fun filtersSortPaginationTimeWindowsAndFullScopeHaveIndependentCacheKeys() = database { cache, _ ->
        val reads = TaskCatalogReads(f.remote(), cache, { }); reads.page(f.lease, f.request); reads.metadata(f.lease, "task")
        val different = listOf(f.request.copy(order = TaskCatalogOrder.PLANNED_ASC), f.request.copy(offset = 1), f.request.copy(limit = 49), f.request.copy(batchId = "other"),
            f.request.copy(operatorId = null), f.request.copy(lineId = null), f.request.copy(kinds = emptySet()), f.request.copy(statuses = setOf(ProductionTaskStatus.RUNNING)),
            f.request.copy(completedWindow = TaskTimeWindow("2026-10-08T00:00:00+08:00", "2026-10-09T00:00:00+08:00")))
        for (request in different) try { reads.page(f.lease, request, true); fail() } catch (_: CacheMiss) { }
        for (scope in listOf(f.scope.copy(environment = "other"), f.scope.copy(oauthId = "other"), f.scope.copy(userId = "other"), f.scope.copy(farmId = "other"))) {
            try { reads.page(ScopeLease(scope, 2), f.request, true); fail() } catch (_: CacheMiss) { }
            try { reads.metadata(ScopeLease(scope, 2), "task", true); fail() } catch (_: CacheMiss) { }
        }
        val a = f.request.copy(kinds = linkedSetOf(ProductionTaskKind.MATING, ProductionTaskKind.ESTRUS))
        val b = a.copy(kinds = linkedSetOf(ProductionTaskKind.ESTRUS, ProductionTaskKind.MATING))
        assertEquals(TaskCatalogCodecs.key(a), TaskCatalogCodecs.key(b))
    }
    @Test fun corruptedTypesVersionsEnumsAndScopeCannotBeAcceptedAsOfflineData() = database { cache, _ ->
        val encoded = TaskCatalogCodecs.encodeMetadata(f.metadata)
        val invalid = listOf(encoded.replace("\"format\":1", "\"format\":2"), encoded.replace("RUNNING", "FUTURE"),
            encoded.replace("\"count\":2", "\"count\":2.5"), encoded.replace("\"disabled\":false", "\"disabled\":\"false\""),
            encoded.replace("\"farm\":\"farm\"", "\"farm\":\"other\""), encoded.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":123456789.12345678900"))
        assertTrue(invalid.all { it != encoded })
        val reads = TaskCatalogReads(f.remote(ApiFailure.Network()), cache, { })
        for (bad in invalid) {
            cache.write(f.scope, "production_catalog_detail", "task", StoredSnapshot(bad, 1))
            try { reads.metadata(f.lease, "task"); fail() } catch (_: CacheInvalid) { }
        }
        cache.write(f.scope, "production_catalog_page", TaskCatalogCodecs.key(f.request), StoredSnapshot(TaskCatalogCodecs.encodePage(f.page.copy(request = f.request.copy(order = TaskCatalogOrder.PLANNED_ASC))), 2))
        try { reads.page(f.lease, f.request, true); fail() } catch (_: CacheInvalid) { }
    }
    @Test fun businessAuthenticationAndDelayedContextChangesDoNotFallbackOrReplaceVerifiedSnapshot() = database { cache, _ ->
        TaskCatalogReads(f.remote(), cache, { }) { 123 }.metadata(f.lease, "task")
        for (failure in listOf(ApiFailure.Business("SIMULATED"), ApiFailure.Authentication(), ApiFailure.Protocol())) {
            try { TaskCatalogReads(f.remote(failure), cache, { }).metadata(f.lease, "task"); fail() } catch (actual: ApiFailure) { assertSame(failure, actual) }
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var current = f.lease
        val remote = object : TaskCatalogRemote {
            override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage = error("Unused")
            override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata { entered.complete(Unit); release.await(); return f.metadata.copy(modifiedAt = f.at) }
        }
        coroutineScope {
            val old = async { try { TaskCatalogReads(remote, cache, { if (it != current) throw RequestContextChanged() }).metadata(f.lease, "task"); false } catch (_: RequestContextChanged) { true } }
            entered.await(); current = ScopeLease(f.scope.copy(farmId = "other"), 2); release.complete(Unit); assertTrue(old.await())
        }
        assertEquals(f.metadata, TaskCatalogReads(f.remote(), cache, { }).metadata(f.lease, "task", true).value)
    }
    @Test fun catalogCapacitySharesTheReadBudgetAndKeepsLocationsAndAccounts() = database { cache, _ ->
        cache.write(f.scope, "account", "current:en", StoredSnapshot("account", 0)); cache.write(f.scope, "locations", "all", StoredSnapshot("locations", 0))
        cache.write(f.scope, "production_task", "existing", StoredSnapshot("existing", 0))
        repeat(260) { n -> cache.write(f.scope, if (n % 2 == 0) "production_catalog_detail" else "production_catalog_page", "key$n", StoredSnapshot("payload$n", n.toLong())) }
        assertNull(cache.read(f.scope, "production_task", "existing")); assertNull(cache.read(f.scope, "production_catalog_detail", "key0"))
        assertNotNull(cache.read(f.scope, "production_catalog_detail", "key4")); assertNotNull(cache.read(f.scope, "production_catalog_page", "key259"))
        assertNotNull(cache.read(f.scope, "account", "current:en")); assertNotNull(cache.read(f.scope, "locations", "all"))
        val count = cache.readableDatabase.rawQuery("SELECT COUNT(*) FROM snapshots WHERE kind IN ('production_catalog_detail','production_catalog_page')", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(256, count)
    }
}
