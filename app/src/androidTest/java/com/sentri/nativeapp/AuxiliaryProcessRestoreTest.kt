package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AuxiliaryProcessRestoreTest {
    @Test fun auxiliaryPagesAndAbsentDetailSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("auxiliaryStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "auxiliary-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = AuxiliaryFixture
        if (stage == "prepare") {
            context.deleteDatabase(name); check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteSnapshotCache(context, name).use { f.warm(ProductionAuxiliaryReads(f.remote(), it, {}) { 123 }) }
            println("AUXILIARY_PREPARED batchPage=1 batchDetails=2 recheckPage=1 preciseTime=true")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteSnapshotCache(context, name).use { cache ->
                    var fetches = 0
                    val reads = ProductionAuxiliaryReads(f.remote(ApiFailure.Network()) { fetches++ }, cache, {})
                    assertEquals(f.batches, reads.batches(f.lease, f.request, true).value)
                    assertEquals(f.rechecks, reads.rechecks(f.lease, f.recheckRequest, true).value)
                    assertEquals(f.batch, reads.batch(f.lease, "batch", true).value)
                    val absence = reads.batch(f.lease, "missing", true); assertNull(absence.value); assertEquals(123L, absence.fetchedAt)
                    try { reads.batch(f.lease, "never-read", true); fail() } catch (_: CacheMiss) { }
                    assertEquals(0, fetches)
                    try { reads.batches(ScopeLease(f.scope.copy(userId = "other"), 2), f.request, true); fail() } catch (_: CacheMiss) { }
                }
                println("AUXILIARY_RESTORED newProcess=true batchPage=1 batchDetails=2 recheckPage=1 remoteFetches=0 preciseTime=true bilingualNames=true cachedAbsence=true scopeIsolated=true")
            } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
        }
    }
}
