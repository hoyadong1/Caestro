param(
    [string]$Avd = 'pleos_connect_210',
    [string]$Apk = '',
    [ValidateSet('host', 'auto')][string]$Gpu = 'host',
    [ValidateSet('Park', 'Drive')][string]$Gear = 'Drive'
)

$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$root = Split-Path $PSScriptRoot -Parent
$debugApk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (!$Apk) {
    $Apk = (Get-ChildItem -LiteralPath (Join-Path $root 'apk') -Filter '*_DriveSoundtrack_release.apk' |
        Select-Object -First 1 -ExpandProperty FullName)
}
foreach ($path in @($emulator, $adb, $Apk)) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing: $path" }
}

$connected = & $adb devices
if ($connected -notmatch 'emulator-\d+\s+device') {
    Write-Host "Starting $Avd with -gpu $Gpu"
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $Avd, '-gpu', $Gpu, '-no-snapshot-load') -WindowStyle Normal | Out-Null
}
& $adb wait-for-device
$deadline = (Get-Date).AddMinutes(5)
do {
    $boot = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -eq '1') { break }
    if ((Get-Date) -gt $deadline) { throw 'Pleos emulator did not boot within five minutes.' }
    Start-Sleep -Seconds 2
} while ($true)
$product = (& $adb shell getprop ro.product.name | Out-String).Trim()
if ($product -ne 'crp_avd') { throw "This test launcher is only for the Pleos emulator; connected product: $product" }

$installResult = & $adb install -r $Apk 2>&1
if ($LASTEXITCODE -ne 0 -and ($installResult | Out-String) -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' -and (Test-Path -LiteralPath $debugApk)) {
    Write-Host 'Existing app has a different signing key; trying the local debug APK without deleting app data.'
    $Apk = $debugApk
    $installResult = & $adb install -r $Apk 2>&1
}
$installResult | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Remove the old app manually if its signing key differs.' }
& $adb forward tcp:8088 tcp:8088 | Out-Null
$base = 'http://127.0.0.1:8088'
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    try {
        Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=drive-soundtrack-interactive" -TimeoutSec 3 | Out-Null
        Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 3 | Out-Null
        $gearState = Invoke-RestMethod -Method Put -Uri "$base/vehicle/gear?gear=$Gear" -TimeoutSec 3
        if ($gearState.actual.value -ne $Gear) { throw "Gear selection returned $($gearState.actual.value)" }
        break
    } catch {
        if ($attempt -eq 14) { throw "Could not select $Gear in Pleos: $_" }
        Start-Sleep -Seconds 2
    }
}

$regulation = if ($Gear -eq 'Drive') { 'off' } else { 'on' }
$regulationResult = & $adb shell cmd hmg_car_service --service drivingstate driving_regulation $regulation 2>&1
$regulationResult | Out-Host
if ($LASTEXITCODE -ne 0) { throw "Could not set emulator driving regulation $regulation." }
Start-Sleep -Seconds 1
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Host
Write-Host "Pleos $Gear selected, speed 0 km/h. In the app, tap the scenario start button once."
if ($Gear -eq 'Drive') { Write-Warning 'Driving restrictions are disabled in this emulator for testing. This does not enable the app screen in production vehicles.' }
Write-Host 'Tap a soundscape card during the scenario to compare all five modes.'
