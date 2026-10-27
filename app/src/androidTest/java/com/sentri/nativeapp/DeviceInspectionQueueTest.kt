package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.pigs.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeviceInspectionQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "device-${UUID.randomUUID()}"
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun localResolutionWaitsForReportAndUsesConfirmedServerIdAfterReopen() = database { queue, name ->
        val reportInput = ReportDeviceInspectionInput("pen", DeviceType.Water, remark = "水压低")
        val report = queue.enqueue(scope, "report", reportInput, emptySet(), 1)
        val resolveInput = ResolveDeviceInspectionInput(DeviceInspectionReference.LocalReport(report.record.id))
        val resolve = queue.enqueue(scope, "resolve", resolveInput, emptySet(), 2)
        assertEquals(setOf(report.record.id), resolve.record.dependsOn)
        SqliteCommandQueue(context, name).use { reopened ->
            assertEquals(setOf(reportInput, resolveInput), reopened.list(scope).map { it.input }.toSet())
            val attempt = reopened.claim(lease, 3)!!
            assertEquals(report.record.id, attempt.record.id)
            reopened.finish(attempt, CommandOutcome.Uncertain, 4)
            assertNull(reopened.claim(lease, 5))
            reopened.finish(attempt, CommandOutcome.Success(DeviceInspectionWriteReceipt("ReportDeviceInspection", "server-id").encode()), 6)
            val resolution = reopened.claim(lease, 7)!!
            assertEquals(resolve.record.id, resolution.record.id)
            assertEquals(ResolveDeviceInspectionInput(DeviceInspectionReference.Server("server-id")), resolution.input)
            assertEquals(resolveInput, reopened.list(scope).single { it.record.id == resolve.record.id }.input)
            try { reopened.finish(resolution, CommandOutcome.Success(DeviceInspectionWriteReceipt("ResolveDeviceInspection", "other").encode()), 8); fail() }
            catch (_: IllegalArgumentException) { }
            reopened.finish(resolution, CommandOutcome.Success(DeviceInspectionWriteReceipt("ResolveDeviceInspection", "server-id").encode()), 9)
            assertEquals(CommandStatus.SYNCED, reopened.list(scope).single { it.record.id == resolve.record.id }.record.status)
        }
    }
    @Test fun missingOrCancelledReportCannotBecomeLocalResolve() = database { queue, _ ->
        try { queue.enqueue(scope, "resolve", ResolveDeviceInspectionInput(DeviceInspectionReference.LocalReport("missing")), emptySet(), 1); fail() }
        catch (_: IllegalArgumentException) { }
        val report = queue.enqueue(scope, "report", ReportDeviceInspectionInput("pen", DeviceType.Feed), emptySet(), 2)
        val child = queue.enqueue(scope, "resolve", ResolveDeviceInspectionInput(DeviceInspectionReference.LocalReport(report.record.id)), emptySet(), 3)
        try { queue.cancel(scope, report.record.id, 4); fail() } catch (_: IllegalArgumentException) { }
        queue.cancel(scope, child.record.id, 5)
        queue.cancel(scope, report.record.id, 6)
        assertNull(queue.claim(lease, 7))
    }
    @Test fun payloadPreservesOptionalAndReferenceKinds() {
        val inputs = listOf(ReportDeviceInspectionInput("pen", DeviceType.Other, DeviceStatus.Normal, "", null, "existing"),
            ResolveDeviceInspectionInput(DeviceInspectionReference.LocalReport("local")),
            ResolveDeviceInspectionInput(DeviceInspectionReference.Server("server")))
        inputs.forEach { assertEquals(it, CommandPayloadCodec.decode(CommandPayloadCodec.VERSION, CommandPayloadCodec.encode(it))) }
    }
    @Test fun deviceListSnapshotRestoresAndStaysFarmScoped() = runBlocking {
        val name = "device-snapshot-${UUID.randomUUID()}"
        val lease = ScopeLease(scope, 1)
        val row = DeviceInspection("record", "farm", "pen", "Water", null, "Fault", "水压低",
            "2026-10-10T08:00:00.123456789+08:00", null, null, null)
        var calls = 0
        val remote = object : DeviceInspectionRemote {
            override suspend fun list(lease: ScopeLease): List<DeviceInspection> { calls++; return listOf(row) }
            override suspend fun forPen(lease: ScopeLease, penCode: String): List<DeviceInspection> =
                listOf(row).filter { it.penCode == penCode }.also { calls++ }
        }
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = DeviceInspectionReads(remote, cache, { }, { 100 })
                assertEquals(DataSource.NETWORK, reads.list(lease).source)
                assertEquals(DataSource.NETWORK, reads.forPen(lease, "pen").source)
            }
            SqliteSnapshotCache(context, name).use { reopened ->
                val reads = DeviceInspectionReads(remote, reopened, { }, { 200 })
                val restored = reads.list(lease, cacheOnly = true)
                assertEquals(DataSource.CACHE, restored.source)
                assertEquals(listOf(row), restored.value)
                assertEquals(listOf(row), reads.forPen(lease, "pen", cacheOnly = true).value)
                assertEquals(2, calls)
                try { reads.forPen(lease, "other", cacheOnly = true); fail() }
                catch (_: com.sentri.nativeapp.pigs.CacheMiss) { }
                try { reads.list(ScopeLease(scope.copy(farmId = "other"), 1), cacheOnly = true); fail() }
                catch (_: com.sentri.nativeapp.pigs.CacheMiss) { }
                try { reads.forPen(ScopeLease(scope.copy(farmId = "other"), 1), "pen", cacheOnly = true); fail() }
                catch (_: com.sentri.nativeapp.pigs.CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
