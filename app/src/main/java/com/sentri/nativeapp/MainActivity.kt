package com.sentri.nativeapp

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sentri.nativeapp.ui.ConnectionScreen
import com.sentri.nativeapp.ui.ConnectionViewModel
import com.sentri.nativeapp.update.ApkUpdateViewModel
import com.sentri.nativeapp.update.ApkUpdateScreen
import com.sentri.nativeapp.update.UpdatePhase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SentriApplication).container
        setContent {
            val model: ConnectionViewModel = viewModel(factory = viewModelFactory {
                initializer { ConnectionViewModel(container.auth, container.farms) }
            })
            val state by model.state.collectAsStateWithLifecycle()
            val updateModel: ApkUpdateViewModel = viewModel(factory = viewModelFactory {
                initializer { ApkUpdateViewModel(container.updates) }
            })
            val updateState by updateModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(updateState.phase) {
                // Match the old startup policy: an unavailable check does not block app access.
                if (updateState.phase == UpdatePhase.FAILED) {
                    Toast.makeText(this@MainActivity, R.string.update_failed, Toast.LENGTH_LONG).show()
                    updateModel.proceed()
                }
            }
            fun clearBrowserSession() {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                val issuer = Uri.parse(container.config.authIssuer)
                // The upgrade shares the old sandbox; preserve legacy business/offline origins.
                WebStorage.getInstance().deleteOrigin("${issuer.scheme}://${issuer.encodedAuthority}")
            }
            MaterialTheme {
                if (updateState.phase != UpdatePhase.IDLE) ApkUpdateScreen(updateState, updateModel)
                else ConnectionScreen(state, container.config, model::login, model::refresh, model::selectFarm,
                    model::switchFarm, model::signOut,
                    onCallback = { url ->
                        if (state.loggingOut) clearBrowserSession()
                        model.callback(url)
                    },
                    onCloseWeb = {
                        if (state.loggingOut) clearBrowserSession()
                        model.closeWeb()
                    },
                    onWebFailure = {
                        if (state.loggingOut) clearBrowserSession()
                        model.webFailure()
                    })
            }
        }
    }
}
