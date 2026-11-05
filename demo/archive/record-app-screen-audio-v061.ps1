param([string]$OutputDirectory = (Join-Path $env:USERPROFILE 'Downloads\drive-soundtrack-screen-audio-v061'))

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Service([string]$action) {
    & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Service action $action failed" }
}

Service 'PAUSE'
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_PRESET --ei preset 0 | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled false | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_SPEED_JOURNEY --ez enabled true | Out-Null
& $adb shell run-as com.drivesoundtrack.app --user 10 rm -f files/live-demo.wav | Out-Null

$clock = [System.Diagnostics.Stopwatch]::StartNew()
$screenStartWall = $clock.Elapsed.TotalSeconds
$screen = Start-Process -FilePath $adb -ArgumentList @('shell','screenrecord','--time-limit','180','--size','1280x720',
    '--bit-rate','2500000','/sdcard/drive-v061-screen.mp4') -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $OutputDirectory 'screenrecord.stdout.log') `
    -RedirectStandardError (Join-Path $OutputDirectory 'screenrecord.stderr.log')
Start-Sleep -Seconds 3
if ($screen.HasExited) { throw "screenrecord exited early: $(Get-Content (Join-Path $OutputDirectory 'screenrecord.stderr.log') -Raw)" }

Service 'ARM_DEBUG_CAPTURE'
$playWall = $clock.Elapsed.TotalSeconds
Service 'PLAY'
$rows = [System.Collections.Generic.List[object]]::new()
$audioSeconds = 0.0
$lastAudioSeconds = -1.0
$lastProgressWall = $clock.Elapsed.TotalSeconds
try {
    while ($audioSeconds -lt 32.8) {
        $wall = $clock.Elapsed.TotalSeconds
        if ($wall -gt 170) { throw 'PCM capture exceeded 170 seconds of wall time.' }
        $ErrorActionPreference = 'Continue'
        $bytes = & $adb shell run-as com.drivesoundtrack.app --user 10 stat -c %s files/live-demo.wav 2>$null
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { Start-Sleep -Milliseconds 400; continue }
        $audioSeconds = [Math]::Max(0.0, ([double]$bytes - 44.0) / 176400.0)
        if ($audioSeconds -gt $lastAudioSeconds) {
            $rows.Add([pscustomobject]@{ wall_seconds = [Math]::Round($wall, 3); audio_seconds = [Math]::Round($audioSeconds, 3) })
            $lastAudioSeconds = $audioSeconds
            $lastProgressWall = $wall
        } elseif ($wall - $lastProgressWall -gt 15) { throw 'PCM output stalled.' }
        Start-Sleep -Milliseconds 350
    }
} finally {
    Service 'PAUSE'
    & $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_SPEED_JOURNEY --ez enabled false | Out-Null
    $screenPid = & $adb shell pidof screenrecord
    if ($screenPid) { & $adb shell kill -2 $screenPid | Out-Null }
    $clock.Stop()
}

$rows | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'sync.csv') -NoTypeInformation -Encoding utf8
[pscustomobject]@{
    screen_start_wall = [Math]::Round($screenStartWall, 3)
    play_command_wall = [Math]::Round($playWall, 3)
    stop_wall = [Math]::Round($clock.Elapsed.TotalSeconds, 3)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'sync-meta.json') -Encoding utf8

for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $signature = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav
    if ($signature -eq 'RIFF') { break }
    Start-Sleep -Seconds 1
}
if ($signature -ne 'RIFF') { throw 'WAV capture did not finish.' }
& python "$PSScriptRoot/pull-journey-v042.py" $OutputDirectory
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $adb pull /sdcard/drive-v061-screen.mp4 (Join-Path $OutputDirectory 'screen.mp4') | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Captured $([Math]::Round($audioSeconds,2)) seconds of app PCM and screen video with $($rows.Count) sync points."
