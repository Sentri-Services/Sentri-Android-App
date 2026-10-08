package com.sentri.nativeapp

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sentri.nativeapp.ui.ConnectionScreen
import com.sentri.nativeapp.ui.ConnectionViewModel

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
            fun clearBrowserSession() {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                WebStorage.getInstance().deleteAllData()
            }
            MaterialTheme {
                ConnectionScreen(state, container.config, model::login, model::refresh, model::selectFarm,
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
