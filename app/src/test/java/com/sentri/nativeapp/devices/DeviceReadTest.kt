package com.sentri.nativeapp.devices

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceReadTest {
    @Test fun continuousScannerWaitsWhileIdleAndAcceptsRepeatedFrames() {
        val session = KeyboardScanSession(timeoutMillis = 10, continuous = true)
        session.start(7, 0)
        assertNull(session.input(7, "", true, true, 0, 1000))
        assertNull(session.input(7, "A", false, true, 0, 1001))
        assertEquals("A", session.input(7, "", true, true, 0, 1002))
        assertNull(session.input(7, "B", false, true, 0, 2000))
        assertEquals("B", session.input(7, "", true, true, 0, 2001))
        assertNull(session.input(8, "C", false, true, 0, 2002))
        session.cancel()
        assertNull(session.input(7, "D", false, true, 0, 2003))
    }
    @Test fun `keyboard accepts only selected device key down frame once and preserves content`() {
        val session = KeyboardScanSession()
        session.start(7, 0)
        assertNull(session.input(8, "other", true, true, 0, 1))
        assertNull(session.input(7, "repeat", false, true, 1, 1))
        assertNull(session.input(7, "up", true, false, 0, 1))
        assertNull(session.input(7, "Tag A", false, true, 0, 2))
        assertEquals("Tag A", session.input(7, "", true, true, 0, 3))
        assertNull(session.input(7, "", true, true, 0, 4))
    }
    @Test fun `keyboard timeout disconnect cancel and malformed frames do not produce identifiers`() {
        val session = KeyboardScanSession(timeoutMillis = 10, maxCharacters = 4)
        session.start(7, 0); assertNull(session.input(7, "late", true, true, 0, 10))
        session.start(7, 0); session.disconnected(7); assertNull(session.input(7, "gone", true, true, 0, 1))
        session.start(7, 0); assertNull(session.input(7, "large", true, true, 0, 1))
        session.start(7, 0); assertNull(session.input(7, "a\nb", true, true, 0, 1))
        session.start(7, 0); session.cancel(); assertNull(session.input(7, "gone", true, true, 0, 1))
    }
    private class Transport : RfidTransport {
        var tags = mutableListOf<(String) -> Unit>(); var failures = mutableListOf<() -> Unit>()
        var stops = 0; var closes = 0
        override fun start(onTag: (String) -> Unit, onFailure: () -> Unit, onDisconnected: () -> Unit) { tags += onTag; failures += onFailure }
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }
    @Test fun `rfid duplicate late result and old timeout cannot complete the new request`() = runTest {
        val transport = Transport(); val controller = RfidReadController(transport, backgroundScope)
        controller.start(100); runCurrent()
        transport.tags[0]("epc-a"); transport.tags[0]("duplicate"); runCurrent()
        assertEquals("epc-a", controller.state.value.epc); assertEquals(1, transport.stops)
        controller.start(200); runCurrent()
        transport.tags[0]("late"); advanceTimeBy(100); runCurrent()
        assertEquals(RfidReadStatus.READING, controller.state.value.status)
        transport.tags[1]("epc-b"); runCurrent()
        assertEquals("epc-b", controller.state.value.epc)
        controller.close(); transport.tags[1]("after-close"); runCurrent()
        assertEquals(RfidReadStatus.CLOSED, controller.state.value.status); assertEquals(1, transport.closes)
        controller.close(); assertEquals(1, transport.closes)
    }
    @Test fun `rfid timeout cancellation disconnect and failure stop inventory exactly once`() = runTest {
        val transport = Transport(); val controller = RfidReadController(transport, backgroundScope)
        controller.start(10); runCurrent(); advanceTimeBy(10); runCurrent()
        assertEquals(RfidReadStatus.TIMEOUT, controller.state.value.status)
        controller.start(); controller.cancel(); assertEquals(RfidReadStatus.CANCELLED, controller.state.value.status)
        controller.start(); controller.disconnected(); assertEquals(RfidReadStatus.DISCONNECTED, controller.state.value.status)
        controller.start(); transport.failures.last()(); runCurrent(); assertEquals(RfidReadStatus.FAILED, controller.state.value.status)
        assertEquals(4, transport.stops); controller.close()
    }
}
