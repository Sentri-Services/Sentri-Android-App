package com.sentri.nativeapp.devices

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent

data class ScannerKeyboard(val id: Int, val name: String)

class AndroidScannerInput(context: Context, private val onDevices: (List<ScannerKeyboard>) -> Unit,
                          private val onResult: (String) -> Unit, private val onDisconnect: () -> Unit,
                          private val continuous: Boolean = false) : AutoCloseable {
    private val manager = context.getSystemService(InputManager::class.java)
    private val frame = KeyboardScanSession(continuous = continuous)
    private var selected: Int? = null
    private var closed = false
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { refresh() }
        override fun onInputDeviceChanged(deviceId: Int) { refresh() }
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (selected == deviceId) { cancel(); onDisconnect() }
            refresh()
        }
    }
    init { manager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper())); refresh() }
    fun refresh() { if (!closed) onDevices(devices()) }
    fun devices(): List<ScannerKeyboard> = manager.inputDeviceIds.asSequence().mapNotNull { manager.getInputDevice(it) }
        .filter { !it.isVirtual && (Build.VERSION.SDK_INT < 29 || it.isExternal) && (it.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD }
        .map { ScannerKeyboard(it.id, it.name) }.toList()
    fun start(deviceId: Int) {
        check(!closed); require(devices().any { it.id == deviceId })
        selected = deviceId; frame.start(deviceId, SystemClock.elapsedRealtime())
    }
    fun cancel() { selected = null; frame.cancel() }
    fun dispatch(event: KeyEvent): Boolean {
        if (closed || selected == null || selected != event.deviceId || event.action != KeyEvent.ACTION_DOWN) return false
        val (text, enter) = scannerKeyFrame(event) ?: return false
        frame.input(event.deviceId, text, enter, true, event.repeatCount, SystemClock.elapsedRealtime())?.let {
            if (!continuous) selected = null
            onResult(it)
        }
        return true
    }
    override fun close() {
        if (closed) return
        cancel(); closed = true; manager.unregisterInputDeviceListener(listener)
    }
}

/** Decoding can be exercised with injected Android KeyEvents without claiming physical input. */
internal fun scannerKeyFrame(event: KeyEvent): Pair<String, Boolean>? {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
    val enter = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    val unicode = event.unicodeChar
    if (!enter && (unicode == 0 || (unicode and Int.MIN_VALUE) != 0)) return null
    return (if (enter) "" else String(Character.toChars(unicode))) to enter
}
