package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomePenCount
import com.sentri.nativeapp.home.HomeRegisteredPen
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeUnitPenRosterReads
import com.sentri.nativeapp.home.HomeUnitPenRosterRemote
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.pigs.StoredSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeUnitPenRosterCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-pens-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private var offline = false
    private val remote = HomeUnitPenRosterRemote { _, id ->
        if (offline) throw ApiFailure.Network()
        HomeUnitPenRoster(id, 4, 1, listOf(HomePenCount("Z-A-7-P1", 1, 2),
            HomePenCount("Z-A-7-P2", 2, 1)), listOf(
            HomeRegisteredPen(1, "Z-A-7-P1", 1), HomeRegisteredPen(2, "Z-A-7-P2", 2),
            HomeRegisteredPen(3, "Z-A-7-P3", 3)))
    }
    private fun reads() = HomeUnitPenRosterReads(remote, cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun completeRosterRestoresAfterRestartWithoutCrossScopeLeak() = runBlocking {
        val online = reads().current(lease, "unit-7")
        assertEquals(DataSource.NETWORK, online.source)
        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reads().current(lease, "unit-7")
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(online.value, restored.value)
        assertEquals("Z-A-7-P3", restored.value.registeredPens?.last()?.code)
        assertEquals(123, restored.fetchedAt)
        assertThrows(CacheMiss::class.java) { runBlocking { reads().current(lease, "unit-8", true) } }
        assertThrows(CacheMiss::class.java) { runBlocking {
            reads().current(lease.copy(scope = lease.scope.copy(farmId = "other")), "unit-7", true)
        } }
        Unit
    }

    @Test fun oldOccupiedOnlySnapshotDoesNotInventEmptyPens() = runBlocking {
        cache.write(lease.scope, "home_unit_pen_roster", "unit-7", StoredSnapshot(
            """{"format":1,"unit":"unit-7","total":1,"unassigned":0,"pens":[{"code":"Z-A-7-P1","number":1,"pigs":1}]}""", 100))
        val old = reads().current(lease, "unit-7", true)
        assertEquals(DataSource.CACHE, old.source)
        assertEquals(1, old.value.pens.single().pigs)
        assertEquals(null, old.value.registeredPens)
    }
}
