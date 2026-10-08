package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.update.AndroidApkInspector
import com.sentri.nativeapp.update.ApkUpdateRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateDownloadTest {
    @Test fun httpsManifestStreamsAndValidatesRealSignedApk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actual = AndroidApkInspector(context).current()
        // A prior installed version fixture; package and certificates are from the actual device APK.
        val previous = actual.copy(versionCode = actual.versionCode - 1)
        val bytes = File(context.applicationInfo.sourceDir).readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            val manifest = JSONObject().put("schemaVersion", 1).put("versionCode", actual.versionCode)
                .put("versionName", actual.versionName).put("applicationId", actual.applicationId)
                .put("buildType", "debug").put("apkUrl", "candidate.apk").put("fileSize", bytes.size)
                .put("sha256", digest).put("forceUpdate", true)
            server.enqueue(MockResponse().setBody(manifest.toString()))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val repo = ApkUpdateRepository(context, client, server.url("/debug/manifest.json")) { previous }
            val ready = requireNotNull(repo.check { })
            assertEquals(digest, ready.manifest.sha256)
            assertEquals(bytes.size.toLong(), ready.file.length())
            repo.validate(ready.file, ready.manifest)
            assertEquals("/debug/manifest.json", server.takeRequest().path)
            assertEquals("/debug/candidate.apk", server.takeRequest().path)
            assertFalse(File(ready.file.parentFile, "update.apk.part").exists())
            ready.file.delete()
            // Same metadata with a corrupted response must fail before installation.
            server.enqueue(MockResponse().setBody(manifest.toString()))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })))
            try { repo.check { }; fail("Corrupt update must fail") } catch (_: IllegalArgumentException) { }
            assertFalse(File(context.cacheDir, "apk-updates/update.apk.part").exists())
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
