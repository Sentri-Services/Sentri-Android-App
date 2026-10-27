package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.sampling.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SamplingReadCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private val pig = SamplingPig("pig", "farm", "P-1", null, "B", "YLL", "ACTIVE",
        "Z.U.P", "SELFBRED", "FEMALE", SamplingLocation("farm", "Z", "U", "S", "R", "P",
            "z", "u", "s", "r", "g", 1, 0, SamplingName("Z", "区", "Zone"), null, null, "R", "01"))
    private val user = SamplingUser("person", "farm", "A", "B", "staff", "Worker", null)
    private val row = SamplingTask("task", "farm", "sampling", "test", at, "creator", false, false,
        false, null, null, null, null, null, null, null, null, null, null,
        JsonValueAdapter.validate("[123456789.12345678900]"), null,
        listOf(SamplingTarget(1,"farm","test","pig","PIG",pig,null),
            SamplingTarget(2,"farm","test","person","PERSON",null,user),
            SamplingTarget(3,"farm","test","surface","ENVIRONMENT",null,null),
            SamplingTarget(4,"farm","test","feed","MATERIAL",null,null)),
        SamplingTest("test","farm","sampling","SC-1","ASF","BLOOD_TUBE",at,
            listOf(SamplingTestTarget("plan","farm","test",null,"BLOOD","PIG",
                JsonValueAdapter.validate("[\"pig\",123456789.12345678900]")))),
        listOf(SamplingReport(1,"farm","task","report","application/pdf",at,"Result",null)))
    private class FakeRemote(private val row: SamplingTask) : SamplingReadRemote {
        var calls = 0
        override suspend fun page(lease: ScopeLease, request: SamplingPageRequest): SamplingPage {
            calls++; return SamplingPage(request, listOf(row), false)
        }
        override suspend fun detail(lease: ScopeLease, id: String): SamplingTask? {
            calls++; return if (id == row.id) row else null
        }
    }
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "sampling-read-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun fullAndHomePagesAndDetailRestoreAllTargetKinds() = database { cache, name ->
        val remote = FakeRemote(row); val reads = SamplingReads(remote, cache, { }, { 1000L })
        val full = SamplingPageRequest(limit = 50)
        val home = SamplingPageRequest(SamplingReadMode.UNSAMPLED_OR_SAMPLED_IN_WINDOW,
            "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z")
        reads.page(lease, full); reads.page(lease, home); reads.detail(lease, "task")
        assertEquals(3, remote.calls)
        assertNotEquals(SamplingReadCodec.key(full), SamplingReadCodec.key(home))
        SqliteSnapshotCache(context, name).use { reopened ->
            val restored = SamplingReads(remote, reopened, { }, { 2000L })
            assertEquals(row, restored.page(lease, full, true).value.rows.single())
            assertEquals(row, restored.page(lease, home, true).value.rows.single())
            val detail = restored.detail(lease, "task", true)
            assertEquals(DataSource.CACHE, detail.source)
            assertEquals(1000L, detail.fetchedAt)
            assertEquals(row, detail.value)
            assertEquals(4, detail.value!!.targets.size)
            assertEquals(3, remote.calls)
        }
    }
    @Test fun corruptOrForeignSnapshotsNeverBecomeTrusted() = database { cache, _ ->
        val reads = SamplingReads(FakeRemote(row), cache, { })
        reads.detail(lease, "task")
        try { reads.detail(lease.copy(scope = scope.copy(farmId = "other")), "task", true); fail() }
        catch (_: CacheMiss) { }
        val encoded = SamplingReadCodec.encodeDetail(row)
        cache.write(scope, "sampling_detail", "task", StoredSnapshot(encoded.replace("\"test\":\"test\"", "\"test\":\"other\""), 1))
        try { reads.detail(lease, "task", true); fail() } catch (_: CacheInvalid) { }
        cache.write(scope, "sampling_detail", "task", StoredSnapshot(encoded.replace("\"report\":\"report\"", "\"report\":\"\""), 1))
        try { reads.detail(lease, "task", true); fail() } catch (_: CacheInvalid) { }
    }
}
