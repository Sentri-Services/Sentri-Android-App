package com.sentri.nativeapp.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sentri.nativeapp.R
import kotlinx.coroutines.launch

@Composable
fun ApkUpdateScreen(state: UpdateState, model: ApkUpdateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun launchSafely(action: () -> Unit) {
        try { action() }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.update_install_unavailable, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(context, R.string.update_install_unavailable, Toast.LENGTH_LONG).show() }
    }
    fun install() {
        scope.launch {
            val ready = model.validatedInstallFile() ?: return@launch
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.ImageFileProvider", ready.file)
            launchSafely {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) install()
    }
    BackHandler(enabled = state.ready?.manifest?.forceUpdate == true) { }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_update), style = MaterialTheme.typography.headlineSmall)
            when (state.phase) {
                UpdatePhase.CHECKING -> { Text(stringResource(R.string.update_checking)); CircularProgressIndicator() }
                UpdatePhase.DOWNLOADING -> { Text(stringResource(R.string.update_downloading, state.progress)); CircularProgressIndicator() }
                UpdatePhase.READY -> {
                    Text(stringResource(R.string.update_ready, state.ready!!.manifest.versionName))
                    if (state.ready.manifest.forceUpdate) Text(stringResource(R.string.update_required))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            launchSafely { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                        } else install()
                    }) { Text(stringResource(R.string.update_install)) }
                    if (!state.ready.manifest.forceUpdate) OutlinedButton(onClick = model::proceed) { Text(stringResource(R.string.update_skip)) }
                }
                UpdatePhase.FAILED -> {
                    Text(stringResource(R.string.update_failed))
                    Button(onClick = model::check) { Text(stringResource(R.string.refresh)) }
                    OutlinedButton(onClick = model::proceed) { Text(stringResource(R.string.update_continue)) }
                }
                UpdatePhase.IDLE -> Unit
            }
        }
    }
}
