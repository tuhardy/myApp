# Android 构建命令

本文只记录本机 PowerShell 构建命令。先打开 PowerShell 并进入项目的 `android/` 目录：

```powershell
Set-Location .\android
```

## 本次需要：构建 preview APK

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 assemblePreview
```

成功后 APK 位于：

```text
android/app/build/outputs/apk/preview/app-preview.apk
```

preview 使用 debug 签名，包名为 `com.focusassistant.app.preview`，应用名为「专注助手 预览」，版本名带 `-preview` 后缀。它可以与正式包同时安装，但两者数据互不影响。

## 其他常用构建命令

### 只构建 debug APK

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 assembleDebug
```

输出：`android/app/build/outputs/apk/debug/app-debug.apk`。

debug 使用本机 `~/.android/debug.keystore` 签名，包名保持 `com.focusassistant.app`。换电脑后签名可能不同；不要通过卸载旧应用来覆盖安装，以免丢失本机数据。

### 同时构建 debug 和 preview APK

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 assembleDebug assemblePreview
```

### 执行默认完整验证并构建 debug APK

不传 Gradle 任务时，脚本依次执行 `testDebugUnitTest assembleDebug lintDebug`：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1
```

### 构建 preview，同时执行 debug 单测和 lint

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 testDebugUnitTest lintDebug assemblePreview
```

### 清理后重新构建 preview

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 clean assemblePreview
```

### 显示动态下载进度并构建 preview

默认 `--console=plain` 不显示动态下载进度。首次下载依赖、需要人工观察进度时使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Rich assemblePreview
```

### 不使用 Gradle daemon 构建 preview

仅在怀疑 daemon 状态导致构建异常时使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 -NoDaemon assemblePreview
```

### 将完整构建日志写入文件

不要把 daemon 构建的输出管道接给 `tail` 等命令，否则句柄可能使命令迟迟不返回。改为重定向：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify.ps1 assemblePreview > build.log 2>&1
```

日志写在 `android/build.log`；不要在查看日志前仅凭终端尚未返回判断构建失败。

## 本机工具链尚未准备好时

`android/verify.ps1` 使用本机已安装的 Gradle 8.11.1，并读取 `android/.env` 中的 `JAVA_HOME`，以及可选的 `ANDROID_HOME`、`GRADLE_USER_HOME`。缺少工具链时先准备环境：

```powershell
powershell -ExecutionPolicy Bypass -File .\bootstrap.ps1 -JdkPath "本机 JDK 21 目录"
```

脚本会下载 Android SDK 和 Gradle，因此需要联网。只有阅读并同意 Android SDK 许可后，才追加 `-AcceptAndroidSdkLicense`；需要优先使用阿里云 Gradle 镜像时可追加 `-UseAliyunGradleMirror`。

## 注意

- 脚本末尾会打印已经存在的 debug / preview APK 路径、大小和修改时间；只有对应的 `assemble` 任务成功后，该 APK 才是本次更新的产物。
- 构建、单元测试和 lint 通过，不等于真机 UI、权限与后台行为已经验证。
- 当前工程没有配置固定的正式发布签名；preview 是可安装的预览构建，不应当作已签名正式 release 包。
