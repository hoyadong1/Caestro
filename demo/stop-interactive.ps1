$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "Missing: $adb" }
$devices = & $adb devices
if ($devices -notmatch 'emulator-\d+\s+device') {
    Write-Host 'No emulator is running.'
    exit 0
}
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.PAUSE | Out-Null
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $regulationResult = & $adb shell cmd hmg_car_service --service drivingstate driving_regulation on 2>&1
    $regulationCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($regulationCode -ne 0) { Write-Host 'Driving-regulation test service is unavailable on this emulator image.' }
else { $regulationResult | Out-Host }
& $adb forward tcp:8088 tcp:8088 | Out-Null
$base = 'http://127.0.0.1:8088'
try {
    Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=drive-soundtrack-interactive" -TimeoutSec 3 | Out-Null
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 3 | Out-Null
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/gear?gear=Park" -TimeoutSec 3 | Out-Null
} catch {
    Write-Warning "Could not reset virtual vehicle state: $_"
}
& $adb emu kill | Out-Host
