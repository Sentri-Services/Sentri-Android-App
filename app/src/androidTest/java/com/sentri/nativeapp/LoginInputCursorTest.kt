package com.sentri.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sentri.nativeapp.auth.LogtoAuthGateway
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.ui.LoginWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in login input probes. No credentials, submission, exchange, logout or session-store change. */
class LoginInputCursorTest {
    @Test fun composeHostPreservesCaretDuringImeCommits() = probe(composeHost = true, hardware = false)
    @Test fun nativeHostPreservesCaretDuringImeCommits() = probe(composeHost = false, hardware = false)
    @Test fun composeHostPreservesCaretDuringHardwareKeys() = probe(composeHost = true, hardware = true)

    @Test fun localComposeHostPreservesCaretWithoutPageScripts() = probe(composeHost = true, hardware = false, local = true)
    @Test fun localNativeHostPreservesCaretWithoutPageScripts() = probe(composeHost = false, hardware = false, local = true)

    private fun probe(composeHost: Boolean, hardware: Boolean, local: Boolean = false) = runBlocking {
        assumeTrue(local || InstrumentationRegistry.getArguments().getString("liveLoginInput") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = AppConfig.current()
        assertEquals("dev", config.environment)
        // Deliberately bypass AuthRepository: preserve real session and pending-login storage.
        val requestUrl = if (local) "about:blank" else withTimeout(20_000) { LogtoAuthGateway(context, config).beginLogin(forcePrompt = true).url }
        // Raw WebView probes do not need Compose's ActivityScenarioRule. On the test
        // Xiaomi, Instrumentation.startActivitySync waits indefinitely before the test
        // body. Launch the exported debug test host through the standard shell command.
        withContext(Dispatchers.IO) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "am start -W --activity-clear-task -n ${context.packageName}/androidx.activity.ComponentActivity")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val activity = withTimeout(10_000) {
            var host: ComponentActivity? = null
            while (host == null) {
                host = withContext(Dispatchers.Main) {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ComponentActivity>().firstOrNull()
                }
                if (host == null) delay(100)
            }
            host
        }
        var native: WebView? = null
        if (composeHost) withContext(Dispatchers.Main) { activity.setContent {
            LoginWebView(requestUrl, config, {}, {}, {}, Modifier.fillMaxSize())
        } } else withContext(Dispatchers.Main) {
            native = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                activity.setContentView(this)
                loadUrl(requestUrl)
            }
        }
        val web = withTimeout(20_000) {
            var found: WebView? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { native ?: findWeb(resumedActivity().window.decorView) }
                if (found == null) delay(100)
            }
            found
        }
        try {
            if (local) withContext(Dispatchers.Main) {
                web.loadDataWithBaseURL(config.authIssuer,
                    "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body><input id='probe' type='text' style='font-size:24px;margin:40px 0' autofocus></body></html>",
                    "text/html", "UTF-8", null)
            }
            val selector = "Array.from(document.querySelectorAll('input')).find(e=>e.type!=='hidden'&&e.type!=='password'&&!e.disabled&&!e.readOnly&&e.getBoundingClientRect().width>0)"
            val metadata = withTimeout(25_000) {
                var meta: JSONObject? = null
                while (meta == null) {
                    val value = evaluate(web, "(()=>{const e=$selector;return e?JSON.stringify({type:e.type,inputMode:e.inputMode,maxLength:e.maxLength}):'';})()")
                    if (value.isNotEmpty()) meta = JSONObject(value) else delay(150)
                }
                meta
            }
            println("LOGIN_INPUT_PROBE host=${if (composeHost) "compose" else "native"} " +
                "mode=${if (hardware) "keys" else "commit"} local=$local type=${metadata.getString("type")}")
            evaluate(web, "(()=>{const e=$selector;window.__sentriProbe=e;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,'');e.dispatchEvent(new Event('input',{bubbles:true}));e.focus();return '';})()")
            withContext(Dispatchers.Main) { web.requestFocus() }
            delay(150)
            for (index in 1..5) {
                if (hardware) {
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0 + index))
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0 + index))
                } else withContext(Dispatchers.Main) {
                    val connection = requireNotNull(web.onCreateInputConnection(EditorInfo()))
                    assertTrue("IME commit was rejected", connection.commitText(index.toString(), 1))
                }
                delay(150)
                val snapshot = JSONObject(evaluate(web, "(()=>{const e=window.__sentriProbe;return JSON.stringify({length:e.value.length,start:e.selectionStart,end:e.selectionEnd,matches:e.value.replace(/\\s/g,'')==='${(1..index).joinToString("")}'});})()"))
                assertTrue("Input order changed at character $index (host=$composeHost, hardware=$hardware)", snapshot.getBoolean("matches"))
                assertEquals("Caret moved away from end at character $index (host=$composeHost, hardware=$hardware)",
                    snapshot.getInt("length"), snapshot.getInt("start"))
                assertEquals(snapshot.getInt("start"), snapshot.getInt("end"))
            }
            // Preserve intentional caret movement too: a force-to-end script would fail this.
            evaluate(web, "(()=>{window.__sentriProbe.setSelectionRange(2,2);return '';})()")
            delay(150)
            if (hardware) {
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_9))
            } else withContext(Dispatchers.Main) {
                requireNotNull(web.onCreateInputConnection(EditorInfo())).commitText("9", 1)
            }
            delay(150)
            var middle = JSONObject(evaluate(web, "JSON.stringify({matches:window.__sentriProbe.value.replace(/\\s/g,'')==='129345',start:window.__sentriProbe.selectionStart})"))
            assertTrue("Middle insertion changed the wrong position", middle.getBoolean("matches"))
            assertEquals(3, middle.getInt("start"))
            if (hardware) {
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } else withContext(Dispatchers.Main) {
                requireNotNull(web.onCreateInputConnection(EditorInfo())).deleteSurroundingText(1, 0)
            }
            delay(150)
            middle = JSONObject(evaluate(web, "JSON.stringify({matches:window.__sentriProbe.value.replace(/\\s/g,'')==='12345',start:window.__sentriProbe.selectionStart})"))
            assertTrue("Deletion changed the wrong position", middle.getBoolean("matches"))
            assertEquals(2, middle.getInt("start"))
            if (!hardware) {
                withContext(Dispatchers.Main) {
                    requireNotNull(web.onCreateInputConnection(EditorInfo())).apply {
                        setComposingText("67", 1)
                        finishComposingText()
                    }
                }
                delay(150)
                middle = JSONObject(evaluate(web, "JSON.stringify({matches:window.__sentriProbe.value.replace(/\\s/g,'')==='1267345',start:window.__sentriProbe.selectionStart})"))
                assertTrue("Composing text changed the wrong position", middle.getBoolean("matches"))
                assertEquals(4, middle.getInt("start"))
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (!composeHost) { web.stopLoading(); web.destroy() }
                activity.finish()
            }
        }
    }

    private suspend fun evaluate(web: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { web.evaluateJavascript(script) { raw ->
            result.complete(if (raw == "null") "" else JSONArray("[$raw]").getString(0))
        } }
        return withTimeout(5_000) { result.await() }
    }
    private fun resumedActivity(): Activity = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).first()
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
}
