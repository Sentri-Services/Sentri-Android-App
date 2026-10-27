package com.sentri.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.auth.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PigCacheTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "pig-cache-test-${UUID.randomUUID()}.db"
    private var cache = SqliteSnapshotCache(context, name)
    private val scope = DataScope("dev", "oauth-a", "user-a", "farm-a")
    private val lease = ScopeLease(scope, 1)
    private var current = lease
    private val remote = Remote()
    private fun repository() = PigRepository(remote, cache, { if (it != current) throw RequestContextChanged() }) { 100 }
    private fun pig(farm: String = scope.farmId) = Pig("p1", farm, "TagA", null, "Z-S-U-1", "sow", null, "alive", null, null, BigDecimal("123.123456789"), "2026-01-01T00:00:00.123456+08:00", null, null, null, null, "u1", "Z-S-U-1", null, null, emptyList(), emptyList())
    private inner class Remote : PigRemote {
        var calls = 0
        var failure: Exception? = null
        override suspend fun locations(scope: DataScope): Locations { calls++; failure?.let { throw it }; return Locations(emptyList(), emptyList()) }
        override suspend fun page(scope: DataScope, filter: PigFilter, offset: Int): PigPage { calls++; failure?.let { throw it }; return PigPage(listOf(pig(scope.farmId)), 1, offset, filter.pageSize) }
        override suspend fun detail(scope: DataScope, id: String): PigDetail { calls++; failure?.let { throw it }; return PigDetail(pig(scope.farmId), JsonValue("[null,123.123456789]"), emptyList()) }
    }
    @After fun close() {
        cache.close(); context.deleteDatabase(name)
        context.getSharedPreferences(name, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }

    @Test fun cacheSurvivesReopenWithoutNetworkAndPreservesScalars() = runBlocking {
        val repository = repository()
        repository.page(lease, PigFilter("Z-S-U"), 0)
        repository.detail(lease, "p1")
        repository.remember(lease, BrowsePosition(PigFilter("Z-S-U", search = "TagA"), 20, "p1", true))
        val calls = remote.calls
        cache.close(); cache = SqliteSnapshotCache(context, name)
        remote.failure = ApiFailure.Network()
        val reopened = repository()
        val page = reopened.page(lease, PigFilter("Z-S-U"), 0, cacheOnly = true)
        assertEquals(DataSource.CACHE, page.source)
        assertEquals(BigDecimal("123.123456789"), page.value.pigs.single().weight)
        assertEquals("2026-01-01T00:00:00.123456+08:00", page.value.pigs.single().birthday)
        assertEquals("[null,123.123456789]", reopened.detail(lease, "p1", true).value?.deathImages?.encoded)
        assertEquals("p1", reopened.restore(lease)?.detailId)
        assertEquals(20, reopened.restore(lease)?.offset)
        assertEquals(calls, remote.calls)
    }
    @Test fun isolatedByEnvironmentUserFarmAndRequestParameters() = runBlocking {
        val repository = repository()
        repository.page(lease, PigFilter("Z-S-U", search = "TagA"), 0)
        for (changed in listOf(scope.copy(environment = "demo"), scope.copy(oauthId = "other", userId = "other"), scope.copy(farmId = "other"))) {
            current = ScopeLease(changed, 2)
            try { repository.page(current, PigFilter("Z-S-U", search = "TagA"), 0, true); fail("Cross scope cache visible") } catch (_: CacheMiss) { }
        }
        current = lease
        for (filter in listOf(PigFilter("Z-S-U", search = "taga"), PigFilter("Z-S-U", search = "TagA", pageSize = 30), PigFilter("other", search = "TagA"))) {
            try { repository.page(lease, filter, 0, true); fail("Filter cache collision") } catch (_: CacheMiss) { }
        }
        try { repository.page(lease, PigFilter("Z-S-U", search = "TagA"), 20, true); fail("Page cache collision") } catch (_: CacheMiss) { }
    }
    @Test fun networkFallbackDoesNotHidePermissionDenial() = runBlocking {
        val repository = repository()
        repository.page(lease, PigFilter(), 0)
        remote.failure = ApiFailure.Network()
        assertNotNull(repository.page(lease, PigFilter(), 0).refreshFailure)
        remote.failure = ApiFailure.Business("denied")
        try { repository.page(lease, PigFilter(), 0); fail("Permission failure served stale data") } catch (_: ApiFailure.Business) { }
    }
    @Test fun clearOnlyCurrentScopeAndRejectCorruptSnapshots() = runBlocking {
        val repository = repository()
        repository.page(lease, PigFilter(), 0)
        val other = scope.copy(farmId = "farm-b")
        cache.write(other, "page", "other", StoredSnapshot("keep", 50))
        cache.writableDatabase.execSQL("UPDATE snapshots SET payload='{}' WHERE farm_id=?", arrayOf(scope.farmId))
        try { repository.page(lease, PigFilter(), 0, true); fail("Corrupt data accepted") } catch (_: CacheInvalid) { }
        repository.clear(lease)
        assertNull(cache.read(scope, "page", PigFilter().cacheKey(0)))
        assertEquals("keep", cache.read(other, "page", "other")?.payload)
    }
    @Test fun schemaUpgradeRetainsScopedRows() {
        cache.close(); context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE snapshots (environment TEXT NOT NULL, oauth_id TEXT NOT NULL, user_id TEXT NOT NULL, farm_id TEXT NOT NULL, kind TEXT NOT NULL, cache_key TEXT NOT NULL, payload TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(environment,oauth_id,user_id,farm_id,kind,cache_key))")
            db.execSQL("INSERT INTO snapshots VALUES ('dev','oauth-a','user-a','farm-a','page','key','saved',123)")
            db.version = 1
        }
        cache = SqliteSnapshotCache(context, name)
        assertEquals("saved", cache.read(scope, "page", "key")?.payload)
        assertEquals(2, cache.readableDatabase.version)
    }
    @Test fun replacingSnapshotRollsBackIfInsertFails() {
        val original = StoredSnapshot("original", 1)
        cache.write(scope, "page", "key", original)
        cache.writableDatabase.execSQL("CREATE TRIGGER reject_snapshot BEFORE INSERT ON snapshots WHEN NEW.payload='reject' BEGIN SELECT RAISE(ABORT,'test failure'); END")
        try { cache.write(scope, "page", "key", StoredSnapshot("reject", 2)); fail("Insert must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(original, cache.read(scope, "page", "key"))
    }
    @Test fun replacingAccountBindingCannotResurrectOldUserAfterClearingCurrentScope() {
        val newer = scope.copy(userId = "user-b")
        cache.write(scope, "account", "current", StoredSnapshot("older-user", 1))
        cache.write(scope, "page", "key", StoredSnapshot("older-data", 1))
        cache.write(newer, "account", "current", StoredSnapshot("newer-user", 2))
        assertEquals("user-b", cache.readAccount(scope.environment, scope.oauthId, scope.farmId)?.userId)
        assertNull(cache.read(scope, "account", "current"))
        cache.clear(newer)
        assertNull(cache.readAccount(scope.environment, scope.oauthId, scope.farmId))
        assertEquals("older-data", cache.read(scope, "page", "key")?.payload)
    }
    @Test fun retentionKeepsNewestSnapshotWithEqualTimestampsAndPreservesMetadataAndOtherScopes() {
        val other = scope.copy(farmId = "other")
        cache.write(other, "page", "key", StoredSnapshot("other-farm", 1))
        for (kind in listOf("account", "locations", "browse")) cache.write(scope, kind, "metadata", StoredSnapshot(kind, 1))
        for (index in 0..256) cache.write(scope, "page", index.toString(), StoredSnapshot("$index", 1))
        assertNull(cache.read(scope, "page", "0"))
        assertEquals("256", cache.read(scope, "page", "256")?.payload)
        assertEquals("other-farm", cache.read(other, "page", "key")?.payload)
        for (kind in listOf("account", "locations", "browse")) assertNotNull(cache.read(scope, kind, "metadata"))
    }
    @Test fun lateResponseAfterFarmSwitchCannotWriteEitherScope() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = object : PigRemote by remote {
            override suspend fun page(scope: DataScope, filter: PigFilter, offset: Int): PigPage {
                entered.complete(Unit); release.await(); return PigPage(listOf(pig(scope.farmId)), 1, offset, filter.pageSize)
            }
        }
        val repository = PigRepository(delayed, cache, { if (it != current) throw RequestContextChanged() })
        val task = async { try { repository.page(lease, PigFilter(), 0); false } catch (_: RequestContextChanged) { true } }
        entered.await(); current = ScopeLease(scope.copy(farmId = "farm-b"), 2); release.complete(Unit)
        assertTrue(task.await())
        assertNull(cache.read(scope, "page", PigFilter().cacheKey(0)))
        assertNull(cache.read(current.scope, "page", PigFilter().cacheKey(0)))
    }
    @Test fun corruptJsonAndAccountOwnershipAreRejected() = runBlocking {
        val repo = repository(); repo.detail(lease, "p1")
        cache.writableDatabase.execSQL("UPDATE snapshots SET payload=replace(payload, '[null,123.123456789]', 'invalid-json') WHERE kind='detail'")
        try { repo.detail(lease, "p1", true); fail("Malformed JSON accepted") } catch (_: CacheInvalid) { }
        val account = AccountContext(Identity("user-a", "name", null, "user", emptyList()), Farm("farm-a", "Farm"))
        cache.write(scope, "account", "current", StoredSnapshot(SnapshotCodec.account(account), 1))
        assertEquals("user-a", cache.readAccount("dev", "oauth-a", "farm-a")?.userId)
        assertNull(cache.readAccount("dev", "other", "farm-a"))
        cache.writableDatabase.execSQL("UPDATE snapshots SET user_id='other' WHERE kind='account'")
        val store = EncryptedSessionStore(context, name, name).apply { saveSession(Session("fake", "fake", 999999, "oauth-a", "farm-a")) }
        val gateway = object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("Unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("Unused")
            override suspend fun refresh(session: Session): Session = error("Unused")
            override fun logoutUrl(session: Session): String? = null
        }
        val auth = AuthRepository(store, gateway) { 0 }
        val farms = object : FarmRepository {
            override suspend fun choices(oauthId: String): List<FarmChoice> = error("Unused")
            override suspend fun choose(choice: FarmChoice): Unit = error("Unused")
            override suspend fun account(): AccountContext = throw ApiFailure.Network()
        }
        try { CachedFarmRepository(farms, cache, auth, "dev").account(); fail("Account ownership mismatch accepted") } catch (_: CacheInvalid) { }
    }

    @Test fun accountSnapshotKeepsValidatedFarmTimezoneAndAcceptsOlderSnapshots() {
        val account = AccountContext(Identity("user", "name", null, "user", emptyList()),
            Farm("farm-a", "Farm", "Asia/Shanghai"))
        val saved = SnapshotCodec.account(account)
        assertEquals("Asia/Shanghai", SnapshotCodec.account(saved).farm.timezone)
        val old = org.json.JSONObject(saved).apply { getJSONObject("account").remove("farmTimezone") }.toString()
        assertNull(SnapshotCodec.account(old).farm.timezone)
        val invalid = org.json.JSONObject(saved).apply {
            getJSONObject("account").put("farmTimezone", "invalid-zone")
        }.toString()
        assertNull(SnapshotCodec.account(invalid).farm.timezone)
    }
}
