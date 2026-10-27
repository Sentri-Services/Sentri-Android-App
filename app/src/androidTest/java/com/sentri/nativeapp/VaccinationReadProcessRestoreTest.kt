package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.offline.SqliteCommandQueue
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.vaccination.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

class VaccinationReadProcessRestoreTest {
    @Test fun dosesAndPendingCommandSurviveForceStopWithoutRemoteReads() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("vaccinationReadStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vaccination-read-process-test"
        val queueName = "$name-commands"
        val prefs = context.getSharedPreferences(name, 0)
        val scope = DataScope("dev", "oauth", "user", "farm")
        val lease = ScopeLease(scope, 1)
        val at = "2026-10-10T08:00:00.123456789+08:00"
        val exemption = VaccinationExemption("farm", "pig", false,
            JsonValueAdapter.validate("[]"), JsonValueAdapter.validate("[]"), at, null, at, null)
        val row = VaccinationTask("task", "farm", "vaccine", "brand", "IN_PROGRESS", at, at, at,
            null, at, null, null, "IM", 7, "DAY", "mL", 2, BigDecimal("987654321.12345678900"),
            1, 0, 0, 1, "PLAN", "plan", null, null, false, null, null, null,
            listOf(VaccinationTargetDose("farm", "task", "pig", "vaccine", "brand", 1, "COMPLETED", at, at, null, null, null, exemption, true),
                VaccinationTargetDose("farm", "task", "pig", "vaccine", "brand", 2, "PENDING", at, null, null, null, null, exemption, true)))
        val full = VaccinationPageRequest(limit = 1)
        val today = VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00Z", windowEnd = "2026-10-11T00:00:00Z", limit = 1)
        if (stage == "prepare") {
            context.deleteDatabase(name); context.deleteDatabase(queueName)
            check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            val remote = object : VaccinationReadRemote {
                override suspend fun page(lease: ScopeLease, request: VaccinationPageRequest) = VaccinationPage(request, listOf(row), false)
                override suspend fun detail(lease: ScopeLease, id: String) = if (id == row.id) row else null
            }
            SqliteCommandQueue(context, queueName).use { queue ->
                queue.enqueue(scope, "dose", ExecuteVaccinationTaskInput("task", listOf("pig"), at), emptySet(), 1)
                SqliteSnapshotCache(context, name).use { cache ->
                    val reads = VaccinationReads(remote, cache, {}) { 123 }
                    reads.page(lease, full); reads.page(lease, today); reads.detail(lease, row.id)
                    assertEquals(VaccinationPreviewState.PREVIEW,
                        VaccinationWorkspaceReads(reads, queue, {}).task(lease, row.id).projection?.effects?.single()?.state)
                }
            }
            println("VACCINATION_READ_PREPARED pid=${Process.myPid()} snapshots=3 commands=1")
        } else {
            try {
                val old = prefs.getInt("pid", -1); assertNotEquals(-1, old); assertNotEquals(old, Process.myPid())
                var calls = 0
                val remote = object : VaccinationReadRemote {
                    override suspend fun page(lease: ScopeLease, request: VaccinationPageRequest): VaccinationPage {
                        calls++; throw ApiFailure.Network()
                    }
                    override suspend fun detail(lease: ScopeLease, id: String): VaccinationTask? {
                        calls++; throw ApiFailure.Network()
                    }
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    SqliteSnapshotCache(context, name).use { cache ->
                        val reads = VaccinationReads(remote, cache, {})
                        assertEquals(row, reads.page(lease, full, true).value.rows.single())
                        assertEquals(row, reads.page(lease, today, true).value.rows.single())
                        assertEquals(row, reads.detail(lease, row.id, true).value)
                        assertEquals(exemption, reads.detail(lease, row.id, true).value?.targets?.first()?.exemption)
                        val workspace = VaccinationWorkspaceReads(reads, queue, {}).task(lease, row.id, cacheOnly = true)
                        assertEquals(row, workspace.projection?.serverTask)
                        assertEquals("COMPLETED", workspace.projection?.previewTask?.status)
                        assertEquals(listOf("COMPLETED", "COMPLETED"), workspace.projection?.previewTask?.targets?.map { it.status })
                        assertEquals(VaccinationPreviewState.PREVIEW, workspace.projection?.effects?.single()?.state)
                        assertEquals(1, queue.list(scope).size)
                        assertEquals(0, calls)
                        println("VACCINATION_READ_RESTORED previousPid=$old pid=${Process.myPid()} snapshots=3 commands=1 remoteReads=$calls")
                    }
                }
            } finally { context.deleteDatabase(name); context.deleteDatabase(queueName); prefs.edit().clear().commit() }
        }
    }
}
