package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeTransferCatalogReader
import com.sentri.nativeapp.home.HomeTransferPage
import com.sentri.nativeapp.home.HomeTransferRemote
import com.sentri.nativeapp.home.HomeTransferTask
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
class HomeTransferCatalogCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-transfer-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private var offline = false
    private var failSecondPage = false
    private var changedTotal = false
    private val tasks = (0 until 205).map { index ->
        HomeTransferTask("transfer-$index", "farm", "IN_PROGRESS", "PRODUCTION_SOW",
            null, if (index == 0) null else "31", listOf("Z-A-7"), listOf("Z-B-9"))
    }
    private val remote = object : HomeTransferRemote {
        override suspend fun page(lease: ScopeLease, offset: Int, limit: Int): HomeTransferPage {
            assertEquals(200, limit)
            if (offline || failSecondPage && offset > 0) throw ApiFailure.Network()
            return HomeTransferPage(tasks.drop(offset).take(limit), if (changedTotal && offset > 0) 206 else 205)
        }
    }
    private fun reader() = HomeTransferCatalogReader(remote, cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
    }

    @Test fun partialFetchCannotBecomeHomeCountAndFullSnapshotSurvivesRestart() = runBlocking {
        failSecondPage = true
        assertThrows(ApiFailure.Network::class.java) { runBlocking { reader().load(lease) } }
        assertThrows(CacheMiss::class.java) { runBlocking { reader().load(lease, true) } }
        failSecondPage = false
        changedTotal = true
        assertThrows(ApiFailure.Protocol::class.java) { runBlocking { reader().load(lease) } }
        changedTotal = false
        assertEquals(DataSource.NETWORK, reader().load(lease).source)
        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reader().load(lease)
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(205, restored.value.size)
        assertEquals(null, restored.value.first().plannedAt)
        assertEquals(null, restored.value.first().batchSerial)
        assertEquals(123, restored.fetchedAt)
        assertThrows(CacheMiss::class.java) { runBlocking {
            reader().load(lease.copy(scope = scope.copy(farmId = "another")), true)
        } }
        Unit
    }
}
