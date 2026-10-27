package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeUnitStock
import com.sentri.nativeapp.home.HomeUnitStockReads
import com.sentri.nativeapp.home.HomeUnitStockRemote
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
class HomeUnitStockCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-stock-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
    private var offline = false
    private val remote = HomeUnitStockRemote { _, id ->
        if (offline) throw ApiFailure.Network()
        HomeUnitStock(id, 86, 8)
    }
    private fun reads() = HomeUnitStockReads(remote, cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun stockRestoresOnlyWithinSameAccountFarmAndUnit() = runBlocking {
        assertEquals(DataSource.NETWORK, reads().current(lease, "unit-7").source)
        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reads().current(lease, "unit-7")
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(HomeUnitStock("unit-7", 86, 8), restored.value)
        assertEquals(123, restored.fetchedAt)
        assertThrows(CacheMiss::class.java) { runBlocking { reads().current(lease, "unit-8", true) } }
        assertThrows(CacheMiss::class.java) { runBlocking {
            reads().current(lease.copy(scope = lease.scope.copy(farmId = "other")), "unit-7", true)
        } }
        assertThrows(CacheMiss::class.java) { runBlocking {
            reads().current(lease.copy(scope = lease.scope.copy(userId = "other")), "unit-7", true)
        } }
        Unit
    }
}
