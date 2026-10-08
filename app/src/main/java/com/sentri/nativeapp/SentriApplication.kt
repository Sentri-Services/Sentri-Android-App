package com.sentri.nativeapp

import android.app.Application

class SentriApplication : Application() {
    val container by lazy { AppContainer(this) }
}
