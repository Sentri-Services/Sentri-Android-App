package com.sentri.nativeapp.devices

import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class KeyboardReadStatus { IDLE, READING, RESULT, TIMEOUT, DISCONNECTED, CANCELLED, FAILED }
data class DeviceVerificationState(
    val keyboards: List<ScannerKeyboard> = emptyList(), val readers: List<UsbReaderDevice> = emptyList(),
    val keyboardStatus: KeyboardReadStatus = KeyboardReadStatus.IDLE, val keyboardResult: String? = null,
    val rfid: RfidReadState = RfidReadState(), val enumerationFailed: Boolean = false,
)
class DeviceVerificationViewModel(context: Context) : ViewModel() {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow(DeviceVerificationState())
    val state = mutable.asStateFlow()
    private var keyboardDeadline: Job? = null
    private var rfid: RfidReadController? = null
    private var readerCollection: Job? = null
    private val keyboard = AndroidScannerInput(this.context,
        { devices -> mutable.update { it.copy(keyboards = devices) } },
        { result -> keyboardDeadline?.cancel(); mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.RESULT, keyboardResult = result) } },
        { keyboardDeadline?.cancel(); mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.DISCONNECTED, keyboardResult = null) } })
    init { refresh() }
    fun refresh() {
        keyboard.refresh()
        viewModelScope.launch {
            try {
                val readers = withContext(Dispatchers.IO) { AndroidRfidTransport.devices(context) }
                mutable.update { it.copy(readers = readers, enumerationFailed = false) }
            } catch (_: Exception) { mutable.update { it.copy(readers = emptyList(), enumerationFailed = true) } }
        }
    }
    fun startKeyboard(id: Int) {
        keyboardDeadline?.cancel()
        try { keyboard.start(id) } catch (_: Exception) {
            mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.FAILED, keyboardResult = null) }; return
        }
        mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.READING, keyboardResult = null) }
        keyboardDeadline = viewModelScope.launch {
            delay(10_000); keyboard.cancel(); mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.TIMEOUT, keyboardResult = null) }
        }
    }
    fun dispatch(event: KeyEvent) = keyboard.dispatch(event)
    fun startRfid(id: Int) {
        readerCollection?.cancel(); rfid?.close()
        val controller = RfidReadController(AndroidRfidTransport(context, id), viewModelScope)
        rfid = controller
        readerCollection = viewModelScope.launch { controller.state.collect { state -> mutable.update { it.copy(rfid = state) } } }
        controller.start()
    }
    fun cancel() {
        keyboardDeadline?.cancel(); keyboard.cancel()
        readerCollection?.cancel(); readerCollection = null
        rfid?.close(); rfid = null
        mutable.update { it.copy(keyboardStatus = KeyboardReadStatus.CANCELLED, keyboardResult = null, rfid = RfidReadState(RfidReadStatus.CANCELLED)) }
    }
    override fun onCleared() { keyboardDeadline?.cancel(); keyboard.close(); readerCollection?.cancel(); rfid?.close() }
}
