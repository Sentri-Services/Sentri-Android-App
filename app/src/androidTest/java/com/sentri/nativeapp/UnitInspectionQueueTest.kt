package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.network.ApiFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class UnitInspectionQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)
    @Test fun exactDecimalPayloadAndPerUnitOrderSurviveReopen() {
        val name = "unit-command-${UUID.randomUUID()}"
        try {
            SqliteCommandQueue(context, name).use { queue ->
                val first = SubmitUnitInspectionInput("unit", "stable-id", temperature = BigDecimal("21.2500"),
                    humidity = BigDecimal.ZERO, environmentRemarks = "")
                val a = queue.enqueue(scope, "inspection", first, emptySet(), 100)
                val b = queue.enqueue(scope, "remark", SubmitUnitRemarkInput("unit", "交班备注"), emptySet(), 0)
                assertEquals(setOf(a.record.id), b.record.dependsOn)
            }
            SqliteCommandQueue(context, name).use { reopened ->
                val commands = reopened.list(scope)
                val first = commands.single { it.input is SubmitUnitInspectionInput }
                val second = commands.single { it.input is SubmitUnitRemarkInput }
                assertEquals(BigDecimal("21.2500"), (first.input as SubmitUnitInspectionInput).temperature)
                assertEquals("", (first.input as SubmitUnitInspectionInput).environmentRemarks)
                assertEquals(first.record.id, reopened.claim(lease, 101)?.record?.id)
                val attempted = reopened.list(scope).single { it.record.id == first.record.id }
                try { reopened.finish(attempted, CommandOutcome.Success(UnitInspectionWriteReceipt("SubmitUnitInspection", "wrong").encode()), 102); fail() }
                catch (_: Exception) { }
                val active = reopened.list(scope).single { it.record.id == first.record.id }
                reopened.finish(active, CommandOutcome.Success(UnitInspectionWriteReceipt("SubmitUnitInspection", "stable-id").encode()), 103)
                assertEquals(second.record.id, reopened.claim(lease, 104)?.record?.id)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun separateInspectionAndRemarkSnapshotsRemainScopedAndExact() = runBlocking {
        val name = "unit-cache-${UUID.randomUUID()}"
        val scopeLease = ScopeLease(scope, 1)
        val inspection = UnitInspectionRecord("id", "farm", "unit", "2026-10-10T08:00:00.123456789+08:00", null,
            BigDecimal("21.2500"), null, null, null, "normal", null, BigDecimal("123.4500"), null,
            "", "excellent", "yes", null)
        val remark = UnitRemarkRecord("farm", "unit", "交班备注", null, null)
        var remoteCalls = 0
        val remote = object : UnitInspectionRemote {
            override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = listOf(inspection).also { remoteCalls++ }
            override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = listOf(inspection).filter { it.id in ids }
            override suspend fun remarks(lease: ScopeLease, unitCode: String) = listOf(remark).also { remoteCalls++ }
        }
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = UnitInspectionReads(remote, cache, { }, { 100 })
                reads.inspections(scopeLease, "2026-10-10T00:00:00+08:00")
                reads.remarks(scopeLease, "unit")
            }
            SqliteSnapshotCache(context, name).use { reopened ->
                val reads = UnitInspectionReads(remote, reopened, { }, { 200 })
                assertEquals(listOf(inspection), reads.inspections(scopeLease, "2026-10-10T00:00:00+08:00", cacheOnly = true).value)
                assertEquals(listOf(remark), reads.remarks(scopeLease, "unit", cacheOnly = true).value)
                assertEquals(2, remoteCalls)
                try { reads.inspections(scopeLease, null, cacheOnly = true); fail() } catch (_: CacheMiss) { }
                try { reads.remarks(ScopeLease(scope.copy(farmId = "other"), 1), "unit", true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun checkInDetailIsExactUnitScopedAndRestoresOffline() = runBlocking {
        val name = "unit-detail-${UUID.randomUUID()}"
        val scopeLease = ScopeLease(scope, 1)
        val record = UnitInspectionRecord("check-1", "farm", "unit", "2026-10-10T08:00:00+08:00", null,
            BigDecimal("21.2500"), null, null, null, null, null, null, null,
            null, null, null, null)
        var calls = 0
        val remote = object : UnitInspectionRemote {
            override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = emptyList<UnitInspectionRecord>()
            override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = listOf(record).also { calls++ }
            override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
        }
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = UnitInspectionReads(remote, cache, { }, { 100 })
                assertEquals(record, reads.byId(scopeLease, "unit", "check-1").value)
                try { reads.byId(scopeLease, "other", "check-1"); fail() }
                catch (_: ApiFailure.Protocol) { }
            }
            SqliteSnapshotCache(context, name).use { reopened ->
                val reads = UnitInspectionReads(remote, reopened, { }, { 200 })
                assertEquals(record, reads.byId(scopeLease, "unit", "check-1", cacheOnly = true).value)
                assertEquals(2, calls)
                try { reads.byId(scopeLease, "other", "check-1", cacheOnly = true); fail() }
                catch (_: CacheMiss) { }
                try { reads.byId(ScopeLease(scope.copy(farmId = "another"), 1), "unit", "check-1", true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
