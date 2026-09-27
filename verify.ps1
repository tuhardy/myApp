# 浏览器原型本机验证入口：语法检查 + 模型单测，避免每次重新拼命令。
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1
#   powershell -ExecutionPolicy Bypass -File .\verify.ps1 -Browser
# -Browser 会额外跑 Playwright 回归，需要先在另一终端启动预览服务：
#   uv run --no-project --python 3.12 python -m http.server 5173 --bind 127.0.0.1
# pyee / greenlet 固定为已验证版本，避免临时验证依赖漂移。
[CmdletBinding()]
param([switch]$Browser)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

Write-Host '== node --check =='
node --check app.js
node --check model.js

Write-Host '== node --test =='
node --test model.test.cjs

if ($Browser) {
    Write-Host '== playwright browser_check.py =='
    Write-Host '需要 Edge，以及 127.0.0.1:5173 上正在运行的预览服务。截图输出位置见测试输出。'
    uv run --no-project --python 3.12 --with playwright==1.55.0 --with pyee==13.0.0 --with greenlet==3.2.4 python browser_check.py
    if ($LASTEXITCODE -ne 0) { throw "browser_check.py 失败，退出码 $LASTEXITCODE" }
}
