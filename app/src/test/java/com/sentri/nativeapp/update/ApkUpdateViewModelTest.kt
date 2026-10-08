package com.sentri.nativeapp.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ApkUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun ready(force: Boolean) = ReadyUpdate(UpdateManifest(1, 136, "1.3.6", "com.sentri.jingan.dev", "debug", "app.apk", 1, "0".repeat(64), force), File("test.apk"))
    private class FakeUpdates(var ready: ReadyUpdate? = null) : ApkUpdates {
        var calls = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun check(progress: (Int) -> Unit): ReadyUpdate? {
            calls++; gate?.await(); if (fail) throw IllegalStateException(); progress(100); return ready
        }
        override fun validate(file: File, manifest: UpdateManifest) = Unit
    }
    @Test fun `local debug does not access update channel`() = runTest(dispatcher) {
        val fake = FakeUpdates(); val model = ApkUpdateViewModel(fake, false)
        advanceUntilIdle(); assertEquals(0, fake.calls); assertEquals(UpdatePhase.IDLE, model.state.value.phase)
    }
    @Test fun `force update cannot be skipped while optional update can`() = runTest(dispatcher) {
        val forced = ApkUpdateViewModel(FakeUpdates(ready(true)), true)
        advanceUntilIdle(); forced.proceed(); assertEquals(UpdatePhase.READY, forced.state.value.phase)
        val optional = ApkUpdateViewModel(FakeUpdates(ready(false)), true)
        advanceUntilIdle(); optional.proceed(); assertEquals(UpdatePhase.IDLE, optional.state.value.phase)
    }
    @Test fun `failed check permits current app and retry`() = runTest(dispatcher) {
        val fake = FakeUpdates().apply { fail = true }; val model = ApkUpdateViewModel(fake, true)
        advanceUntilIdle(); assertEquals(UpdatePhase.FAILED, model.state.value.phase)
        fake.fail = false; model.check(); advanceUntilIdle(); assertEquals(UpdatePhase.IDLE, model.state.value.phase)
        assertEquals(2, fake.calls)
    }
    @Test fun `repeated checks share one active download`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>();val fake = FakeUpdates(ready(true)).apply { this.gate = gate }
        val model = ApkUpdateViewModel(fake, true); model.check(); runCurrent(); assertEquals(1, fake.calls)
        gate.complete(Unit); advanceUntilIdle(); assertEquals(UpdatePhase.READY, model.state.value.phase)
    }
}
