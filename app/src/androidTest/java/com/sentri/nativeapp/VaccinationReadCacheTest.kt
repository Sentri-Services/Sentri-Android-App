package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.vaccination.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID
import org.json.JSONObject

class VaccinationReadCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private val at = "2026-10-10T08:00:00.1234567890123+08:00"
    private val location = VaccinationLocation("farm", "P", "R", "S", "U", "Z", 0,
        VaccinationName("Z", "区", "Zone"), null, VaccinationName("U", "单元", "Unit"), "R")
    private val pig = VaccinationPig("A", "farm", "", null, at, "YLL", "PRODUCTION_BOAR", "ACTIVE",
        "Z.U.P", "batch", "B-1", location)
    private val exemption = VaccinationExemption("farm", "A", true,
        JsonValueAdapter.validate("[\"HEALTH_ABNORMAL\"]"), JsonValueAdapter.validate("[\"异常\"]"),
        at, null, at, "operator")
    private fun dose(target: String, index: Int, status: String) = VaccinationTargetDose("farm", "task", target,
        "vaccine", "brand", index, status, at, if (status == "COMPLETED") at else null, null, null,
        if (target == "A") pig else null, if (target == "A") exemption else null, true)
    private val value = VaccinationTask("task", "farm", "vaccine", "brand", "IN_PROGRESS", at, at, at,
        null, at, null, null, "IM", 7, "DAY", "mL", 2, BigDecimal("123456789.12345678900"),
        1, 0, 0, 2, "PLAN", "plan", null, JsonValueAdapter.validate("{\"ratio\":0.1234567890123456789}"),
        false, null, VaccinationVaccine("vaccine", "farm", "疫苗", "Vaccine", "ACTIVE"),
        VaccinationBrand("brand", "farm", "vaccine", "品牌", "Brand", "液体", "ACTIVE"),
        listOf(dose("A", 1, "COMPLETED"), dose("A", 2, "PENDING"), dose("B", 1, "PENDING")))
    private class FakeRemote(private val value: VaccinationTask) : VaccinationReadRemote {
        var calls = 0
        override suspend fun page(lease: ScopeLease, request: VaccinationPageRequest): VaccinationPage {
            calls++; return VaccinationPage(request, listOf(value), false)
        }
        override suspend fun detail(lease: ScopeLease, id: String): VaccinationTask? {
            calls++; return if (id == value.id) value else null
        }
    }
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "vaccination-read-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun separateListModesAndDetailRestoreEveryDoseAfterDatabaseReopen() = database { cache, name ->
        val remote = FakeRemote(value); val reads = VaccinationReads(remote, cache, { }, { 1000L })
        val all = VaccinationPageRequest(limit = 50)
        val today = VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW,
            windowStart = "2026-10-10T00:00:00Z", windowEnd = "2026-10-11T00:00:00Z")
        reads.page(lease, all); reads.page(lease, today); reads.detail(lease, "task")
        assertEquals(3, remote.calls)
        assertNotEquals(VaccinationReadCodec.key(all), VaccinationReadCodec.key(today))
        SqliteSnapshotCache(context, name).use { reopened ->
            val restored = VaccinationReads(remote, reopened, { }, { 2000L })
            val full = restored.page(lease, all, true)
            val prefetch = restored.page(lease, today, true)
            val detail = restored.detail(lease, "task", true)
            assertEquals(DataSource.CACHE, full.source); assertEquals(DataSource.CACHE, prefetch.source)
            assertEquals(1000L, detail.fetchedAt)
            assertEquals(value, detail.value)
            assertEquals(BigDecimal("123456789.12345678900"), full.value.rows.single().dose)
            assertEquals(listOf(1, 2), detail.value!!.targets.filter { it.targetId == "A" }.map { it.doseIndex })
            assertEquals(exemption, detail.value!!.targets.first().exemption)
            assertTrue(detail.value!!.targets.all { it.exemptionQueried })
            assertEquals(3, remote.calls)
        }
    }
    @Test fun missingAndCorruptSnapshotsAreNeverBorrowedAcrossScope() = database { cache, _ ->
        val reads = VaccinationReads(FakeRemote(value), cache, { })
        reads.detail(lease, "task")
        try { reads.detail(lease.copy(scope = scope.copy(farmId = "other")), "task", true); fail() }
        catch (_: CacheMiss) { }
        cache.write(scope, "vaccination_detail", "task", StoredSnapshot(
            VaccinationReadCodec.encodeDetail(value).replace("\"doseCount\":2", "\"doseCount\":0"), 1))
        try { reads.detail(lease, "task", true); fail() } catch (_: CacheInvalid) { }
        val other = VaccinationReadCodec.encodeDetail(value).replace("\"farm\":\"farm\"", "\"farm\":\"other\"")
        cache.write(scope, "vaccination_detail", "task", StoredSnapshot(other, 1))
        try { reads.detail(lease, "task", true); fail() } catch (_: CacheInvalid) { }
    }
    @Test fun oldSnapshotKeepsExemptionStateUnknownUntilRefreshed() {
        val saved = JSONObject(VaccinationReadCodec.encodeDetail(value))
        val targets = saved.getJSONObject("value").getJSONArray("targets")
        for (index in 0 until targets.length()) {
            targets.getJSONObject(index).remove("exemptionQueried")
            targets.getJSONObject(index).remove("exemption")
        }
        val restored = VaccinationReadCodec.decodeDetail(saved.toString())!!
        assertTrue(restored.targets.all { !it.exemptionQueried && it.exemption == null })
    }
}
