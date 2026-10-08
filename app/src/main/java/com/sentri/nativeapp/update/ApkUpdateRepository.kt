package com.sentri.nativeapp.update

import android.content.Context
import com.sentri.nativeapp.BuildConfig
import com.sentri.nativeapp.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ReadyUpdate(val manifest: UpdateManifest, val file: File)
interface ApkUpdates {
    suspend fun check(progress: (Int) -> Unit): ReadyUpdate?
    fun validate(file: File, manifest: UpdateManifest)
}

class ApkUpdateRepository(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val manifestUrl: okhttp3.HttpUrl = "${BuildConfig.APK_UPDATE_BASE_URL}/${BuildConfig.BUILD_TYPE}/manifest.json".toHttpUrl(),
    private val installedIdentity: () -> ApkIdentity = { AndroidApkInspector(context.applicationContext).current() },
) : ApkUpdates {
    private val inspector = AndroidApkInspector(context.applicationContext)
    private val directory = File(context.cacheDir, "apk-updates")
    companion object {
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build()
    }

    override suspend fun check(progress: (Int) -> Unit): ReadyUpdate? = withContext(Dispatchers.IO) {
        require(manifestUrl.isHttps)
        val current = installedIdentity()
        val manifest = client.newCall(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()).execute().use {
            check(it.isSuccessful)
            val body = requireNotNull(it.body)
            // Bound the pointer response; APK content is streamed separately.
            val source = body.source()
            check(!source.request(64 * 1024 + 1L))
            UpdateManifest.parse(source.readUtf8())
        }
        val downloadUrl = UpdatePolicy.downloadUrl(manifest, manifestUrl, current, BuildConfig.BUILD_TYPE)
            ?: return@withContext null
        directory.mkdirs()
        val part = File(directory, "update.apk.part")
        val final = File(directory, "update.apk")
        part.delete()
        try {
            client.newCall(Request.Builder().url(downloadUrl).build()).execute().use {
                check(it.isSuccessful)
                val body = requireNotNull(it.body)
                body.byteStream().use { source ->
                    part.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= manifest.fileSize)
                            target.write(buffer, 0, count)
                            progress((copied * 100 / manifest.fileSize).toInt())
                        }
                    }
                }
            }
            validate(part, manifest)
            final.delete()
            check(part.renameTo(final))
            DiagnosticLog.event("apk_update_validated")
            ReadyUpdate(manifest, final)
        } finally { part.delete() }
    }

    override fun validate(file: File, manifest: UpdateManifest) {
        UpdatePolicy.validateFile(file, manifest)
        UpdatePolicy.validateIdentity(inspector.archive(file), manifest, installedIdentity())
    }
}
