package icu.idoit.apkassistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveInspectorTest {
    private val binaryHeader = byteArrayOf(3, 0, 8, 0, 8, 0, 0, 0)

    private fun withArchive(name: String, entries: Map<String, ByteArray>, test: (File) -> Unit) {
        val folder = Files.createTempDirectory("apk-assistant-test").toFile()
        try {
            val file = File(folder, name)
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                }
            }
            test(file)
        } finally { folder.deleteRecursively() }
    }

    @Test fun arbitrarySuffixesUseContent() {
        listOf("应用.apk", "应用.apk.1", "应用.apk.xx.xx", "应用.bin", "无后缀").forEach { name ->
            withArchive(name, mapOf("AndroidManifest.xml" to binaryHeader)) { ArchiveInspector.check(it) }
        }
    }

    @Test fun archiveWithoutManifestIsRejectedEvenWithApkSuffix() {
        withArchive("fake.apk.1", mapOf("hello.txt" to "hi".toByteArray())) { file ->
            assertThrows(IllegalArgumentException::class.java) { ArchiveInspector.check(file) }
        }
    }

    @Test fun plainXmlCannotMasqueradeAsAndroidBinaryManifest() {
        withArchive("fake.apk", mapOf("AndroidManifest.xml" to "<manifest/>".toByteArray())) { file ->
            assertThrows(IllegalArgumentException::class.java) { ArchiveInspector.check(file) }
        }
    }

    @Test fun splitContainersHaveSpecificMessage() {
        listOf("bundle.xapk.1", "bundle.apks", "bundle.apkm", "bundle.bin").forEach { name ->
            withArchive(name, mapOf("base.apk" to binaryHeader, "split_config.arm64_v8a.apk" to binaryHeader)) { file ->
                val error = assertThrows(IllegalArgumentException::class.java) { ArchiveInspector.check(file) }
                assertTrue(error.message!!.contains("拆分包"))
            }
        }
    }

    @Test fun validApkCanContainAnEmbeddedApkAsset() {
        withArchive("app.apk", mapOf("AndroidManifest.xml" to binaryHeader, "assets/helper.apk" to binaryHeader)) {
            ArchiveInspector.check(it)
        }
    }

    @Test fun emptyAndCorruptFilesAreRejected() {
        val file = File.createTempFile("invalid", ".apk")
        try {
            assertThrows(IllegalArgumentException::class.java) { ArchiveInspector.check(file) }
            file.writeText("not a zip")
            assertThrows(IllegalArgumentException::class.java) { ArchiveInspector.check(file) }
        } finally { file.delete() }
    }
}
