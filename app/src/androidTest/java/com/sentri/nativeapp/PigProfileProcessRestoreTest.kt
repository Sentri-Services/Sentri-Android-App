package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PigProfileProcessRestoreTest {
    @Test fun fullProfilesAndMissingDetailSurviveProcessStopWithoutRemoteReads() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("pigProfileStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pig-profile-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = PigProfileFixture
        if (stage == "prepare") {
            context.deleteDatabase(name); check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, name).use { cache -> f.warm(PigProfileReads(f.remote(), cache, {}) { 123 }) }
            println("PIG_PROFILE_PREPARED pid=${Process.myPid()} snapshots=3")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                var calls = 0
                SqliteSnapshotCache(context, name).use { cache ->
                    val reads = PigProfileReads(f.remote(called = { calls++ }), cache, {})
                    val detail = reads.detail(f.lease, "pig", true)
                    assertEquals(f.pig, detail.value); assertEquals(123L, detail.fetchedAt); assertEquals(DataSource.CACHE, detail.source)
                    assertEquals(PigProfilePage(f.request, listOf(f.pig), 1), reads.page(f.lease, f.request, true).value)
                    assertNull(reads.detail(f.lease, "missing", true).value)
                    try { reads.detail(ScopeLease(f.scope.copy(userId = "other"), 2), "pig", true); fail() } catch (_: CacheMiss) { }
                }
                assertEquals(0, calls)
                println("PIG_PROFILE_RESTORED pid=${Process.myPid()} newProcess=true snapshots=3 remoteReads=0 decimalExact=true rawJsonExact=true bilingualNames=true missingDetail=true scopeIsolated=true")
            } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
        }
    }
}
