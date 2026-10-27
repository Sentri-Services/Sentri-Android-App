package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.RecentUnitCheckInsPage
import com.sentri.nativeapp.inspection.RecentUnitCheckInsReads
import com.sentri.nativeapp.inspection.RecentUnitCheckInsRemote
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecentUnitCheckInsCacheTest {
    @Test fun aPageRestoresOnlyForTheSameFarmUnitAndOffset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recent-checkins-${UUID.randomUUID()}.db"
        val scope = DataScope("dev", "oauth", "user", "farm")
        val lease = ScopeLease(scope, 1)
        val page = RecentUnitCheckInsPage(listOf(UnitCheckInSummary("id", "farm", "Z-S-U",
            "2026-10-21T09:41:00+08:00", "Worker")), 0, false)
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reader = RecentUnitCheckInsReads(RecentUnitCheckInsRemote { _, _, _ -> page },
                    cache, { }, { 100 })
                assertEquals(DataSource.NETWORK, reader.page(lease, "Z-S-U").source)
            }
            SqliteSnapshotCache(context, name).use { cache ->
                val reader = RecentUnitCheckInsReads(RecentUnitCheckInsRemote { _, _, _ ->
                    throw ApiFailure.Network() }, cache, { }, { 200 })
                val restored = reader.page(lease, "Z-S-U")
                assertEquals(DataSource.CACHE, restored.source)
                assertEquals(page, restored.value)
                try { reader.page(lease, "Z-S-U", 20, cacheOnly = true); fail() } catch (_: CacheMiss) { }
                try { reader.page(lease, "OTHER", cacheOnly = true); fail() } catch (_: CacheMiss) { }
                try { reader.page(ScopeLease(scope.copy(farmId = "other"), 1), "Z-S-U", cacheOnly = true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
