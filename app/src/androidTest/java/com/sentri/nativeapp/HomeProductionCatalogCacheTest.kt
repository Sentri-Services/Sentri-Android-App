package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeProductionCatalogReader
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskCatalogPage
import com.sentri.nativeapp.production.TaskCatalogReads
import com.sentri.nativeapp.production.TaskCatalogRemote
import com.sentri.nativeapp.production.TaskCatalogRequest
import com.sentri.nativeapp.production.TaskMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeProductionCatalogCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-catalog-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private var calls = 0
    private var offline = false
    private val tasks = (0 until 205).map { index ->
        TaskMetadata("task-$index", "farm", ProductionTaskKind.PREGNANCY, ProductionTaskStatus.RUNNING,
            null, null, null, null, null, null, TaskBatch("batch-$index", "B$index", null, null, emptyList()),
            null, emptyList(), emptyList(), emptyList(), emptyList())
    }
    private val remote = object : TaskCatalogRemote {
        override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage {
            calls++
            if (offline) throw ApiFailure.Network()
            return TaskCatalogPage(request, tasks.drop(request.offset).take(request.limit), tasks.size)
        }
        override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? = null
    }
    private fun reader() = HomeProductionCatalogReader(TaskCatalogReads(remote, cache, {}), cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun fullCatalogIsSavedAtomicallyAndRestoredAfterRestart() = runBlocking {
        val online = reader().load(lease)
        assertEquals(DataSource.NETWORK, online.source)
        assertEquals(205, online.value.size)
        assertEquals(2, calls)

        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reader().load(lease)
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(205, restored.value.size)
        assertEquals(123, restored.fetchedAt)
        assertEquals(3, calls)
        assertThrows(CacheMiss::class.java) { runBlocking { reader().load(lease.copy(scope = scope.copy(farmId = "other")), true) } }
        Unit
    }
}
