# Android 本机验证入口：集中一份环境变量，避免每次重新拼命令。
# 用法（在 android/ 目录）：
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1 assembleDebug
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1 -NoDaemon
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Rich
# 不传任务时执行 testDebugUnitTest assembleDebug lintDebug。
# 走本机已校验的 Gradle 8.11.1，不用 wrapper，避免重复联网下载。
# 默认复用 Gradle daemon 省去每次冷启动 JVM；构建行为可疑时用 -NoDaemon 排除 daemon 状态。
# 默认 --console=plain 便于把输出重定向到文件阅读，但它会隐藏下载进度条；
# 人工盯大文件下载时用 -Rich 换成动态进度输出。
[CmdletBinding()]
param(
    [switch]$NoDaemon,
    [switch]$Rich,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = 'D:\jdk\jdk21'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-user-home'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$gradle = Join-Path $projectRoot '.tools\gradle-8.11.1\bin\gradle.bat'

foreach ($required in @($env:JAVA_HOME, $env:ANDROID_HOME, $gradle)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "缺少 $required，请先运行 .\bootstrap.ps1 准备本机工具链。"
    }
}

if (-not $Tasks -or $Tasks.Count -eq 0) {
    $Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug')
}

Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "gradle           = $gradle"
Write-Host "tasks            = $($Tasks -join ' ')"

$options = @(if ($Rich) { '--console=rich' } else { '--console=plain' })
if ($NoDaemon) { $options += '--no-daemon' }
Write-Host "options          = $($options -join ' ')"

& $gradle @Tasks @options
if ($LASTEXITCODE -ne 0) {
    throw "Gradle 失败，退出码 $LASTEXITCODE"
}

# APK 只在 assemble 任务后才会更新，路径固定，方便直接覆盖安装。
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path -LiteralPath $apk) {
    $info = Get-Item -LiteralPath $apk
    Write-Host "APK: $apk ($([math]::Round($info.Length / 1MB, 1)) MB, $($info.LastWriteTime))"
}
