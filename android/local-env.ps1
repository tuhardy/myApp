# 读取 android\.env 中的本机路径，供 bootstrap.ps1 与 verify.ps1 点引用（. "$PSScriptRoot\local-env.ps1"）。
# 本机路径只写在 .env（不提交），脚本里不写死任何机器相关的路径；模板见 .env.example。

$LocalEnvFile = Join-Path $PSScriptRoot '.env'
$LocalEnvExample = Join-Path $PSScriptRoot '.env.example'
$LocalEnvHelp = "请把 android\.env.example 复制为 android\.env，并把 JAVA_HOME 改成本机 JDK 21 目录（在 android 目录执行：Copy-Item .env.example .env）。"

function Read-LocalEnv {
    $values = @{}
    if (-not (Test-Path -LiteralPath $LocalEnvFile -PathType Leaf)) { return $values }
    foreach ($line in [IO.File]::ReadAllLines($LocalEnvFile)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $separator = $text.IndexOf('=')
        if ($separator -lt 1) { throw "android\.env 格式错误（应为 KEY=VALUE）：$line" }
        $values[$text.Substring(0, $separator).Trim()] = $text.Substring($separator + 1).Trim().Trim('"', "'")
    }
    return $values
}

$LocalEnv = Read-LocalEnv

# 返回 .env 中的路径；相对路径按 android\ 目录解析；未配置时返回 $Default。
function Get-LocalEnvPath([string]$Name, [string]$Default = '') {
    $value = $LocalEnv[$Name]
    if (-not $value) { return $Default }
    if ([IO.Path]::IsPathRooted($value)) { return $value }
    return [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $value))
}

# 校验 JDK 目录；不可用时给出指向 .env 的修改提示。
function Assert-LocalJdk([string]$Path) {
    if (-not $Path) {
        if (Test-Path -LiteralPath $LocalEnvFile -PathType Leaf) { throw "android\.env 中没有填写 JAVA_HOME。请填写本机 JDK 21 目录（该目录下应有 bin\java.exe）。" }
        throw "缺少 android\.env。$LocalEnvHelp"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'bin\java.exe') -PathType Leaf)) {
        throw "JAVA_HOME=$Path 下找不到 bin\java.exe。请修改 android\.env 中的 JAVA_HOME 为本机 JDK 21 目录。"
    }
}
