package icu.idoit.apkassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var repository: ApkRepository
    private lateinit var status: TextView
    private lateinit var details: LinearLayout
    private lateinit var choose: Button
    private lateinit var install: Button
    private lateinit var clear: Button
    private lateinit var progress: ProgressBar
    private var apk: ApkDetails? = null
    private var busy = false
    private var generation = 0
    private var restoringPath: String? = null
    private var restoringName: String? = null
    private var waitingPermission = false
    private var installerOpen = false
    private var activeUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ApkRepository(applicationContext)
        render()
        worker.execute { repository.cleanOldFiles() }
        waitingPermission = savedInstanceState?.getBoolean("waitingPermission") ?: false
        installerOpen = savedInstanceState?.getBoolean("installerOpen") ?: false
        val path = savedInstanceState?.getString("file")
        val name = savedInstanceState?.getString("name")
        when {
            path != null -> {
                restoringPath = path
                restoringName = name
                load { repository.inspect(File(path), name ?: "安装包") }
            }
            savedInstanceState?.getString("activeUri") != null -> importFile(Uri.parse(savedInstanceState.getString("activeUri")))
            savedInstanceState == null -> receive(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (busy || installerOpen) {
            Toast.makeText(this, "请完成当前操作后重新打开文件。", Toast.LENGTH_LONG).show()
        } else receive(intent)
    }

    @Suppress("DEPRECATION")
    private fun receive(incoming: Intent) {
        if (incoming.action == Intent.ACTION_MAIN) return
        val uris = linkedSetOf<Uri>()
        try {
            if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
                incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            } else incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            incoming.data?.let { uris.add(it) }
            incoming.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
            when (uris.size) {
                0 -> showError("没有收到可读取的文件。请下载完成后，用「其他应用打开」，或在下方手动选择。")
                1 -> importFile(uris.first())
                else -> showError("请每次选择一个普通 APK；暂不支持批量或拆分包安装。")
            }
        } catch (_: Exception) {
            showError("接收文件失败，请使用「选择安装包」重新选择。")
        }
    }

    private fun importFile(uri: Uri) {
        activeUri = uri
        load { repository.import(uri) }
    }

    private fun load(action: () -> ApkDetails) {
        busy = true
        apk?.file?.delete()
        apk = null
        details.removeAllViews()
        status.text = "正在读取并识别安装包…"
        status.setTextColor(INK)
        updateButtons()
        val request = ++generation
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || request != generation) return@runOnUiThread
                busy = false
                activeUri = null
                restoringPath = null
                restoringName = null
                result.fold(onSuccess = {
                    apk = it
                    showDetails(it)
                }, onFailure = {
                    val message = when (it) {
                        is SecurityException -> "来源应用没有授予文件读取权限。请将文件保存到下载目录，再手动选择。"
                        is java.io.IOException -> "读取失败：文件可能未下载完成、已被移动，或手机空间不足。请重新选择。"
                        else -> it.message ?: "文件识别失败，请重新获取完整的普通 APK。"
                    }
                    showError(message)
                })
                updateButtons()
            }
        }
    }

    private fun showDetails(item: ApkDetails) {
        status.text = "已识别普通 APK，请确认应用信息"
        status.setTextColor(GREEN)
        details.removeAllViews()
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        item.icon?.let { icon -> heading.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }) }
        heading.addView(label(item.label, 20, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        details.addView(heading)
        field("包名", item.packageName)
        field("版本", "${item.versionName}（${item.versionCode}）")
        field("文件", item.originalName)
        field("大小", android.text.format.Formatter.formatFileSize(this, item.file.length()))
        field("最低系统", "Android API ${item.minSdk}")
        val hash = TextView(this).apply {
            text = "SHA-256\n${item.sha256}"
            textSize = 11f
            setTextColor(MUTED)
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        details.addView(hash)
        details.addView(label("请确认来源可信。名称和图标不代表安全认证，签名冲突、版本降级和兼容性由系统安装器最终检查。", 12, MUTED).apply { setPadding(0, dp(16), 0, 0) })
        updateButtons()
    }

    private fun showError(message: String) {
        apk = null
        details.removeAllViews()
        status.text = message
        status.setTextColor(Color.rgb(161, 65, 44))
        updateButtons()
    }

    private fun selectFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }, PICK_FILE)
        } catch (_: ActivityNotFoundException) {
            status.text = "系统文件选择器不可用，请从文件管理器选择「其他应用打开」。"
        }
    }

    private fun requestInstall() {
        if (apk == null || busy || installerOpen) return
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("允许安装未知应用")
                .setMessage("首次使用需要在系统设置中允许「安装助手」安装应用。返回后再次点击安装，系统仍会要求你确认。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        waitingPermission = true
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), INSTALL_PERMISSION)
                    } catch (_: ActivityNotFoundException) {
                        waitingPermission = false
                        status.text = "请在系统设置中为安装助手开启「安装未知应用」。"
                    }
                }.show()
            return
        }
        launchInstaller()
    }

    @Suppress("DEPRECATION")
    private fun launchInstaller() {
        val item = apk ?: return
        if (!item.file.isFile) { showError("临时文件已被系统清理，请重新选择。"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", item.file)
            val request = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newRawUri("APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            installerOpen = true
            updateButtons()
            startActivityForResult(request, INSTALL_PACKAGE)
        } catch (_: Exception) {
            installerOpen = false
            updateButtons()
            status.text = "无法启动系统安装器，请检查系统是否限制安装应用。"
        }
    }

    @Deprecated("Platform activity result API used to keep this app lightweight")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_FILE -> if (resultCode == RESULT_OK) data?.data?.let { importFile(it) }
            INSTALL_PERMISSION -> {
                waitingPermission = false
                status.text = if (packageManager.canRequestPackageInstalls()) "权限已开启，请点击「交给系统安装」继续。" else "尚未允许安装。可以稍后再次开启权限。"
            }
            INSTALL_PACKAGE -> {
                installerOpen = false
                status.text = when (resultCode) {
                    RESULT_OK -> "系统报告安装成功。"
                    RESULT_CANCELED -> "安装页面已关闭。若未完成安装，可再次尝试。"
                    else -> "系统未完成安装。请检查安装器提示的签名、版本、空间或系统限制。"
                }
                updateButtons()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("file", apk?.file?.absolutePath ?: restoringPath)
        outState.putString("name", apk?.originalName ?: restoringName)
        outState.putString("activeUri", activeUri?.toString())
        outState.putBoolean("waitingPermission", waitingPermission)
        outState.putBoolean("installerOpen", installerOpen)
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun updateButtons() {
        choose.isEnabled = !busy && !installerOpen
        install.isEnabled = !busy && apk != null && !installerOpen
        clear.visibility = if (apk == null) View.GONE else View.VISIBLE
        clear.isEnabled = !busy && !installerOpen
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun render() {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        scroll.addView(root)
        // Target 35 draws edge to edge on Android 15; keep controls outside system bars.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(label("安装助手", 26, INK, true))
        root.addView(label("收到的文件，多了后缀也能识别", 14, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        }
        root.addView(card)
        status = label("选择一个文件，查看它是否是有效的 Android 安装包。", 15, INK)
        card.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(16) })
        details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        card.addView(details)
        install = button("交给系统安装", true) { requestInstall() }
        card.addView(install, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        choose = button("选择安装包", false) { selectFile() }
        root.addView(choose, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        clear = button("清除当前文件", false) {
            apk?.file?.delete()
            apk = null
            details.removeAllViews()
            status.text = "已清除临时文件，可继续选择安装包。"
            status.setTextColor(INK)
            updateButtons()
        }
        root.addView(clear, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(label("如何使用", 16, INK, true).apply { setPadding(0, dp(26), 0, dp(10)) })
        root.addView(label("1. 在微信或 QQ 下载完成后，选择「其他应用打开」或「分享」，找到安装助手。\n\n2. 也可以点击上方按钮，从系统文件选择器中找到文件。\n\n3. 核对应用名称、包名和版本，再交给系统安装。", 14, MUTED))
        root.addView(label("支持普通 APK，无论文件名是 .apk.1、.apk.xx.xx、.bin，还是没有后缀。暂不支持 XAPK / APKS / APKM 拆分包。\n\n全程离线 · 不修改原文件 · 无需全部文件访问权限", 12, MUTED).apply { setPadding(0, dp(24), 0, 0) })
        setContentView(scroll)
        updateButtons()
    }

    private fun field(title: String, value: String) {
        details.addView(label("$title  $value", 14, INK).apply { setPadding(0, dp(12), 0, 0); setTextIsSelectable(true) })
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 15f; isAllCaps = false
        if (primary) backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(GREEN, Color.rgb(210, 219, 215)))
        setTextColor(if (primary) Color.WHITE else GREEN)
        setOnClickListener { click() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val PICK_FILE = 10
        private const val INSTALL_PERMISSION = 11
        private const val INSTALL_PACKAGE = 12
        private val GREEN = Color.rgb(35, 118, 93)
        private val INK = Color.rgb(30, 44, 39)
        private val MUTED = Color.rgb(100, 115, 108)
    }
}
