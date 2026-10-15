package icu.idoit.apkassistant

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Optional device tests; never run by desktop unit tests. */
class ApkRepositoryDeviceTest {
    @Test fun androidParserRecognizesRealSignedApkWithAnyName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ApkRepository(context)
        val folder = File(context.cacheDir, "packages").apply { mkdirs() }
        listOf("应用.apk.1", "应用.apk.xx.xx", "应用.bin", "无后缀").forEach { name ->
            val file = File(folder, name)
            try {
                File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
                val result = repository.inspect(file, name)
                assertEquals(context.packageName, result.packageName)
                assertEquals(name, result.originalName)
                assertEquals("1.0.0", result.versionName)
                assertEquals(64, result.sha256.length)
            } finally { file.delete() }
        }
    }

    @Test fun androidParserRejectsForgedManifestHeader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "packages").apply { mkdirs() }
        val file = File(folder, "fake.apk")
        try {
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(3, 0, 8, 0, 8, 0, 0, 0))
                zip.closeEntry()
            }
            try {
                ApkRepository(context).inspect(file, "fake.apk")
                fail("Forged manifest must not be accepted as an APK")
            } catch (_: IllegalArgumentException) { /* Expected */ }
        } finally { file.delete() }
    }
}
