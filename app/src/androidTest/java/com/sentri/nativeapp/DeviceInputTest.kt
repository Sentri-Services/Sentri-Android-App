package com.sentri.nativeapp

import android.hardware.usb.UsbManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.devices.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DeviceInputTest {
    @Test fun injectedAndroidKeyEventsDecodeThroughTheKeyboardFrameContract() {
        val session = KeyboardScanSession()
        val simulatedDevice = -1
        session.start(simulatedDevice, 0)
        val key = KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_SHIFT_ON, simulatedDevice, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val frame = requireNotNull(scannerKeyFrame(key))
        assertEquals("A", frame.first)
        assertNull(session.input(simulatedDevice, frame.first, frame.second, true, 0, 1))
        val enter = KeyEvent(0, 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, simulatedDevice, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val ending = requireNotNull(scannerKeyFrame(enter))
        assertEquals("A", session.input(simulatedDevice, ending.first, ending.second, true, 0, 2))
        assertNull(scannerKeyFrame(KeyEvent.changeAction(enter, KeyEvent.ACTION_UP)))
        assertNull(scannerKeyFrame(KeyEvent.changeTimeRepeat(key, 3, 1)))
    }
    @Test fun readerSdkEnumeratesAndMissingDeviceFailsWithoutPermissionOrInventory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.getSystemService(UsbManager::class.java).deviceList.isEmpty())
        assertTrue(AndroidRfidTransport.devices(context).isEmpty())
        // Ensure the actual bundled SDK's entry point links on the target Android runtime.
        com.gg.reader.api.dal.GClient().apply { setPrint(false); close() }
        val failed = CompletableDeferred<Unit>()
        val transport = AndroidRfidTransport(context, Int.MAX_VALUE)
        transport.start({ fail("Missing USB device produced EPC") }, { failed.complete(Unit) }, { fail("Unexpected disconnect") })
        withTimeout(5_000) { failed.await() }
        transport.close(); withTimeout(5_000) { transport.awaitClosed() }
    }
}
