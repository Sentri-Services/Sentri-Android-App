package com.sentri.nativeapp.auth

import org.junit.Assert.*
import org.junit.Test

class CallbackPolicyTest {
    private val policy = CallbackPolicy("https://mobile_sentri_android/index.html")
    @Test fun `accepts existing synthetic callback with code and state`() {
        assertTrue(policy.matches("https://mobile_sentri_android/index.html?code=test&state=example"))
    }
    @Test fun `rejects lookalike hosts userinfo ports paths and schemes`() {
        listOf("https://evil_host/index.html", "https://mobile_sentri_android.evil.test/index.html",
            "https://evil.test/mobile_sentri_android/index.html", "https://user@mobile_sentri_android/index.html",
            "https://mobile_sentri_android:443/index.html", "http://mobile_sentri_android/index.html",
            "https://mobile_sentri_android/other", "https://mobile_sentri_android/index.html#fragment",
            "file:///index.html", "not a uri").forEach { assertFalse(it, policy.matches(it)) }
    }
}
