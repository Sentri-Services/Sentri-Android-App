package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.offline.SqliteCommandQueue
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProductionTaskCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private val at = "2026-10-09T01:00:00.123456789Z"
    private val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
    private val estrus = EstrusTask("task", "farm", "batch", "RUNNING", listOf(EstrusPig("pig", "TagA", null, false, EstrusStatus.IN_ESTRUS, at, true, at, location)))
    private val mating = MatingTask("task", "farm", "batch", "RUNNING", listOf(MatingPig("pig", "TagA", null, false,
        MatingSummary(1, at, at, at, "BoarA", at, "BoarA"), listOf(MatingRecord(at, 1, "BoarA", "operator", MatingMaterial(1, "boar", null, null, at, null, "BoarA", null), null)), location)))
    private val returned = ReEstrusTask("task", "farm", "batch", "RUNNING", listOf(ReEstrusPig("pig", "TagA", null, false, location,
        ReEstrusResult(ReEstrusStatus.SIGNS_OF_RE_ESTRUS, at, false, null, at, "operator", null, "operator"))))
    private val pregnancy = PregnancyTask("task", "farm", "batch", "RUNNING", listOf(PregnancyPig("pig", "TagA", null, false, location,
        PregnancyResult(PregnancyStatus.NOT_SURE, at, null, at, at, "operator", null, "operator"))))
    private fun key(type: String, id: String) = "${type.length}:$type${id.length}:$id"
    private fun database(block: (SqliteSnapshotCache, String) -> Unit) {
        val name = "task-cache-${UUID.randomUUID()}.db"
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun fourTaskSchemasPersistAndRestorePrecisionEnumsAndNestedRecords() = database { cache, name -> runBlocking {
        suspend fun <T : ProductionTaskSnapshot> roundtrip(value: T, codec: TaskCodec<T>) {
            assertEquals(value, codec.decode(codec.encode(value))); assertNull(codec.decode(codec.encode(null)))
            val online = ProductionTaskReads(TaskRemote { _, _ -> value }, cache, codec, { }) { 123 }
            assertEquals(DataSource.NETWORK, online.task(lease, "task").source)
            SqliteSnapshotCache(context, name).use { reopened ->
                val offline = ProductionTaskReads<T>(TaskRemote { _, _ -> throw ApiFailure.Network() }, reopened, codec, { })
                val result = offline.task(lease, "task")
                assertEquals(value, result.value); assertEquals(123L, result.fetchedAt); assertEquals(DataSource.CACHE, result.source)
                assertTrue(result.refreshFailure is ApiFailure.Network)
                assertEquals(value, offline.task(lease, "task", true).value)
            }
        }
        roundtrip(estrus, ProductionTaskCodecs.estrus); roundtrip(mating, ProductionTaskCodecs.mating)
        roundtrip(returned, ProductionTaskCodecs.reEstrus); roundtrip(pregnancy, ProductionTaskCodecs.pregnancy)
    } }
    @Test fun noSnapshotFromOtherScopeTypeOrRequestCanBeBorrowed() = database { cache, _ -> runBlocking {
        val reader = ProductionTaskReads(TaskRemote { _, _ -> returned }, cache, ProductionTaskCodecs.reEstrus, { })
        reader.task(lease, "task")
        val otherScopes = listOf(scope.copy(environment = "demo"), scope.copy(oauthId = "other"), scope.copy(userId = "other"), scope.copy(farmId = "other"))
        for (other in otherScopes) { try { reader.task(ScopeLease(other, 2), "task", true); fail() } catch (_: CacheMiss) { } }
        try { reader.task(lease, "other-task", true); fail() } catch (_: CacheMiss) { }
        val pregReader = ProductionTaskReads(TaskRemote { _, _ -> pregnancy }, cache, ProductionTaskCodecs.pregnancy, { })
        try { pregReader.task(lease, "task", true); fail() } catch (_: CacheMiss) { }
        cache.write(scope, "production_task", key("RE_ESTRUS", "task"), StoredSnapshot(ProductionTaskCodecs.pregnancy.encode(pregnancy), 1))
        try { reader.task(lease, "task", true); fail() } catch (_: CacheInvalid) { }
    } }
    @Test fun businessProtocolAndContextErrorsDoNotMaskWithGoodCache() = database { cache, _ -> runBlocking {
        var failure: Exception? = null; var foreign = false; var switched = false
        val remote = TaskRemote { _: ScopeLease, _: String -> failure?.let { throw it }; if (foreign) returned.copy(farmId = "other") else returned }
        val reader = ProductionTaskReads(remote, cache, ProductionTaskCodecs.reEstrus, { if (switched) throw RequestContextChanged() }) { 1 }
        reader.task(lease, "task")
        failure = ApiFailure.Protocol()
        try { reader.task(lease, "task"); fail() } catch (_: ApiFailure.Protocol) { }
        failure = ApiFailure.Business("simulated rejection")
        try { reader.task(lease, "task"); fail() } catch (_: ApiFailure.Business) { }
        failure = null; foreign = true
        try { reader.task(lease, "task"); fail() } catch (_: IllegalArgumentException) { }
        switched = true
        try { reader.task(lease, "task", true); fail() } catch (_: RequestContextChanged) { }
        switched = false
        assertEquals(returned, reader.task(lease, "task", true).value)
    } }
    @Test fun delayedTaskResponseCannotPopulateCacheAfterScopeSwitch() = database { cache, _ -> runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var current = lease
        val reader = ProductionTaskReads(TaskRemote { _: ScopeLease, _: String -> entered.complete(Unit); release.await(); returned }, cache, ProductionTaskCodecs.reEstrus,
            { if (it != current) throw RequestContextChanged() })
        val pending = async { try { reader.task(lease, "task"); false } catch (_: RequestContextChanged) { true } }
        entered.await(); current = lease.copy(revision = 2); release.complete(Unit)
        assertTrue(pending.await())
        assertNull(cache.read(scope, "production_task", key("RE_ESTRUS", "task")))
    } }
    @Test fun cachedAbsenceDiffersFromNeverFetchedAndCorruptPayload() = database { cache, _ -> runBlocking {
        val reader = ProductionTaskReads<ReEstrusTask>(TaskRemote { _, _ -> null }, cache, ProductionTaskCodecs.reEstrus, { })
        assertNull(reader.task(lease, "task").value)
        assertNull(reader.task(lease, "task", true).value)
        try { reader.task(lease, "missing", true); fail() } catch (_: CacheMiss) { }
        for (payload in listOf("{}", ProductionTaskCodecs.reEstrus.encode(returned).replace(".123456789Z", "-invalid"), ProductionTaskCodecs.reEstrus.encode(returned).replace("SIGNS_OF_RE_ESTRUS", "FUTURE"))) {
            cache.write(scope, "production_task", key("RE_ESTRUS", "task"), StoredSnapshot(payload, 1))
            try { reader.task(lease, "task", true); fail() } catch (_: CacheInvalid) { }
        }
    } }
    @Test fun taskSnapshotEvictionAndCacheClearNeverRemovePendingCommands() = database { cache, name ->
        val queueName = "$name-queue"
        try { SqliteCommandQueue(context, queueName).use { queue ->
            queue.enqueue(scope, "pending", CheckReEstrusInput("task", "pig", ReEstrusStatus.RE_ESTRUS, null, "unit"), emptySet(), 1)
            for (n in 0..256) cache.write(scope, "production_task", "task-$n", StoredSnapshot("snapshot", n.toLong()))
            assertNull(cache.read(scope, "production_task", "task-0"))
            assertNotNull(cache.read(scope, "production_task", "task-256"))
            cache.clear(scope)
            assertNotNull(queue.list(scope).single())
        } } finally { context.deleteDatabase(queueName) }
    }
}
