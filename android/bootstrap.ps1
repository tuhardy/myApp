[CmdletBinding()]
param(
    [string]$JdkPath = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\jdk\jdk21' }),
    [switch]$AcceptAndroidSdkLicense,
    [switch]$UseAliyunGradleMirror
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$downloads = Join-Path $toolsRoot 'downloads'
$gradleVersion = '8.11.1'
$gradleSha256 = 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
$commandToolsSha256 = '4d6931209eebb1bfb7c7e8b240a6a3cb3ab24479ea294f3539429574b1eec862'
$commandToolsSha1 = '3d2917302740f476999a091bc5558837c7a863c5'
$wrapperSha256 = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'

function Ensure-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        $parent = Split-Path -Parent $Path
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
            Ensure-Directory $parent
        }
        Get-Item -LiteralPath $parent | Out-Null
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Get-VerifiedArchive([string[]]$Urls, [string]$Destination, [string]$Sha256) {
    Ensure-Directory (Split-Path -Parent $Destination)
    if ((Test-Path -LiteralPath $Destination) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash -eq $Sha256) {
        return
    }
    foreach ($url in $Urls) {
        & curl.exe --fail --location --show-error --retry 2 --connect-timeout 20 --max-time 1200 --speed-time 60 --speed-limit 1024 --continue-at - --header 'Accept: application/octet-stream' --output "$Destination.partial" $url
        if ($LASTEXITCODE -eq 0) {
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath "$Destination.partial").Hash -ne $Sha256) {
                throw "Archive checksum mismatch: $url"
            }
            Move-Item -LiteralPath "$Destination.partial" -Destination $Destination -Force
            return
        }
    }
    throw "Unable to download an official archive: $Destination"
}

if (-not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe') -PathType Leaf)) {
    throw 'Pass -JdkPath with an installed JDK 21 directory.'
}
if (-not $AcceptAndroidSdkLicense -and -not (Test-Path -LiteralPath (Join-Path $sdkRoot 'licenses\android-sdk-license'))) {
    throw 'Read https://developer.android.com/studio/terms and rerun with -AcceptAndroidSdkLicense only if you agree.'
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousGradleHome = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $JdkPath
    $env:PATH = "$(Join-Path $JdkPath 'bin');$env:PATH"
    $env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-user-home'
    Ensure-Directory $toolsRoot
    Ensure-Directory $downloads
    Ensure-Directory $sdkRoot

    $commandArchive = Join-Path $downloads 'commandlinetools-win-11076708_latest.zip'
    Get-VerifiedArchive @('https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip') $commandArchive $commandToolsSha256
    if ((Get-FileHash -Algorithm SHA1 -LiteralPath $commandArchive).Hash -ne $commandToolsSha1) {
        throw 'Android command-line tools do not match the official repository checksum.'
    }
    $commandTools = Join-Path $sdkRoot 'cmdline-tools\12.0'
    $sdkManager = Join-Path $commandTools 'bin\sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $sdkManager)) {
        $staging = Join-Path $downloads 'command-tools-extracted'
        Ensure-Directory $staging
        Expand-Archive -LiteralPath $commandArchive -DestinationPath $staging -Force
        Ensure-Directory (Split-Path -Parent $commandTools)
        Move-Item -LiteralPath (Join-Path $staging 'cmdline-tools') -Destination $commandTools
    }
    $sdkArguments = @("--sdk_root=$sdkRoot", '--channel=0', 'platforms;android-35', 'build-tools;35.0.0', 'platform-tools')
    if ($AcceptAndroidSdkLicense) {
        'y' | & $sdkManager @sdkArguments
    } else {
        & $sdkManager @sdkArguments
    }
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
    foreach ($requiredPath in @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe', 'platform-tools\adb.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot $requiredPath))) {
            throw "Missing SDK component: $requiredPath"
        }
    }
    $sdkProperty = 'sdk.dir=' + $sdkRoot.Replace('\', '/') + [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), $sdkProperty, [Text.UTF8Encoding]::new($false))

    $gradleArchive = Join-Path $downloads "gradle-$gradleVersion-bin.zip"
    $gradleUrls = @(
        "https://downloads.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
        "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
        'https://api.github.com/repos/gradle/gradle-distributions/releases/assets/207876967'
    )
    if ($UseAliyunGradleMirror) {
        $gradleUrls = @("https://mirrors.aliyun.com/gradle/distributions/v$gradleVersion/gradle-$gradleVersion-bin.zip") + $gradleUrls
    }
    Get-VerifiedArchive $gradleUrls $gradleArchive $gradleSha256
    $gradleRoot = Join-Path $toolsRoot "gradle-$gradleVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $gradleRoot 'bin\gradle.bat'))) {
        Expand-Archive -LiteralPath $gradleArchive -DestinationPath $toolsRoot -Force
    }
    $wrapperJar = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
    if (-not (Test-Path -LiteralPath $wrapperJar) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $wrapperJar).Hash -ne $wrapperSha256) {
        throw 'The checked-in Gradle wrapper JAR is missing or has an unexpected checksum.'
    }
    & (Join-Path $gradleRoot 'bin\gradle.bat') --version
    if ($LASTEXITCODE -ne 0) { throw 'Gradle validation failed.' }
    Write-Host "Android SDK: $sdkRoot"
    Write-Host "Gradle: $gradleRoot"
    Write-Host "For this PowerShell session: `$env:JAVA_HOME = '$JdkPath'; `$env:GRADLE_USER_HOME = '$(Join-Path $toolsRoot 'gradle-user-home')'"
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:GRADLE_USER_HOME = $previousGradleHome
}
