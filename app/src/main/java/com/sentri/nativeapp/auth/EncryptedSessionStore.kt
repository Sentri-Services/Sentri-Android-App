package com.sentri.nativeapp.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private preferences contain only AES-GCM ciphertext; keys stay in Android Keystore. */
class EncryptedSessionStore(context: Context, preferencesName: String = "sentri_session", private val alias: String = "sentri.session.v1") : SessionStore {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun readSession(): Session? = decode("session") {
        Session(it.getString("authState"), it.getString("accessToken"), it.getLong("expiresAt"),
            it.getString("oauthId"), it.optString("farmId").takeIf(String::isNotBlank))
    }

    override fun saveSession(session: Session?) = write("session", session?.let {
        JSONObject().put("authState", it.authState).put("accessToken", it.accessToken)
            .put("expiresAt", it.expiresAtMillis).put("oauthId", it.oauthId).put("farmId", it.farmId ?: "")
    })

    override fun readPendingLogin(): LoginRequest? = decode("pending") {
        LoginRequest(it.getString("serialized"), it.getString("url"))
    }
    override fun savePendingLogin(request: LoginRequest?) = write("pending", request?.let {
        JSONObject().put("serialized", it.serialized).put("url", it.url)
    })

    @Synchronized private fun <T> decode(name: String, reader: (JSONObject) -> T): T? = try {
        read(name)?.let(reader)
    } catch (_: Exception) {
        check(preferences.edit().clear().commit())
        null
    }

    @Synchronized private fun read(name: String): JSONObject? {
        val encoded = preferences.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            // Unreadable or restored ciphertext must never masquerade as a valid session.
            check(preferences.edit().clear().commit())
            null
        }
    }

    @Synchronized private fun write(name: String, json: JSONObject?) {
        val editor = preferences.edit()
        if (json == null) editor.remove(name) else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            editor.putString(name, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Could not persist authentication state" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
