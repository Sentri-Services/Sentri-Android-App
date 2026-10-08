package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.sentri.nativeapp.update.AndroidApkInspector
import com.sentri.nativeapp.update.UpdateManifest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateContractTest {
    @Test fun parsesExistingManifestContract() {
        val manifest = UpdateManifest.parse("""{"schemaVersion":1,"versionCode":136,"versionName":"1.3.6","applicationId":"com.sentri.jingan.dev","buildType":"debug","apkUrl":"Sentri_1.3.6_136_dev_debug.apk","fileSize":100,"sha256":"${"a".repeat(64)}","forceUpdate":true}""")
        assertEquals(136L, manifest.versionCode); assertTrue(manifest.forceUpdate)
        assertEquals("com.sentri.jingan.dev", manifest.applicationId)
    }
    @Test fun currentAndArchiveCertificatesAreReadFromActualApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inspector = AndroidApkInspector(context)
        val current = inspector.current()
        val archive = inspector.archive(File(context.applicationInfo.sourceDir))
        assertEquals(current, archive); assertTrue(current.certificates.isNotEmpty())
    }
    @Test fun installationProviderExposesOnlyUpdateCacheDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "apk-updates/update.apk")
        assertEquals("content", FileProvider.getUriForFile(context, "${context.packageName}.ImageFileProvider", file).scheme)
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.ImageFileProvider", File(context.filesDir, "private-session"))
            fail("Private files must not be shared")
        } catch (_: IllegalArgumentException) { }
    }
}
