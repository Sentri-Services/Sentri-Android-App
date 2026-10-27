package com.sentri.nativeapp.devices

/** External keyboard scanner frames end with Enter. A device must be selected explicitly. */
class KeyboardScanSession(private val timeoutMillis: Long = 10_000,
    private val maxCharacters: Int = 256, private val continuous: Boolean = false) {
    init { require(timeoutMillis > 0 && maxCharacters > 0) }
    private var device: Int? = null
    private var deadline = 0L
    private val buffer = StringBuilder()
    fun start(deviceId: Int, nowMillis: Long) {
        device = deviceId
        deadline = if (continuous) 0L else Math.addExact(nowMillis, timeoutMillis)
        buffer.clear()
    }
    fun cancel() { device = null; buffer.clear() }
    fun disconnected(deviceId: Int) { if (device == deviceId) cancel() }
    /** One-shot returns once; continuous mode accepts Enter-delimited frames until stopped. */
    fun input(deviceId: Int, text: String, enter: Boolean, keyDown: Boolean, repeatCount: Int, nowMillis: Long): String? {
        if (device == null || device != deviceId || !keyDown || repeatCount != 0) return null
        if (continuous && deadline == 0L) {
            if (enter || text.isEmpty()) return null
            deadline = Math.addExact(nowMillis, timeoutMillis)
        }
        if (nowMillis >= deadline) {
            if (!continuous) { cancel(); return null }
            buffer.clear()
            deadline = Math.addExact(nowMillis, timeoutMillis)
        }
        if (text.any { it.isISOControl() } || buffer.length + text.length > maxCharacters) {
            if (continuous) { buffer.clear(); deadline = 0L } else cancel()
            return null
        }
        buffer.append(text)
        if (!enter || buffer.isEmpty()) return null
        val value = buffer.toString()
        if (continuous) { buffer.clear(); deadline = 0L } else cancel()
        return value
    }
}
