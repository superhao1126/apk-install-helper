package icu.idoit.apkassistant

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Only examines structure. Android's package parser must still accept the APK. */
object ArchiveInspector {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    fun check(file: File) {
        require(file.length() in 1..MAX_BYTES) { "文件为空或超过 2 GB，请重新选择。" }
        try {
            ZipFile(file).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")
                if (manifest == null) {
                    if (zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }) {
                        throw IllegalArgumentException("这是包含 APK 的压缩包或拆分包（XAPK/APKS/APKM）。请先解压，或使用支持拆分安装的工具。")
                    }
                    throw IllegalArgumentException("未找到 Android 安装包清单。这不是普通 APK。")
                }
                require(!manifest.isDirectory && manifest.size in 8..(4L * 1024 * 1024)) {
                    "安装包清单无效或已损坏。"
                }
                // Binary Android XML header, not a renamed plain XML or generic ZIP.
                zip.getInputStream(manifest).use { input ->
                    val header = ByteArray(4)
                    require(input.read(header) == 4 && header.contentEquals(byteArrayOf(3, 0, 8, 0))) {
                        "安装包清单格式无效，请重新获取完整 APK。"
                    }
                }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("文件不是有效 APK，或下载尚未完成。请重新下载。", e)
        }
    }
}
