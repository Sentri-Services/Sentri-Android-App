package com.sentri.nativeapp

import android.util.Log

/** Event/category only. Callers must never pass URLs, credentials or server response bodies. */
object DiagnosticLog {
    fun event(name: String) { Log.i("SentriIntegration", name) }
}
