package icu.idoit.apkassistant

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID

data class ApkDetails(
    val file: File, val originalName: String, val label: String, val packageName: String,
    val versionName: String, val versionCode: Long, val minSdk: Int,
    val icon: Drawable?, val sha256: String
)

class ApkRepository(private val context: Context) {
    private val folder = File(context.cacheDir, "packages").apply { mkdirs() }

    fun cleanOldFiles() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        folder.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun import(uri: Uri): ApkDetails {
        require(uri.scheme == "content" || uri.scheme == "file") {
            "没有收到可读取的文件，请先下载，再使用「选择安装包」。"
        }
        if (uri.scheme == "file") {
            val path = File(requireNotNull(uri.path)).canonicalPath
            require(path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/")) {
                "无法读取此文件路径，请通过系统文件选择器重新选择。"
            }
        }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"
        var declaredSize = -1L
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        require(declaredSize <= ArchiveInspector.MAX_BYTES) { "文件超过 2 GB，暂不支持。" }
        require(declaredSize < 0 || declaredSize < folder.usableSpace - RESERVE_BYTES) {
            "手机剩余空间不足，请清理后重试。"
        }
        val destination = File(folder, "${UUID.randomUUID()}.apk")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法读取文件，请先下载完成，再重新选择。")
            input.use { source ->
                destination.outputStream().use { target ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= ArchiveInspector.MAX_BYTES) { "文件超过 2 GB，暂不支持。" }
                        require(folder.usableSpace > count + RESERVE_BYTES) { "手机剩余空间不足，请清理后重试。" }
                        target.write(buffer, 0, count)
                    }
                    require(declaredSize < 0 || total == declaredSize) { "文件未读取完整，请下载完成后重试。" }
                }
            }
            return inspect(destination, name.take(240))
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    fun inspect(file: File, originalName: String): ApkDetails {
        require(file.canonicalFile.parentFile == folder.canonicalFile && file.isFile) {
            "临时安装包已清理，请重新选择文件。"
        }
        ArchiveInspector.check(file)
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("Android 无法解析此安装包，文件可能损坏、缺少签名或不是普通 APK。")
        val app = info.applicationInfo ?: throw IllegalArgumentException("缺少应用信息，无法安装。")
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val signed = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.isNotEmpty() == true
            else !info.signatures.isNullOrEmpty()
        require(signed) { "安装包未包含有效签名，请获取原始 APK。" }
        val resources = pm.getResourcesForApplication(app)
        resources.assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                if (xml.name == "manifest") {
                    require(xml.getAttributeValue(null, "split").isNullOrEmpty()) {
                        "这是拆分 APK，不能单独安装，请获取完整的普通 APK。"
                    }
                }
                require(xml.name != "uses-split" && !(xml.name == "application" &&
                    xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "isSplitRequired", false))) {
                    "此 APK 依赖其他拆分包，请获取完整的普通 APK，或使用支持拆分安装的工具。"
                }
            }
        }
        require(app.minSdkVersion <= Build.VERSION.SDK_INT) {
            "此应用要求 Android API ${app.minSdkVersion} 或更高版本，当前手机不兼容。"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return ApkDetails(file, originalName, runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
            info.packageName, info.versionName ?: "未标注", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            app.minSdkVersion, runCatching { app.loadIcon(pm) }.getOrNull(),
            digest.digest().joinToString("") { "%02x".format(it) })
    }

    companion object { private const val RESERVE_BYTES = 64L * 1024 * 1024 }
}
