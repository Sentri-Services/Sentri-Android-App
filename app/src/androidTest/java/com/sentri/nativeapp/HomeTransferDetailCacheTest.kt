package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeTransferDetail
import com.sentri.nativeapp.home.HomeTransferDetailReader
import com.sentri.nativeapp.home.HomeTransferDetailRemote
import com.sentri.nativeapp.home.TransferPigRecord
import com.sentri.nativeapp.home.TransferUnitProgress
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
class HomeTransferDetailCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-transfer-detail-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private var offline = false
    private val remote = object : HomeTransferDetailRemote {
        override suspend fun task(lease: ScopeLease, id: String): HomeTransferDetail? {
            if (offline) throw ApiFailure.Network()
            return HomeTransferDetail(id, "farm", "IN_PROGRESS", "31",
                listOf(TransferUnitProgress("Z-A-7", 2)), listOf(TransferUnitProgress("Z-B-9", 1)),
                listOf(TransferPigRecord("pig-1", "008", null, "TRANSFER_OUT")))
        }
    }
    private fun reader() = HomeTransferDetailReader(remote, cache, {}, { 123 })

    @After fun clean() { cache.close(); context.deleteDatabase(name) }

    @Test fun fullTaskSurvivesRestartOnlyWithinTheSameScopeAndId() = runBlocking {
        assertEquals(DataSource.NETWORK, reader().task(lease, "tr-31").source)
        cache.close(); cache = SqliteSnapshotCache(context, name)
        offline = true
        val saved = reader().task(lease, "tr-31")
        assertEquals(DataSource.CACHE, saved.source)
        assertEquals(1, saved.value!!.to.single().recorded)
        assertEquals("TRANSFER_OUT", saved.value!!.pigs.single().status)
        assertThrows(CacheMiss::class.java) { runBlocking { reader().task(lease, "tr-32", true) } }
        assertThrows(CacheMiss::class.java) { runBlocking {
            reader().task(lease.copy(scope = scope.copy(farmId = "other")), "tr-31", true)
        } }
        Unit
    }
}
