package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.sampling.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SamplingProcessRestoreTest {
    @Test fun scopedSamplingTargetsAndUnknownCommandSurviveForceStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("samplingStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sampling-process-test"
        val queueName = "$name-commands"
        val prefs = context.getSharedPreferences(name, 0)
        val scope = DataScope("dev", "oauth", "user", "farm")
        val lease = ScopeLease(scope, 1)
        val at = "2026-10-10T08:00:00.123456789+08:00"
        val row = SamplingTask("task", "farm", "sampling", "test", at, "creator", false, false,
            false, null, null, null, null, null, null, null, null, null, null,
            JsonValueAdapter.validate("[123456789.12345678900]"), null,
            listOf(SamplingTarget(1,"farm","test","pig","PIG",
                SamplingPig("pig","farm","P-1",null,null,null,"ACTIVE",null,"SELFBRED","FEMALE",null),null),
                SamplingTarget(2,"farm","test","person","PERSON",null,
                    SamplingUser("person","farm",null,null,"worker","Worker",null)),
                SamplingTarget(3,"farm","test","surface","ENVIRONMENT",null,null),
                SamplingTarget(4,"farm","test","feed","MATERIAL",null,null)),
            SamplingTest("test","farm","sampling","SC-1","ASF","BLOOD_TUBE",at,
                listOf(SamplingTestTarget("plan","farm","test",null,"BLOOD","PIG",
                    JsonValueAdapter.validate("[\"pig\"]")))), emptyList())
        val full = SamplingPageRequest(limit = 5)
        val home = SamplingPageRequest(SamplingReadMode.UNSAMPLED_OR_SAMPLED_IN_WINDOW,
            "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z", limit = 5)
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase(queueName)
            check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            val remote = object : SamplingReadRemote {
                override suspend fun page(lease: ScopeLease, request: SamplingPageRequest) = SamplingPage(request, listOf(row), false)
                override suspend fun detail(lease: ScopeLease, id: String) = if (id == row.id) row else null
            }
            SqliteCommandQueue(context, queueName).use { queue ->
                queue.enqueue(scope, "submit", SubmitSampledInput("task", "备注"), emptySet(), 1)
                val attempt = queue.claim(QueueContext(scope, 1, true, true), 2)!!
                queue.finish(attempt, CommandOutcome.Uncertain, 3)
                SqliteSnapshotCache(context, name).use { cache ->
                    val reads = SamplingReads(remote, cache, {}) { 123 }
                    reads.page(lease, full); reads.page(lease, home); reads.detail(lease, row.id)
                }
            }
            println("SAMPLING_PREPARED pid=${Process.myPid()} snapshots=3 commands=1")
        } else {
            try {
                val old = prefs.getInt("pid", -1); assertNotEquals(-1, old); assertNotEquals(old, Process.myPid())
                var calls = 0
                val remote = object : SamplingReadRemote {
                    override suspend fun page(lease: ScopeLease, request: SamplingPageRequest): SamplingPage {
                        calls++; throw ApiFailure.Network()
                    }
                    override suspend fun detail(lease: ScopeLease, id: String): SamplingTask? {
                        calls++; throw ApiFailure.Network()
                    }
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    SqliteSnapshotCache(context, name).use { cache ->
                        val reads = SamplingReads(remote, cache, {})
                        assertEquals(row, reads.page(lease, full, true).value.rows.single())
                        assertEquals(row, reads.page(lease, home, true).value.rows.single())
                        assertEquals(row, reads.detail(lease, row.id, true).value)
                        assertEquals(CommandStatus.UNCERTAIN, queue.list(scope).single().record.status)
                        assertNull(queue.claim(QueueContext(scope, 1, true, true), 4))
                        val workspace = SamplingWorkspaceReads(reads, queue, {}).task(lease, row.id, cacheOnly = true)
                        assertEquals(row, workspace.projection?.serverTask)
                        assertEquals(row, workspace.projection?.previewTask)
                        assertEquals(SamplingPreviewState.WAITING_CONFIRMATION, workspace.projection?.effects?.single()?.state)
                        assertEquals(0, calls)
                        println("SAMPLING_RESTORED previousPid=$old pid=${Process.myPid()} snapshots=3 commands=1 remoteReads=$calls")
                    }
                }
            } finally { context.deleteDatabase(name); context.deleteDatabase(queueName); prefs.edit().clear().commit() }
        }
    }
}
