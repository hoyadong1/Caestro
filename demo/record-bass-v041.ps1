param([string]$OutputDirectory = 'C:\Users\as864\Downloads\drive-soundtrack-bass-v041')

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\as864\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$base = 'http://127.0.0.1:8088'
$speeds = @(0, 10, 20, 35, 55, 80, 110, 130)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $adb forward tcp:8088 tcp:8088 | Out-Null

function Heartbeat {
    Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=drive-soundtrack-bass-study" -TimeoutSec 3 | Out-Null
}
function Hold([int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Heartbeat
        Start-Sleep -Seconds 1
    }
}
function Service([string]$action) {
    & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null
}
function SetSpeed([int]$kmh) {
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=$kmh&brake=0&steering=0" -TimeoutSec 3 | Out-Null
}

Service 'PAUSE'
Heartbeat
SetSpeed 0
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=Off" -TimeoutSec 3 | Out-Null
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null
& $adb shell pm grant --user 10 com.drivesoundtrack.app android.car.permission.CAR_SPEED | Out-Null
Service 'RECONNECT_SPEED'
Hold 3
& $adb shell input tap 720 110 | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_PRESET --ei preset 4 | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled true | Out-Null
Hold 2
$storedPreset = ''
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $storedPreset = (& $adb shell run-as com.drivesoundtrack.app --user 10 cat shared_prefs/soundtrack.xml) -join "`n"
    if ($storedPreset -match 'name="preset" value="4"') { break }
    Start-Sleep -Seconds 1
}
if ($storedPreset -notmatch 'name="preset" value="4"') { throw 'Circuit preset was not selected; refusing to record.' }

foreach ($kmh in $speeds) {
    $name = 'bass-{0:D3}' -f $kmh
    SetSpeed $kmh
    Hold 2
    $video = Join-Path $OutputDirectory "$name.webm"
    & $adb emu screenrecord start --size 1920x1080 --fps 12 --bit-rate 4M $video | Out-Null
    Hold 2
    Service 'ARM_DEBUG_CAPTURE'
    Service 'PLAY'
    Write-Host "Recording isolated bass at $kmh km/h"
    Hold 12
    Service 'PAUSE'
    $signature = ''
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $signature = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav
        if ($signature -eq 'RIFF') { break }
        Hold 1
    }
    if ($signature -ne 'RIFF') { throw "Audio capture did not finish for $name" }
    & $adb emu screenrecord stop | Out-Null
    & $adb shell run-as com.drivesoundtrack.app --user 10 cp files/live-demo.wav "files/$name.wav" | Out-Null
}

& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled false | Out-Null
SetSpeed 0
& python "$PSScriptRoot/pull-bass-v041.py" $OutputDirectory
Write-Host "Saved isolated bass captures to $OutputDirectory."
