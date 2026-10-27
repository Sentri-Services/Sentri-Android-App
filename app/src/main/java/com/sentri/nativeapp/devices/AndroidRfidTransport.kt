package com.sentri.nativeapp.devices

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.gg.reader.api.dal.GClient
import com.gg.reader.api.dal.communication.AndroidUsbHidClient
import com.gg.reader.api.dal.communication.OnUsbHidDeviceListener
import com.gg.reader.api.protocol.gx.EnumG
import com.gg.reader.api.protocol.gx.MsgBaseInventoryEpc
import com.gg.reader.api.protocol.gx.MsgBaseStop
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class UsbReaderDevice(val id: Int, val vendorId: Int, val productId: Int)

/** Serializes blocking vendor calls off the UI thread; Android owns permission and detach handling. */
class AndroidRfidTransport(context: Context, private val deviceId: Int) : RfidTransport {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsbManager::class.java)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val revision = AtomicLong()
    private var job: Job? = null
    private var connection: Connection? = null
    @Volatile private var closed = false
    private data class Connection(val client: GClient, val usb: AndroidUsbHidClient, val receiver: BroadcastReceiver)

    companion object {
        fun devices(context: Context): List<UsbReaderDevice> = AndroidUsbHidClient.getUsbHidList(context.applicationContext).orEmpty()
            .map { it.usbDevice }.distinctBy { it.deviceId }.map { UsbReaderDevice(it.deviceId, it.vendorId, it.productId) }
    }

    override fun start(onTag: (String) -> Unit, onFailure: () -> Unit, onDisconnected: () -> Unit) {
        check(!closed)
        val current = revision.incrementAndGet(); job?.cancel()
        job = worker.launch {
            try {
                mutex.withLock {
                    cleanup()
                    val usb = AndroidUsbHidClient.getUsbHidList(context).orEmpty().firstOrNull { it.usbDevice.deviceId == deviceId }
                        ?: run { if (revision.get() == current) onFailure(); return@withLock }
                    val device = usb.usbDevice
                    if (!permission(device) || manager.deviceList.values.none { it.deviceId == deviceId } || !manager.hasPermission(device)) {
                        if (revision.get() == current) onFailure(); return@withLock
                    }
                    ensureActive()
                    if (revision.get() != current) return@withLock
                    val client = GClient().apply { setPrint(false) }
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val detached = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) }
                            if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED && detached?.deviceId == deviceId && revision.get() == current) onDisconnected()
                        }
                    }
                    ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
                    connection = Connection(client, usb, receiver)
                    client.onTagEpcLog = com.gg.reader.api.dal.HandlerTagEpcLog { _, row ->
                        if (revision.get() == current && row != null && row.result == 0) row.epc?.let(onTag)
                    }
                    client.onDisconnected = com.gg.reader.api.dal.HandlerTcpDisconnected {
                        if (revision.get() == current) onDisconnected()
                    }
                    usb.deviceListener = object : OnUsbHidDeviceListener {
                        override fun onDeviceConnected(usb: AndroidUsbHidClient) {
                            worker.launch {
                                mutex.withLock {
                                    if (revision.get() != current || closed) return@withLock
                                    try {
                                        val scan = MsgBaseInventoryEpc().apply {
                                            antennaEnable = EnumG.AntennaNo_1
                                            inventoryMode = EnumG.InventoryMode_Single
                                        }
                                        client.sendSynMsg(scan, 1000)
                                        if (scan.rtCode.toInt() != 0 && revision.get() == current) onFailure()
                                    } catch (_: Exception) { if (revision.get() == current) onFailure() }
                                }
                            }
                        }
                        override fun onDeviceConnectFailed(usb: AndroidUsbHidClient) { if (revision.get() == current) onFailure() }
                    }
                    // The SDK's legacy permission path uses flags=0. Open only after Android authorization.
                    if (!client.openAndroidUsbHid(usb) && revision.get() == current) onFailure()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (revision.get() == current && !closed) onFailure() }
        }
    }

    private suspend fun permission(device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        var receiver: BroadcastReceiver? = null
        var pending: PendingIntent? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
                val callback = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == action && continuation.isActive) continuation.resume(manager.hasPermission(device))
                    }
                }
                receiver = callback
                ContextCompat.registerReceiver(context, callback, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                val request = PendingIntent.getBroadcast(context, deviceId, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                pending = request
                manager.requestPermission(device, request)
            }
        } finally {
            pending?.cancel()
            receiver?.let(::unregister)
        }
    }

    private fun unregister(receiver: BroadcastReceiver) { try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { } }
    private fun cleanup() {
        val existing = connection ?: return; connection = null
        existing.client.onTagEpcLog = null; existing.client.onDisconnected = null; existing.usb.deviceListener = null
        try { if (existing.usb.isConnected) existing.client.sendSynMsg(MsgBaseStop(), 1000) } catch (_: Exception) { }
        try { existing.client.close() } catch (_: Exception) { }
        try { existing.usb.dispose() } catch (_: Exception) { }
        unregister(existing.receiver)
    }
    override fun stop() {
        revision.incrementAndGet(); job?.cancel(); job = null
        worker.launch { mutex.withLock { cleanup() } }
    }
    override fun close() {
        if (closed) return
        closed = true; revision.incrementAndGet(); job?.cancel()
        worker.launch { try { mutex.withLock { cleanup() } } finally { worker.cancel() } }
    }
    internal suspend fun awaitClosed() { worker.coroutineContext[Job]?.join() }
}
