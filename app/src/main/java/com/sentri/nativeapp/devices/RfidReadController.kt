package com.sentri.nativeapp.devices

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RfidTransport : AutoCloseable {
    fun start(onTag: (String) -> Unit, onFailure: () -> Unit, onDisconnected: () -> Unit)
    fun stop()
}
enum class RfidReadStatus { IDLE, READING, RESULT, FAILED, TIMEOUT, CANCELLED, DISCONNECTED, CLOSED }
data class RfidReadState(val status: RfidReadStatus = RfidReadStatus.IDLE, val epc: String? = null)

/** A single read owns its callback and deadline. Late SDK events cannot complete another read. */
class RfidReadController(private val transport: RfidTransport, private val scope: CoroutineScope) : AutoCloseable {
    private val mutable = MutableStateFlow(RfidReadState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var active = false
    private var closed = false
    private var timeout: Job? = null
    // Public lifecycle methods run on the owner's dispatcher. SDK callbacks are marshalled onto it.
    fun start(timeoutMillis: Long = 10_000) {
        require(timeoutMillis in 1..60_000); check(!closed)
        if (active) finish(generation, RfidReadStatus.CANCELLED)
        val request = ++generation; active = true
        mutable.value = RfidReadState(RfidReadStatus.READING)
        timeout = scope.launch { delay(timeoutMillis); finish(request, RfidReadStatus.TIMEOUT) }
        try {
            transport.start(
                { epc -> scope.launch { if (epc.isNotEmpty()) finish(request, RfidReadStatus.RESULT, epc) } },
                { scope.launch { finish(request, RfidReadStatus.FAILED) } },
                { scope.launch { finish(request, RfidReadStatus.DISCONNECTED) } },
            )
        } catch (_: Exception) { finish(request, RfidReadStatus.FAILED) }
    }
    private fun finish(request: Long, status: RfidReadStatus, epc: String? = null) {
        if (!active || generation != request || closed) return
        active = false; timeout?.cancel(); timeout = null
        try { transport.stop() } catch (_: Exception) { }
        mutable.value = RfidReadState(status, epc)
    }
    fun cancel() { finish(generation, RfidReadStatus.CANCELLED) }
    fun disconnected() { finish(generation, RfidReadStatus.DISCONNECTED) }
    override fun close() {
        if (closed) return
        cancel(); closed = true; generation++
        try { transport.close() } finally { mutable.value = RfidReadState(RfidReadStatus.CLOSED) }
    }
}
