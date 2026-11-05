param([string]$OutputDirectory = (Join-Path $env:USERPROFILE 'Downloads\drive-soundtrack-screen-audio-v062'))

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
$segments = [System.Collections.Generic.List[object]]::new()
$part = 0
function StartScreen {
    $script:part++
    $start = $clock.Elapsed.TotalSeconds
    $remote = "/sdcard/drive-v062-screen-$script:part.mp4"
    $process = Start-Process -FilePath $adb -ArgumentList @('shell','screenrecord','--time-limit','180',
        '--size','1280x720','--bit-rate','2500000',$remote) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $OutputDirectory "screenrecord-$script:part.stdout.log") `
        -RedirectStandardError (Join-Path $OutputDirectory "screenrecord-$script:part.stderr.log")
    $script:segments.Add([pscustomobject]@{ part=$script:part; start_wall=[Math]::Round($start,3);
        end_wall=0.0; remote=$remote; process=$process })
}
function StopScreen {
    $screenPid = & $adb shell pidof screenrecord
    if ($screenPid) { & $adb shell kill -2 $screenPid | Out-Null }
    $last = $script:segments[$script:segments.Count-1]
    $last.process.WaitForExit(10000) | Out-Null
    $last.end_wall = [Math]::Round($clock.Elapsed.TotalSeconds,3)
}
StartScreen
Start-Sleep -Seconds 3
if ($segments[0].process.HasExited) { throw 'screenrecord exited early' }

Service 'ARM_DEBUG_CAPTURE'
$playWall = $clock.Elapsed.TotalSeconds
Service 'PLAY'
$rows = [System.Collections.Generic.List[object]]::new()
$audioSeconds = 0.0
$lastAudioSeconds = -1.0
$lastProgressWall = $clock.Elapsed.TotalSeconds
try {
    while ($audioSeconds -lt 85.0) {
        $wall = $clock.Elapsed.TotalSeconds
        if ($wall -gt 500) { throw 'PCM capture exceeded 500 seconds of wall time.' }
        if ($wall -ge $part * 110) {
            StopScreen
            StartScreen
        }
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
    StopScreen
    $clock.Stop()
}

$rows | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'sync.csv') -NoTypeInformation -Encoding utf8
[pscustomobject]@{
    screen_start_wall = [Math]::Round($screenStartWall, 3)
    play_command_wall = [Math]::Round($playWall, 3)
    stop_wall = [Math]::Round($clock.Elapsed.TotalSeconds, 3)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'sync-meta.json') -Encoding utf8
$segments | Select-Object part,start_wall,end_wall,remote | ConvertTo-Json | `
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'segments.json') -Encoding utf8

for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $signature = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav
    if ($signature -eq 'RIFF') { break }
    Start-Sleep -Seconds 1
}
if ($signature -ne 'RIFF') { throw 'WAV capture did not finish.' }
& python "$PSScriptRoot/pull-journey-v042.py" $OutputDirectory
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
foreach ($segment in $segments) {
    & $adb pull $segment.remote (Join-Path $OutputDirectory "screen-part$($segment.part).mp4") | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Host "Captured $([Math]::Round($audioSeconds,2)) seconds of app PCM and $($segments.Count) screen segments with $($rows.Count) sync points."
