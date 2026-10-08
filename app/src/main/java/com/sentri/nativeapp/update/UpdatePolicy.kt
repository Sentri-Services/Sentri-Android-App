package com.sentri.nativeapp.update

import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateManifest(val schemaVersion: Int, val versionCode: Long, val versionName: String,
    val applicationId: String, val buildType: String, val apkUrl: String, val fileSize: Long,
    val sha256: String, val forceUpdate: Boolean) {
    companion object {
        fun parse(text: String): UpdateManifest = JSONObject(text).let {
            UpdateManifest(it.getInt("schemaVersion"), it.getLong("versionCode"), it.getString("versionName"),
                it.getString("applicationId"), it.getString("buildType"), it.getString("apkUrl"),
                it.getLong("fileSize"), it.getString("sha256"), it.optBoolean("forceUpdate", false))
        }
    }
}

data class ApkIdentity(val applicationId: String, val versionCode: Long, val versionName: String,
    val certificates: Set<String>)

object UpdatePolicy {
    fun downloadUrl(manifest: UpdateManifest, manifestUrl: HttpUrl, current: ApkIdentity, buildType: String): HttpUrl? {
        require(manifest.schemaVersion == 1 && manifest.buildType == buildType)
        require(manifest.applicationId == current.applicationId)
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank())
        require(manifest.fileSize > 0 && manifest.sha256.matches(Regex("(?i)[0-9a-f]{64}")))
        require(manifest.apkUrl.isNotBlank())
        val url = requireNotNull(manifestUrl.resolve(manifest.apkUrl))
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        return url.takeIf { manifest.versionCode > current.versionCode }
    }

    fun validateFile(file: File, manifest: UpdateManifest) {
        require(file.length() == manifest.fileSize)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(manifest.sha256, ignoreCase = true))
    }

    fun validateIdentity(candidate: ApkIdentity, manifest: UpdateManifest, current: ApkIdentity) {
        require(candidate.applicationId == current.applicationId && candidate.applicationId == manifest.applicationId)
        require(candidate.versionCode == manifest.versionCode && candidate.versionCode > current.versionCode)
        require(candidate.versionName == manifest.versionName)
        require(current.certificates.isNotEmpty() && candidate.certificates == current.certificates)
    }
}
