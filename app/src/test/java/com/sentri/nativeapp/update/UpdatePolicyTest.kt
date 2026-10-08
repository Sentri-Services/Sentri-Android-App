package com.sentri.nativeapp.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdatePolicyTest {
    private val current = ApkIdentity("com.sentri.jingan.dev", 135, "1.3.5", setOf("existing-cert"))
    private val url = "https://farm-apk.sentri.cn/apks/dev/debug/manifest.json".toHttpUrl()
    private val manifest = UpdateManifest(1, 136, "1.3.6", current.applicationId, "debug", "Sentri_1.3.6_136_dev_debug.apk", 3, "0".repeat(64), true)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid update") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `relative manifest uses same channel and only higher version installs`() {
        assertEquals("https://farm-apk.sentri.cn/apks/dev/debug/Sentri_1.3.6_136_dev_debug.apk", UpdatePolicy.downloadUrl(manifest, url, current, "debug").toString())
        assertNull(UpdatePolicy.downloadUrl(manifest.copy(versionCode = 135), url, current, "debug"))
        assertNull(UpdatePolicy.downloadUrl(manifest.copy(versionCode = 134), url, current, "debug"))
    }
    @Test fun `reject wrong package build type schema and malformed digest`() {
        listOf(manifest.copy(applicationId = "com.sentri.jingan.demo"), manifest.copy(buildType = "release"),
            manifest.copy(schemaVersion = 2), manifest.copy(sha256 = "bad"), manifest.copy(fileSize = 0)).forEach {
            rejects { UpdatePolicy.downloadUrl(it, url, current, "debug") }
        }
    }
    @Test fun `reject plaintext and credential-bearing download URLs`() {
        for (bad in listOf("http://farm-apk.sentri.cn/app.apk", "https://user:secret@farm-apk.sentri.cn/app.apk")) {
            rejects { UpdatePolicy.downloadUrl(manifest.copy(apkUrl = bad), url, current, "debug") }
        }
    }
    @Test fun `download identity must match version and installed certificate`() {
        val good = current.copy(versionCode = 136, versionName = "1.3.6")
        UpdatePolicy.validateIdentity(good, manifest, current)
        listOf(good.copy(certificates = setOf("other-key")), good.copy(certificates = emptySet()),
            good.copy(applicationId = "other-app"), good.copy(versionCode = 137), good.copy(versionName = "wrong")).forEach {
            rejects { UpdatePolicy.validateIdentity(it, manifest, current) }
        }
    }
    @Test fun `length and SHA256 detect corrupt APK before package parsing`() {
        val file = File.createTempFile("sentri-update", ".apk")
        try {
            val bytes = byteArrayOf(1, 2, 3); file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            UpdatePolicy.validateFile(file, manifest.copy(sha256 = digest))
            rejects { UpdatePolicy.validateFile(file, manifest) }
            rejects { UpdatePolicy.validateFile(file, manifest.copy(fileSize = 4, sha256 = digest)) }
        } finally { file.delete() }
    }
}
