package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.EncryptedSessionStore
import com.sentri.nativeapp.auth.LoginRequest
import com.sentri.nativeapp.auth.Session
import org.junit.Assert.*
import org.junit.Test

class SessionStoreTest {
    private val namespace = "test-session-${java.util.UUID.randomUUID()}"
    @org.junit.After fun clean() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(namespace, 0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(namespace) }
    }
    @Test fun corruptedCiphertextClearsUnreadableSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(namespace, 0)
        preferences.edit().putString("session", "invalid ciphertext").commit()
        assertNull(EncryptedSessionStore(context, namespace, namespace).readSession())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun encryptedSessionSurvivesStoreRecreationAndIsClearedOnLogout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionStore(context, namespace, namespace)
        val session = Session("test-state", "test-secret-access", 99999999, "test-user", "test-farm")
        try {
            store.saveSession(session)
            store.savePendingLogin(LoginRequest("test-pkce", "https://auth.test/login"))
            val reopened = EncryptedSessionStore(context, namespace, namespace)
            assertEquals(session, reopened.readSession())
            assertEquals("test-pkce", reopened.readPendingLogin()?.serialized)
            val prefs = context.getSharedPreferences(namespace, 0)
            assertFalse(prefs.getString("session", "")!!.contains("test-secret-access"))
            assertFalse(prefs.getString("pending", "")!!.contains("test-pkce"))
            reopened.saveSession(null); reopened.savePendingLogin(null)
            assertNull(store.readSession()); assertNull(store.readPendingLogin())
        } finally { store.saveSession(null); store.savePendingLogin(null) }
    }
}
