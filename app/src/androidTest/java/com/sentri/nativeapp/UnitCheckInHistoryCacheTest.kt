package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.UnitCheckInHistoryReads
import com.sentri.nativeapp.inspection.UnitCheckInHistoryRemote
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

class UnitCheckInHistoryCacheTest {
    @Test fun latestUnitCheckInRestoresOnlyForItsOriginalScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "checkin-history-${UUID.randomUUID()}.db"
        val scope = DataScope("dev", "oauth", "user", "farm")
        val lease = ScopeLease(scope, 1)
        val expected = UnitCheckInSummary("id", "farm", "Z-S-U", "2026-10-21T09:41:00+08:00", "Worker")
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reader = UnitCheckInHistoryReads(UnitCheckInHistoryRemote { _, _ -> expected }, cache, { }, { 100 })
                assertEquals(DataSource.NETWORK, reader.latest(lease, "Z-S-U").source)
            }
            SqliteSnapshotCache(context, name).use { cache ->
                val reader = UnitCheckInHistoryReads(UnitCheckInHistoryRemote { _, _ -> throw ApiFailure.Network() },
                    cache, { }, { 200 })
                val restored = reader.latest(lease, "Z-S-U")
                assertEquals(DataSource.CACHE, restored.source)
                assertEquals(expected, restored.value)
                try { reader.latest(lease, "OTHER", cacheOnly = true); fail() } catch (_: CacheMiss) { }
                try { reader.latest(ScopeLease(scope.copy(farmId = "other"), 1), "Z-S-U", cacheOnly = true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
