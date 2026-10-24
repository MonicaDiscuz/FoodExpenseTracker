# 一键生成 release 签名（keystore）+ keystore.properties
# 用法：在项目根目录执行  powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1
#
# 生成后重新 Sync / 构建，`assembleRelease` 产物即为正式签名包，
# 可以正常安装到华为 / 鸿蒙设备（从文件管理器或浏览器安装）。

param(
    [string]$StorePassword = "foodexpense123",
    [string]$KeyPassword = "foodexpense123",
    [string]$Alias = "foodexpense",
    [string]$KeystoreName = "release.keystore"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---------- 1. 定位 keytool ----------
$keytool = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\keytool.exe") }
$candidates += @(
    "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe",
    "$env:ProgramFiles\Java\jdk-17\bin\keytool.exe",
    "$env:ProgramFiles\Java\jdk-21\bin\keytool.exe"
)
foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $keytool = $c; break }
}
if (-not $keytool) {
    $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) {
    Write-Error "没有找到 keytool.exe。请先安装 JDK 17，或设置 JAVA_HOME 环境变量后再运行本脚本。"
}

Write-Host "使用 keytool: $keytool"
Write-Host "项目根目录: $root"

# ---------- 2. 生成 keystore ----------
$keystorePath = Join-Path $root $KeystoreName
if (Test-Path $keystorePath) {
    Write-Host "已存在 $KeystoreName，跳过生成（如需重建请先删除该文件）。"
} else {
    & $keytool -genkeypair -v `
        -keystore $keystorePath `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity 36500 `
        -storepass $StorePassword -keypass $KeyPassword `
        -dname "CN=FoodExpense, OU=Personal, O=Personal, L=CN, ST=CN, C=CN"
    if ($LASTEXITCODE -ne 0) { Write-Error "keystore 生成失败（exit $LASTEXITCODE）" }
    Write-Host "已生成: $keystorePath"
}

# ---------- 3. 写 keystore.properties ----------
$propsPath = Join-Path $root "keystore.properties"
$content = @(
    "storeFile=$KeystoreName",
    "storePassword=$StorePassword",
    "keyAlias=$Alias",
    "keyPassword=$KeyPassword"
) -join "`n"
Set-Content -Path $propsPath -Value $content -Encoding UTF8
Write-Host "已写入: $propsPath"

Write-Host ""
Write-Host "完成 ✅ 接下来：" -ForegroundColor Green
Write-Host "  1) Android Studio 里 Sync Project（或命令行 .\gradlew.bat assembleRelease）"
Write-Host "  2) 产物: app\build\outputs\apk\release\app-release.apk —— 这是正式签名包"
Write-Host "  3) 把 app-release.apk 拷到手机安装；若仍被拦截，请关闭手机「纯净模式」并允许安装未知应用"
