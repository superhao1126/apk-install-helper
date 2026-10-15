# APK 安装助手

一个使用 Android 原生 Kotlin 开发的轻量安装工具，用于识别并安装通过微信、QQ 等应用接收到的普通 APK。即使文件被追加了 `.1`、`.xx.xx` 等后缀，也可以按文件内容识别，再交给系统安装器完成安装。

最低支持 Android 8.0（API 26），应用包名为 `icu.idoit.apkassistant`。

## 项目背景

在日常开发、内部测试和应用分发过程中，经常需要通过微信或 QQ 向手机发送 APK。部分接收场景中，文件名会变成 `应用.apk.1`、`应用.apk.1.1` 等形式，导致点击文件时无法正常进入系统安装页面。

通常可以通过文件管理器找到文件，再手动修改后缀。但对接收者而言，这个过程存在几个问题：

- 不容易找到聊天应用保存文件的位置。
- 不清楚应该删除哪些后缀，多次转发后文件名可能更加复杂。
- 只修改后缀无法确认文件是否真的是有效安装包。
- 安装前缺少直观的应用名称、包名和版本信息，容易选错文件。

本项目将这些操作收敛为一个流程：**接收或选择文件 → 按内容识别 → 核对应用信息 → 系统确认安装**。

## 解决的问题

| 问题 | 处理方式 |
| --- | --- |
| APK 被追加任意后缀，无法直接安装 | 按内容识别，在私有缓存中保存为标准 `.apk` 文件，不依赖原始扩展名 |
| 手动查找、重命名步骤繁琐 | 提供「其他应用打开」、分享和系统文件选择器入口 |
| 普通文件被改名为 APK，难以判断格式 | 检查文件结构，再调用 Android 系统解析器读取应用及签名信息 |
| 安装前不容易确认应用和版本 | 展示图标、名称、包名、版本、大小、最低系统版本和 SHA-256 |
| 首次安装被未知来源权限拦截 | 引导用户前往系统设置，授权后返回继续安装 |

助手只处理接收到的文件副本，不修改聊天或下载目录中的原文件。

## 功能

- 接收「其他应用打开」（ACTION_VIEW）和分享（ACTION_SEND），也支持系统文件选择器。
- 按内容识别，与文件后缀无关：`.apk.1`、`.apk.xx.xx`、`.bin`、无扩展名均可。
- 复制到私有缓存后检查 ZIP/二进制清单结构，再调用 Android PackageManager 解析应用与签名信息。
- 显示图标、应用名、包名、版本、大小、最低系统版本和文件 SHA-256。
- 用户确认后，用受限 FileProvider URI 将文件交给系统安装器。首次引导开启安装未知应用权限。
- 全程离线，无网络、存储扫描或全部文件访问权限；不修改原文件。临时文件可手动清除，启动时清理超过 24 小时的缓存。

## 使用

1. 先安装助手本身。首次可用 USB 或系统浏览器下载，也可在文件管理器将助手文件恢复成 `.apk` 后安装。
2. 在微信/QQ 下载完成，点「其他应用打开」或分享，选择安装助手。也可以在助手中点「选择安装包」。
3. 核对包名、版本和来源，点击「交给系统安装」。首次开启权限后，返回助手再次点击安装。
4. 在系统安装页面确认。

微信/QQ 是否展示助手，取决于实际版本传出的 Intent、MIME 和临时读取授权。如果来源没有提供打开入口或未授予权限，请保存到系统文件选择器可访问的位置再手动选取。Android 对应用私有目录的访问限制仍然适用。

## 实现原理

1. 通过 `ACTION_VIEW`、`ACTION_SEND` 接收文件 URI，或通过 `ACTION_OPEN_DOCUMENT` 选择文件。
2. 将文件流复制到私有缓存目录，使用随机生成的 `.apk` 文件名，避免依赖来源文件名。
3. 检查 ZIP 结构及根目录中的 Android 二进制清单。
4. 使用 `PackageManager.getPackageArchiveInfo` 解析应用、版本和签名信息，并检查拆分标记及最低系统版本。
5. 展示安装包信息，等待用户确认。
6. 通过受限的 `FileProvider` URI 向系统安装器授予临时读取权限，由系统继续完成安装。

## 支持范围

仅支持单个普通 APK，单文件上限 2 GB，复制时保留 64 MB 可用空间。不解压安装 XAPK/APKS/APKM，不批量安装。能识别并拒绝标记为依赖拆分包的 APK；缺失拆分依赖、签名冲突、降级、架构和系统策略等问题仍可能由系统安装器拒绝。

有效包信息不代表应用可信或无恶意代码；助手不提供安全认证，也不绕过系统安装确认。

## 构建

环境：JDK 21、Android SDK Platform 36、网络可访问 Google Maven / Maven Central。Gradle Wrapper 为 8.14.3，AGP 8.13.0，Kotlin 2.2.20。

### Android Studio 运行

1. 使用 Android Studio 打开项目根目录；如果没有识别为 Android 工程，可选择根目录的 `settings.gradle.kts` 打开为项目。
2. 等待 Gradle 同步及代码索引完成，Gradle JDK 使用 JDK 21。
3. 选择 `app` 运行配置及 Android 8.0 以上的模拟器或真机。
4. 点击 **Run** 编译并运行。

真机需要开启 USB 调试并允许电脑连接。微信、QQ 文件打开入口应使用安装了相应应用的真机验证。

### 命令行构建

```sh
# Android Studio 可直接打开本目录；也可写入自己的 SDK 路径
printf 'sdk.dir=/你的/Android/sdk\n' > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

测试版产物：`app/build/outputs/apk/debug/app-debug.apk`。执行下方产物检查脚本后，会在 `artifacts/` 下生成 APK 副本及 `verification.json` 报告。脚本需要 Python 3、JDK 和 Android SDK Build Tools 36.0.0。

Debug APK 使用开发机的调试证书签名，适合开发测试。正式分发可通过 Android Studio 的 **Build → Generate Signed App Bundle or APK** 生成签名 APK。请妥善备份正式签名密钥，后续版本必须沿用同一密钥并递增 `versionCode`。默认命令行 release 构建未配置签名。

## 验证

基础识别和安装流程已通过开发者真机手动验证。不同手机系统、微信/QQ 版本及文件传递入口仍需分别验证，不代表下方所有验收场景均已覆盖。

桌面检查命令：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

单元测试覆盖任意后缀、空文件、伪造 XML、普通 ZIP、损坏 ZIP、拆分容器和包含内嵌 APK 资源的普通包。桌面产物检查验证 ZIP CRC、编译清单、包信息、签名，以及任意后缀下内容的一致性。

另提供 `ApkRepositoryDeviceTest`，用于在 Android 环境中验证系统解析器识别真实签名 APK 及拒绝伪造清单。仅编译测试 APK 不代表已在设备上执行并通过该测试。

建议覆盖的真机验收场景：

- 微信和 QQ 各测试「其他应用打开」和分享；比较 `.apk.1`、`.apk.xx.xx`、`.bin` 和无后缀。
- 手动选文件能显示正确的名称、包名和版本；安装确认能继续、取消后可重试。
- 首次未知来源权限拒绝、允许及返回；切到后台、旋转屏幕后继续操作。
- 普通 ZIP、损坏文件、空文件、XAPK/APKS、单独拆分 APK 能明确提示。
- 用签名冲突、版本降级、不兼容架构的包，确认系统能给出拒绝原因。

## 项目结构

```text
app/src/main/
├── java/icu/idoit/apkassistant/
│   ├── MainActivity.kt       # 文件入口、界面、权限和安装流程
│   ├── ApkRepository.kt      # 文件复制、系统解析和摘要
│   └── ArchiveInspector.kt   # APK 文件结构检查
├── res/                      # 主题、图标和 FileProvider 路径
└── AndroidManifest.xml       # 应用权限和文件接收入口
app/src/test/                # 桌面单元测试
app/src/androidTest/         # Android 设备测试
scripts/verify_apk.py         # 桌面 APK 产物检查
```

主要文件职责：

- `MainActivity.kt`：接收 Intent、原生界面、文件选择、权限和安装流程。
- `ApkRepository.kt`：安全复制、系统解析、签名信息、拆分标记和摘要。
- `ArchiveInspector.kt`：与 Android 无关的文件结构检查。
- `AndroidManifest.xml` / `file_paths.xml`：文件接收入口及仅暴露安装缓存的 FileProvider。

## 源码与分发

`local.properties`、IDE 配置、Gradle 缓存、构建产物和签名密钥已列入 `.gitignore`，不应提交到源码仓库。需要提供下载时，可以通过远程仓库的 Release/附件功能单独分发 APK。

## 参考文档

- [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)
- [Android Intent](https://developer.android.com/reference/android/content/Intent)
- [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
