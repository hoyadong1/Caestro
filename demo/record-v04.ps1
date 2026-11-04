param([string]$OutputDirectory = 'C:\Users\as864\Downloads\drive-soundtrack-live-scenes')

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\as864\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$base = 'http://127.0.0.1:8088'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $adb forward tcp:8088 tcp:8088 | Out-Null

function Heartbeat {
    Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=drive-soundtrack-live-demo" -TimeoutSec 3 | Out-Null
}
function Control([string]$query) {
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?$query" -TimeoutSec 3 | Out-Null
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
function Scene([string]$name, [string]$controls, [string]$turn, [string]$nextControls = '') {
    Control $controls
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=$turn" -TimeoutSec 3 | Out-Null
    Hold 2
    $video = Join-Path $OutputDirectory "$name.webm"
    & $adb emu screenrecord start --size 1920x1080 --fps 12 --bit-rate 4M $video | Out-Null
    Hold 2
    Service 'ARM_DEBUG_CAPTURE'
    Service 'PLAY'
    Write-Host "Recording $name"
    Hold 5
    if ($nextControls) { Control $nextControls }
    Hold 5
    Service 'PAUSE'
    Hold 1
    & $adb emu screenrecord stop | Out-Null
    & $adb shell run-as com.drivesoundtrack.app --user 10 cp files/live-demo.wav "files/$name.wav" | Out-Null
    Write-Host "Finished $name"
}

Service 'PAUSE'
Heartbeat
Control 'speed=0&brake=0&steering=0'
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=Off" -TimeoutSec 3 | Out-Null
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null
& $adb shell pm grant --user 10 com.drivesoundtrack.app android.car.permission.CAR_SPEED | Out-Null
Service 'RECONNECT_SPEED'
Hold 3
& $adb shell input tap 720 110 | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.PRESET --ei preset 4 | Out-Null
Hold 2

Scene '01-circuit' 'speed=0&brake=0&steering=0' 'Off'
Scene '02-speed' 'speed=15&brake=0&steering=0' 'Off' 'speed=35&brake=0&steering=0'
Scene '03-cruise' 'speed=55&brake=0&steering=0' 'Off' 'speed=70&brake=0&steering=0'
Scene '04-brake' 'speed=70&brake=70&steering=0' 'Off' 'speed=45&brake=0&steering=0'
Scene '05-steering' 'speed=40&brake=0&steering=-120' 'Off' 'speed=40&brake=0&steering=-300'
Scene '06-left' 'speed=40&brake=0&steering=-240' 'BlinkLeft'
Scene '07-right' 'speed=45&brake=0&steering=240' 'BlinkRight'
Scene '08-combined' 'speed=65&brake=0&steering=180' 'BlinkRight' 'speed=55&brake=55&steering=300'
Control 'speed=0&brake=0&steering=0'
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=Off" -TimeoutSec 3 | Out-Null
Hold 2
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.PRESET --ei preset 0 | Out-Null
Scene '09-pulse' 'speed=0&brake=0&steering=0' 'Off'

& python "$PSScriptRoot/pull-v04-audio.py" $OutputDirectory
Write-Host "Saved nine scene recordings and app PCM WAVs to $OutputDirectory."
