package com.sentri.nativeapp.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sentri.nativeapp.auth.CallbackPolicy
import com.sentri.nativeapp.config.AppConfig

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(url: String, config: AppConfig, onCallback: (String) -> Unit, onCancel: () -> Unit,
    onFailure: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val policy = remember(config.redirectUri) { CallbackPolicy(config.redirectUri) }
    val allowedHost = remember(config.authIssuer) { Uri.parse(config.authIssuer).host }
    val webView = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                private var completed = false
                private fun handle(target: String): Boolean {
                    if (policy.matches(target)) {
                        if (!completed) { completed = true; onCallback(target) }
                        return true
                    }
                    val uri = Uri.parse(target)
                    if (uri.scheme != "https" || uri.host != allowedHost) {
                        if (!completed) { completed = true; onFailure() }
                        return true
                    }
                    return false
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && handle(request.url.toString())
                @Deprecated("Required on older WebView implementations")
                override fun shouldOverrideUrlLoading(view: WebView, target: String): Boolean = handle(target)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !completed) { completed = true; onFailure() }
                }
            }
            loadUrl(url)
        }
    }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onCancel() }
    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    AndroidView(factory = { hostContext ->
        // Keep the webpage's editable focus inside a native view group rather than making
        // WebView itself the Compose interop focus target.
        FrameLayout(hostContext).apply {
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }, modifier = modifier)
}
