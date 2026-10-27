package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeUnitMaintenance
import com.sentri.nativeapp.home.HomeUnitMaintenanceReads
import com.sentri.nativeapp.home.HomeUnitMaintenanceRemote
import com.sentri.nativeapp.inspection.DeviceInspection
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeUnitMaintenanceCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-maintenance-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private var offline = false
    private val remote = HomeUnitMaintenanceRemote { _, unitId ->
        if (offline) throw ApiFailure.Network()
        HomeUnitMaintenance(unitId, setOf("P1", "P2"), listOf(
            DeviceInspection("issue", "farm", "P1", "Feed", null, "Fault", null,
                null, null, null, null)))
    }
    private fun reads() = HomeUnitMaintenanceReads(remote, cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun completeUnitIssueListRestoresAcrossProcessAndStaysScoped() = runBlocking {
        val online = reads().current(lease, "unit-7")
        assertEquals(DataSource.NETWORK, online.source)
        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reads().current(lease, "unit-7")
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(online.value, restored.value)
        assertEquals(1, restored.value.open.size)
        assertThrows(CacheMiss::class.java) { runBlocking { reads().current(lease, "unit-8", true) } }
        assertThrows(CacheMiss::class.java) { runBlocking {
            reads().current(lease.copy(scope = lease.scope.copy(farmId = "other")), "unit-7", true)
        } }
        Unit
    }
}
