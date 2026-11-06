param([string]$Out, [double]$Duration = 104)
# Parked (P) in-app scenario: records the emulator screen (host side) and the app's own PCM.
# The scenario speed is synthetic and driven by the audio clock, so chapter times stay exact.
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$b = 'http://127.0.0.1:8088'
New-Item -ItemType Directory -Force $Out | Out-Null
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Svc([string]$action) { & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null }

Invoke-RestMethod -Method Post "$b/browser/session?client=drive-soundtrack-rec" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Put "$b/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Put "$b/vehicle/gear?gear=Park" -TimeoutSec 5 | Out-Null
Svc 'PAUSE'
& $adb shell run-as com.drivesoundtrack.app --user 10 rm -f files/live-demo.wav 2>$null | Out-Null
cmd /c "`"$adb`" shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity >nul 2>&1"
Start-Sleep 3

$stop = Join-Path $Out 'stop.flag'; Remove-Item $stop -ErrorAction SilentlyContinue
$screenStart = Now
& $adb emu screenrecord start --time-limit 180 --size 1920x1080 --bit-rate 16000000 --fps 30 (Join-Path $Out 'emulator.webm') | Out-Null
Start-Sleep 2
Svc 'ARM_DEBUG_CAPTURE'
$playStart = Now
Svc 'SCENARIO_TOGGLE'
$sampler = Start-Process powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'sample-pcm.ps1'),'-OutCsv',(Join-Path $Out 'pcm.csv'),'-StopFile',$stop) -PassThru -WindowStyle Hidden
# Wait on the audio clock, not wall time: the emulator renders audio slower than real time.
while ($true) {
    Start-Sleep 2
    $bytes = "$(& $adb shell run-as com.drivesoundtrack.app --user 10 stat -c %s files/live-demo.wav 2>$null)".Trim()
    if ($bytes -match '^\d+$' -and ([int64]$bytes - 44) / 176400 -ge $Duration) { break }
    if (((Now) - $playStart) / 1000 -gt $Duration * 2) { break }
}
Svc 'SCENARIO_TOGGLE'
$stopWall = Now
& $adb emu screenrecord stop | Out-Null
New-Item $stop -ItemType File | Out-Null
$sampler.WaitForExit(15000) | Out-Null
@{ screen_start_ms = $screenStart; play_start_ms = $playStart; stop_ms = $stopWall } | ConvertTo-Json | Set-Content (Join-Path $Out 'meta.json') -Encoding utf8
for ($k = 0; $k -lt 30; $k++) { $sig = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav; if ($sig -eq 'RIFF') { break }; Start-Sleep 1 }
cmd /c "`"$adb`" exec-out run-as com.drivesoundtrack.app --user 10 cat files/live-demo.wav > `"$(Join-Path $Out 'app-audio.wav')`""
New-Item -ItemType Directory -Force (Join-Path $Out 'console') | Out-Null
Write-Host "pcm samples=$((Import-Csv (Join-Path $Out 'pcm.csv')).Count)"
