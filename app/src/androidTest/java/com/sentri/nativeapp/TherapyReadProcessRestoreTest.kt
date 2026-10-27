package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.offline.SqliteCommandQueue
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.therapy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

class TherapyReadProcessRestoreTest {
    @Test fun separateProcessRestoresTherapyAndReferencesWithoutRemoteReads() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("therapyReadStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "therapy-read-process-test"
        val queueName = "$name-commands"
        val prefs = context.getSharedPreferences(name, 0)
        val scope = DataScope("dev", "oauth", "user", "farm")
        val lease = ScopeLease(scope, 1)
        val at = "2026-10-10T10:00:00.1234567890123+08:00"
        val row = TherapyRow("therapy", "farm", "rx", at, BigDecimal("987654321.12345678900"), null,
            "med", null, null, null, null, null, null, at, "InProgress", 2, "treatment", null, "ml",
            listOf(TherapyTargetTask("farm", "rx", "therapy", "scheduled", "pig", 1, "InProgress", at, null, null, null, null, null)))
        val request = TherapyPageRequest(limit = 1)
        val prefetch = TherapyPageRequest(mode = TherapyReadMode.ACTIVE_OR_EXECUTED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00.000Z", windowEnd = "2026-10-11T00:00:00.000Z", limit = 1)
        val medication = TherapyMedication("med", "farm", "药", "Medication")
        val catalog = TherapyMedicationCatalog("cat", "farm", "med", "剂型", "Catalog", null, null)
        val treatment = TherapyTreatment("treatment", "治疗", "Treatment", "详情", "Detail")
        val prescriptionContext = TherapyPrescriptionContext("rx", "farm", "InProgress", setOf("pig"), mapOf("therapy" to "InProgress"))
        if (stage == "prepare") {
            context.deleteDatabase(name)
            context.deleteDatabase(queueName)
            check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            val remote = object : TherapyReadRemote {
                override suspend fun page(lease: ScopeLease, request: TherapyPageRequest) = TherapyPage(request, listOf(row), false)
                override suspend fun detail(lease: ScopeLease, id: String) = if (id == row.id) row else null
                override suspend fun medications(lease: ScopeLease) = listOf(medication)
                override suspend fun catalogs(lease: ScopeLease) = listOf(catalog)
                override suspend fun treatments(lease: ScopeLease) = listOf(treatment)
                override suspend fun context(lease: ScopeLease, prescriptionId: String) = prescriptionContext
            }
            SqliteCommandQueue(context, queueName).use { queue ->
                queue.enqueue(scope, "complete", CompleteTherapyTargetInput("rx", "therapy", "scheduled", listOf("pig"), at), emptySet(), 1)
                SqliteSnapshotCache(context, name).use { cache ->
                    val reads = TherapyReads(remote, cache, {}) { 123 }
                    reads.page(lease, request); reads.page(lease, prefetch); reads.detail(lease, row.id)
                    reads.medications(lease); reads.catalogs(lease); reads.treatments(lease); reads.context(lease, "rx")
                    assertEquals(TherapyPreviewState.PREVIEW, TherapyWorkspaceReads(reads, queue, {}).prescription(lease, "rx").projection?.effects?.single()?.state)
                }
            }
            println("THERAPY_READ_PREPARED pid=${Process.myPid()} snapshots=8 commands=1")
        } else {
            try {
                val old = prefs.getInt("pid", -1); assertNotEquals(-1, old); assertNotEquals(old, Process.myPid())
                var calls = 0
                val remote = object : TherapyReadRemote {
                    private fun failed(): Nothing { calls++; throw ApiFailure.Network() }
                    override suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage = failed()
                    override suspend fun detail(lease: ScopeLease, id: String): TherapyRow? = failed()
                    override suspend fun medications(lease: ScopeLease): List<TherapyMedication> = failed()
                    override suspend fun catalogs(lease: ScopeLease): List<TherapyMedicationCatalog> = failed()
                    override suspend fun treatments(lease: ScopeLease): List<TherapyTreatment> = failed()
                    override suspend fun context(lease: ScopeLease, prescriptionId: String): TherapyPrescriptionContext? = failed()
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    SqliteSnapshotCache(context, name).use { cache ->
                        val reads = TherapyReads(remote, cache, {})
                        assertEquals(row, reads.page(lease, request, true).value.rows.single())
                        assertEquals(row, reads.page(lease, prefetch, true).value.rows.single())
                        assertEquals(row, reads.detail(lease, row.id, true).value)
                        assertEquals(listOf(medication), reads.medications(lease, true).value)
                        assertEquals(listOf(catalog), reads.catalogs(lease, true).value)
                        assertEquals(listOf(treatment), reads.treatments(lease, true).value)
                        assertEquals(prescriptionContext, reads.context(lease, "rx", true).value)
                        val workspace = TherapyWorkspaceReads(reads, queue, {}).prescription(lease, "rx", cacheOnly = true)
                        assertEquals(TherapyPreviewState.PREVIEW, workspace.projection?.effects?.single()?.state)
                        assertEquals(listOf(1, 2), workspace.projection?.previewRows?.get("therapy")?.tasks?.map { it.times })
                        assertEquals(1, workspace.projection?.serverRows?.get("therapy")?.tasks?.size)
                        assertEquals(1, queue.list(scope).size)
                        assertEquals(0, calls)
                        println("THERAPY_READ_RESTORED previousPid=$old pid=${Process.myPid()} snapshots=8 commands=1 remoteReads=$calls")
                    }
                }
            } finally { context.deleteDatabase(name); context.deleteDatabase(queueName); prefs.edit().clear().commit() }
        }
    }
}
