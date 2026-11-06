param([string]$Out, [double]$Kmh = 60)
# Steady speed in D while the wheel goes straight -> left lane change -> left curve -> straight ->
# right curve -> straight, set through the Virtual Console REST API (no console page open, so the
# values hold). Logs each leg against the app's audio clock for captions.
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$b = 'http://127.0.0.1:8088'
New-Item -ItemType Directory -Force $Out | Out-Null
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Svc([string]$a) { & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$a" | Out-Null }
function Put([string]$path) { Invoke-RestMethod -Method Post "$b/browser/session?client=drive-soundtrack-rec" -TimeoutSec 5 | Out-Null; Invoke-RestMethod -Method Put "$b$path" -TimeoutSec 5 | Out-Null }
function AudioSeconds { $x = "$(& $adb shell run-as com.drivesoundtrack.app --user 10 stat -c %s files/live-demo.wav 2>$null)".Trim(); if ($x -match '^\d+$') { ([int64]$x - 44) / 176400 } else { 0 } }
$legs = @(@(8, 0, 'straight'), @(6, -25, 'lane-left'), @(6, 0, 'straight'), @(14, -70, 'curve-left'),
          @(8, 0, 'straight'), @(14, 90, 'curve-right'), @(8, 0, 'straight'))

Put '/vehicle/gear?gear=Park'; Put "/vehicle/controls?speed=0&brake=0&steering=0"
Svc 'PAUSE'; & $adb shell run-as com.drivesoundtrack.app --user 10 rm -f files/live-demo.wav 2>$null | Out-Null
cmd /c "`"$adb`" shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity >nul 2>&1"
Start-Sleep 4; Svc 'PAUSE'
Put '/vehicle/gear?gear=Drive'
$stop = Join-Path $Out 'stop.flag'; Remove-Item $stop -ErrorAction SilentlyContinue
$screenStart = Now
& $adb emu screenrecord start --time-limit 180 --size 1920x1080 --bit-rate 16000000 --fps 30 (Join-Path $Out 'emulator.webm') | Out-Null
Start-Sleep 2
Svc 'ARM_DEBUG_CAPTURE'; $playStart = Now; Svc 'PLAY'
$sampler = Start-Process powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'sample-pcm.ps1'),'-OutCsv',(Join-Path $Out 'pcm.csv'),'-StopFile',$stop) -PassThru -WindowStyle Hidden
# Bring the car up to speed before the route starts.
foreach ($v in 15, 30, 45, $Kmh, $Kmh) { Put "/vehicle/controls?speed=$v&brake=0&steering=0"; Start-Sleep 1 }
$events = New-Object System.Collections.ArrayList
foreach ($leg in $legs) {
    $null = $events.Add([pscustomobject]@{ audio_s = [math]::Round((AudioSeconds), 2); label = $leg[2]; degrees = $leg[1] })
    # Legs are timed on the audio clock so captions and sound line up in the final video.
    $target = (AudioSeconds) + $leg[0]
    while ((AudioSeconds) -lt $target) { Put "/vehicle/controls?speed=$Kmh&brake=0&steering=$($leg[1])"; Start-Sleep -Milliseconds 700 }
}
$null = $events.Add([pscustomobject]@{ audio_s = [math]::Round((AudioSeconds), 2); label = 'end'; degrees = 0 })
Svc 'PAUSE'; $stopWall = Now
& $adb emu screenrecord stop | Out-Null
New-Item $stop -ItemType File | Out-Null; $sampler.WaitForExit(15000) | Out-Null
Put "/vehicle/controls?speed=0&brake=0&steering=0"; Put '/vehicle/gear?gear=Park'
$events | Export-Csv (Join-Path $Out 'events.csv') -NoTypeInformation -Encoding utf8
@{ screen_start_ms = $screenStart; play_start_ms = $playStart; stop_ms = $stopWall } | ConvertTo-Json | Set-Content (Join-Path $Out 'meta.json') -Encoding utf8
for ($k = 0; $k -lt 30; $k++) { $sig = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav; if ($sig -eq 'RIFF') { break }; Start-Sleep 1 }
cmd /c "`"$adb`" exec-out run-as com.drivesoundtrack.app --user 10 cat files/live-demo.wav > `"$(Join-Path $Out 'app-audio.wav')`""
New-Item -ItemType Directory -Force (Join-Path $Out 'console') | Out-Null
Write-Host "legs=$($events.Count - 1) audio=$([math]::Round((Get-Item (Join-Path $Out 'app-audio.wav')).Length / 176400, 1)) s"
