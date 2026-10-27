package com.sentri.nativeapp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.home.HomeVaccinationCatalogReader
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.vaccination.VaccinationPage
import com.sentri.nativeapp.vaccination.VaccinationPageRequest
import com.sentri.nativeapp.vaccination.VaccinationReadMode
import com.sentri.nativeapp.vaccination.VaccinationReadRemote
import com.sentri.nativeapp.vaccination.VaccinationReads
import com.sentri.nativeapp.vaccination.VaccinationTask
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeVaccinationCatalogCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "home-vaccination-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = ScopeLease(scope, 1)
    private var calls = 0
    private var offline = false
    private var failSecondPage = false
    private val tasks = (0 until 205).map { index ->
        VaccinationTask("v-$index", "farm", "vaccine", "brand", "IN_PROGRESS", null, null,
            null, null, null, null, null, "IM", null, null, "mL", 1, BigDecimal.ONE,
            0, 0, 0, 0, "PLAN", null, null, null, false, null, null, null, emptyList())
    }
    private val remote = object : VaccinationReadRemote {
        override suspend fun page(lease: ScopeLease, request: VaccinationPageRequest): VaccinationPage {
            calls++
            assertEquals(VaccinationReadMode.ACTIVE, request.mode)
            if (offline || (failSecondPage && request.offset > 0)) throw ApiFailure.Network()
            return VaccinationPage(request, tasks.drop(request.offset).take(request.limit),
                request.offset + request.limit < tasks.size)
        }
        override suspend fun detail(lease: ScopeLease, id: String): VaccinationTask? = null
    }
    private fun reader() = HomeVaccinationCatalogReader(VaccinationReads(remote, cache, {}), cache, {}, { 123 })

    @After fun clean() {
        cache.close()
        context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun partialPageNeverBecomesHomeCountAndFullSnapshotRestoresAfterRestart() = runBlocking {
        failSecondPage = true
        assertThrows(ApiFailure.Network::class.java) { runBlocking { reader().load(lease) } }
        assertThrows(CacheMiss::class.java) { runBlocking { reader().load(lease, true) } }
        failSecondPage = false
        val online = reader().load(lease)
        assertEquals(DataSource.NETWORK, online.source)
        assertEquals(205, online.value.size)

        cache.close()
        cache = SqliteSnapshotCache(context, name)
        offline = true
        val restored = reader().load(lease)
        assertEquals(DataSource.CACHE, restored.source)
        assertEquals(205, restored.value.size)
        assertEquals(123, restored.fetchedAt)
        assertThrows(CacheMiss::class.java) { runBlocking {
            reader().load(lease.copy(scope = scope.copy(farmId = "another")), true)
        } }
        Unit
    }
}
