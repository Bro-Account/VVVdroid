#Requires -Version 5.1
<#
.SYNOPSIS
    Builds VVVdroid, installs it on an emulator (or a connected phone) and
    opens a test catalog through the launch-intent extras.

.DESCRIPTION
    Cuts the manual cycle down to one command: start/await the emulator,
    build, install, grant "All files access", push the test catalogs, launch
    with a given open mode, then dump the visible UI text and a screenshot.

    The API 37 system image needs roughly 12 GB free disk space and 8 GB RAM;
    the script refuses to start below 12 GB free so a boot cannot fill the
    system drive.

.PARAMETER Avd
    Emulator AVD to start when no device is connected.

.PARAMETER Serial
    Use a specific device/emulator (e.g. the phone over USB) instead of
    starting the AVD.

.PARAMETER Catalog
    Local catalog files to push to /sdcard/Download/vvvdroid-test/.

.PARAMETER Mode
    auto    ask when the catalog is read-write (default)
    copy    always open a private copy
    inplace open the catalog where it is

.PARAMETER Open
    Which of the pushed catalogs to open. Defaults to the last one, so
    passing the read-only file last tests the zero-write path.

.PARAMETER WaitSeconds
    How long to wait for the app to report a result (copy mode needs more).

.PARAMETER NoBuild
    Skip the Gradle build (use the existing APK).

.EXAMPLE
    .\scripts\emulator-test.ps1 -Catalog .\test\_AndroidTest_ro.vvv

.EXAMPLE
    .\scripts\emulator-test.ps1 -Catalog .\test\_AndroidTest.vvv -Mode copy
#>
[CmdletBinding()]
param(
    [string]$Avd = "Medium_Phone_API_37.0",
    [string]$Serial,
    [string[]]$Catalog = @("$PSScriptRoot\..\test\_AndroidTest.vvv"),
    [ValidateSet("auto", "copy", "inplace")]
    [string]$Mode = "auto",
    [string]$Open,
    [int]$WaitSeconds = 120,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$Root = Split-Path $PSScriptRoot -Parent
$Sdk  = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$Adb  = Join-Path $Sdk "platform-tools\adb.exe"
$Emu  = Join-Path $Sdk "emulator\emulator.exe"
$Apk  = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$Pkg  = "com.vvvdroid.app"
$RemoteDir = "/sdcard/Download/vvvdroid-test"

function Invoke-Adb {
    param([string[]]$Arguments)
    $target = @()
    if ($Serial) { $target = @("-s", $Serial) }
    $lines = @(& $Adb @target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    return [pscustomobject]@{ Lines = $lines; Text = ($lines -join "`n"); Code = $LASTEXITCODE }
}

function Get-Device {
    $target = @()
    if ($Serial) { $target = @("-s", $Serial) }
    $out = @(& $Adb @target devices 2>&1)
    foreach ($line in $out) {
        if ($line -match '^(\S+)\s+device$') { return $Matches[1] }
    }
    return $null
}

function Wait-Boot {
    param([int]$TimeoutSeconds = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $probe = Invoke-Adb @("shell", "getprop", "sys.boot_completed")
        if ($probe.Text.Trim() -eq "1") { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Dump-Ui {
    param([string]$ScreenshotPath)
    Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    Invoke-Adb @("shell", "wm", "dismiss-keyguard") | Out-Null
    $dump = Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    if ($dump.Code -ne 0) {
        Write-Host "  (uiautomator dump failed: $($dump.Text.Trim()))"
        return
    }
    $xml = (Invoke-Adb @("exec-out", "cat", "/sdcard/ui.xml")).Text
    $texts = [regex]::Matches($xml, 'text="([^"]+)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_.Trim() -ne "" } |
        Select-Object -Unique
    foreach ($t in $texts) { Write-Host "  | $t" }

    if ($ScreenshotPath) {
        $targetArgs = if ($Serial) { "-s $Serial " } else { "" }
        & cmd /c "`"$Adb`" $targetArgs exec-out screencap -p > `"$ScreenshotPath`""
        Write-Host "  screenshot: $ScreenshotPath"
    }
}

# --- preconditions ---------------------------------------------------------

if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }

$free = (Get-PSDrive C).Free
if (-not $Serial -and $free -lt 12GB) {
    throw ("Not enough free disk space for the emulator: {0:N1} GB free, about 12 GB needed. " +
        "Free some space or run on a phone with -Serial <device>." -f ($free / 1GB))
}

$resolved = @()
foreach ($c in $Catalog) {
    $full = [System.IO.Path]::GetFullPath($c)
    if (-not (Test-Path -LiteralPath $full)) { throw "Test catalog not found: $full" }
    $resolved += $full
}
if ($resolved.Count -eq 0) { throw "No test catalogs given." }

$openLocal = if ($Open) { [System.IO.Path]::GetFullPath($Open) } else { $resolved[-1] }
$openRemote = "$RemoteDir/" + (Split-Path $openLocal -Leaf)

# --- device ---------------------------------------------------------------

$device = Get-Device
if (-not $device) {
    Write-Host "Starting emulator $Avd (this takes a minute)..."
    Start-Process -FilePath $Emu -ArgumentList @(
        "-avd", $Avd,
        "-no-snapshot-save",
        "-no-boot-anim",
        "-gpu", "auto"
    ) | Out-Null
    if (-not (Wait-Boot)) { throw "Emulator did not finish booting in time (adb: $Adb devices)." }
    $device = Get-Device
    if (-not $device) { throw "No device after boot." }
}
Write-Host "Device: $device"
if (-not (Wait-Boot)) { throw "Device is not booted (sys.boot_completed != 1)." }

# --- build + install ------------------------------------------------------

if (-not $NoBuild) {
    Write-Host "Building..."
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    Push-Location $Root
    try {
        & .\gradlew.bat :app:assembleDebug --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
    }
    finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }

Write-Host "Installing..."
$install = Invoke-Adb @("install", "-r", $Apk)
$install.Lines | Where-Object { $_ -match "Success|Failure|Error" } | ForEach-Object { "  $_" }
if ($install.Text -notmatch "Success") { throw "Install failed: $($install.Text)" }

# "All files access" is an app-op; this is the adb equivalent of the toggle.
Invoke-Adb @("shell", "appops", "set", $Pkg, "MANAGE_EXTERNAL_STORAGE", "allow") | Out-Null

# --- push + launch --------------------------------------------------------

Invoke-Adb @("shell", "mkdir", "-p", $RemoteDir) | Out-Null
foreach ($c in $resolved) {
    Write-Host ("Pushing {0} ({1:N0} MB)..." -f (Split-Path $c -Leaf), ((Get-Item $c).Length / 1MB))
    $push = Invoke-Adb @("push", $c, "$RemoteDir/")
    if ($push.Code -ne 0) { throw "adb push failed: $($push.Text)" }
}

Write-Host "Launching $openRemote (mode=$Mode)..."
Invoke-Adb @("shell", "am", "force-stop", $Pkg) | Out-Null
$launch = Invoke-Adb @(
    "shell", "am", "start", "-n", "$Pkg/.MainActivity",
    "-e", "vvv.catalog", $openRemote,
    "-e", "vvv.mode", $Mode
)
if ($launch.Text -match "Error") { throw "Launch failed: $($launch.Text)" }

# --- result ---------------------------------------------------------------

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$lastText = ""
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $dump = Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    if ($dump.Code -ne 0) { continue }
    $xml = (Invoke-Adb @("exec-out", "cat", "/sdcard/ui.xml")).Text
    $lastText = $xml
    if ($xml -match "Schema version|Open failed|Not a Firebird|legacy|cancelled") { break }
}

Write-Host ""
Write-Host "UI:"
if ($lastText -match "Schema version|Open failed|Not a Firebird") {
    Dump-Ui -ScreenshotPath (Join-Path $env:TEMP "vvvdroid-ui.png")
}
else {
    Dump-Ui -ScreenshotPath (Join-Path $env:TEMP "vvvdroid-ui.png")
    Write-Host "  (no result text yet - copy still running?)"
}

Write-Host ""
Write-Host "Logcat (crashes):"
$log = Invoke-Adb @("logcat", "-d", "-t", "400")
$log.Lines | Where-Object { $_ -match "AndroidRuntime|FATAL|vvvdroid|$Pkg" } |
    Select-Object -Last 25 | ForEach-Object { "  $_" }
