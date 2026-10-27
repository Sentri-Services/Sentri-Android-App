package com.sentri.nativeapp.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sentri.nativeapp.R

@Composable
fun DeviceVerificationScreen(state: DeviceVerificationState, model: DeviceVerificationViewModel, onBack: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val focus = remember { FocusRequester() }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancel() }
    }
    BackHandler(onBack = onBack)
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(16.dp)
            .testTag("device-verification").onPreviewKeyEvent { model.dispatch(it.nativeKeyEvent) }
            .focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.device_validation), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.device_validation_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    Button(onClick = model::refresh) { Text(stringResource(R.string.refresh)) }
                    OutlinedButton(onClick = model::cancel) { Text(stringResource(R.string.cancel)) }
                }
                Text(stringResource(R.string.keyboard_scanner), style = MaterialTheme.typography.titleLarge)
                if (state.keyboards.isEmpty()) Text(stringResource(R.string.no_keyboard_scanner), Modifier.testTag("no-scanner-keyboard"))
                state.keyboards.forEach { device ->
                    Button(onClick = { model.startKeyboard(device.id); focus.requestFocus() }) { Text("${device.name} (${device.id})") }
                }
                Text(stringResource(R.string.device_state, keyboardStatus(state.keyboardStatus)))
                state.keyboardResult?.let { Text(stringResource(R.string.device_result, it)) }
                Text(stringResource(R.string.usb_rfid), style = MaterialTheme.typography.titleLarge)
                if (state.readers.isEmpty()) Text(stringResource(R.string.no_usb_reader), Modifier.testTag("no-usb-reader"))
                if (state.enumerationFailed) Text(stringResource(R.string.device_failure))
                state.readers.forEach { device ->
                    Button(onClick = { model.startRfid(device.id) }) { Text("USB ${device.id} (${device.vendorId}:${device.productId})") }
                }
                Text(stringResource(R.string.device_state, rfidStatus(state.rfid.status)))
                state.rfid.epc?.let { Text(stringResource(R.string.device_result, it)) }
            }
        }
    }
}
@Composable private fun keyboardStatus(status: KeyboardReadStatus) = stringResource(when (status) {
    KeyboardReadStatus.IDLE -> R.string.device_idle
    KeyboardReadStatus.READING -> R.string.device_reading
    KeyboardReadStatus.RESULT -> R.string.device_complete
    KeyboardReadStatus.TIMEOUT -> R.string.device_timeout
    KeyboardReadStatus.DISCONNECTED -> R.string.device_disconnected
    KeyboardReadStatus.CANCELLED -> R.string.device_cancelled
    KeyboardReadStatus.FAILED -> R.string.device_failure
})
@Composable private fun rfidStatus(status: RfidReadStatus) = stringResource(when (status) {
    RfidReadStatus.IDLE -> R.string.device_idle
    RfidReadStatus.READING -> R.string.device_reading
    RfidReadStatus.RESULT -> R.string.device_complete
    RfidReadStatus.TIMEOUT -> R.string.device_timeout
    RfidReadStatus.DISCONNECTED -> R.string.device_disconnected
    RfidReadStatus.CANCELLED, RfidReadStatus.CLOSED -> R.string.device_cancelled
    RfidReadStatus.FAILED -> R.string.device_failure
})
