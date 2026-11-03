param([string]$Output = "$PSScriptRoot/../docs/drive-soundtrack-demo-v03-raw.mp4")

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\as864\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$scrcpy = 'C:\Users\as864\Tools\scrcpy-win64-v5.0.1\scrcpy.exe'
$base = 'http://127.0.0.1:8088'
& $adb forward tcp:8088 tcp:8088 | Out-Null

function Heartbeat {
    Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=drive-soundtrack-demo" -TimeoutSec 3 | Out-Null
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
function Mark([string]$label) {
    Write-Host ("{0,5:n1}s {1}" -f $timer.Elapsed.TotalSeconds, $label)
}

Heartbeat
Control 'speed=0&brake=0&steering=0'
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=Off" -TimeoutSec 3 | Out-Null
& $adb shell am start -n com.drivesoundtrack.app/.MainActivity | Out-Null
Start-Sleep -Seconds 2
$recording = Start-Process -FilePath $scrcpy -ArgumentList @(
    '--no-window', '--no-control', '--require-audio', '--audio-codec=aac',
    '--max-size=1920', '--video-bit-rate=8M', '--time-limit=70',
    "--record=$Output"
) -WindowStyle Hidden -PassThru
$timer = [Diagnostics.Stopwatch]::StartNew()
Mark 'Circuit, stationary'
Hold 8
Control 'speed=35&brake=0&steering=0'
Mark 'speed 35 km/h'
Hold 10
Control 'speed=55&brake=70&steering=0'
Mark 'speed 55 km/h, brake 70%'
Hold 10
Control 'speed=36&brake=0&steering=150'
Mark 'steering 150 deg'
Hold 9
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=BlinkLeft" -TimeoutSec 3 | Out-Null
Mark 'left turn signal'
Hold 9
Invoke-RestMethod -Method Put -Uri "$base/vehicle/turn-signal?mode=Off" -TimeoutSec 3 | Out-Null
Control 'speed=0&brake=0&steering=0'
Mark 'stationary'
Hold 4
& $adb shell input tap 2000 320 | Out-Null
Mark 'select Pulse'
Hold 16
$recording.WaitForExit()
Mark 'recording complete'
