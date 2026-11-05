$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "Missing: $adb" }
$devices = & $adb devices
if ($devices -notmatch 'emulator-\d+\s+device') { throw 'Start the Pleos emulator first with .\demo\start-interactive.ps1' }

# Reset the synthetic scenario, then start the normal vehicle-speed path.
& $adb shell am force-stop --user 10 com.drivesoundtrack.app | Out-Null
& $adb shell pm grant --user 10 com.drivesoundtrack.app android.car.permission.CAR_SPEED | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not grant CAR_SPEED to the installed app.' }
& $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.PLAY | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Normal media playback did not start.' }
& $adb forward tcp:8088 tcp:8088 | Out-Null

Write-Host 'Normal playback started. Open http://127.0.0.1:8088/ and set D, then vary speed.'
Write-Host 'The app screen may be hidden in D; listen for the soundtrack and check the media session.'
Write-Host 'For speed callback logs from the debug APK: adb logcat -s DriveSoundtrack:I'
