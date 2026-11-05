param([string]$OutputDirectory = (Join-Path $env:USERPROFILE 'Downloads\drive-soundtrack-layers-v050'))

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function Service([string]$action) {
    & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Service action $action failed" }
}
function Profile([double]$t) {
    if ($t -lt 2) { return @(0.0, 'READY') }
    if ($t -lt 6) { $speed = ($t - 2) * 10; return @($speed, 'CITY ACCEL') }
    if ($t -lt 7) { return @(40.0, 'CITY CRUISE') }
    if ($t -lt 11) { $speed = 40 + ($t - 7) * 10; return @($speed, 'ROAD ACCEL') }
    if ($t -lt 12) { return @(80.0, 'ROAD CRUISE') }
    if ($t -lt 17) { $speed = 80 + ($t - 12) * 10; return @($speed, 'HIGHWAY ACCEL') }
    if ($t -lt 19) { return @(130.0, 'HIGHWAY CRUISE') }
    if ($t -lt 23) { $speed = 130 - ($t - 19) * 16.25; return @($speed, 'COAST') }
    if ($t -lt 27) { $speed = 65 - ($t - 23) * 16.25; return @($speed, 'BRAKE TO STOP') }
    return @(0.0, 'STOP')
}

Service 'PAUSE'
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_PRESET --ei preset 0 | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled false | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_SPEED_JOURNEY --ez enabled true | Out-Null
& $adb shell run-as com.drivesoundtrack.app --user 10 rm -f files/live-demo.wav | Out-Null
Service 'ARM_DEBUG_CAPTURE'
Service 'PLAY'
Start-Sleep -Seconds 2

$rows = [System.Collections.Generic.List[object]]::new()
$clock = [System.Diagnostics.Stopwatch]::StartNew()
$audioSeconds = 0.0
$lastAudioSeconds = -1.0
$lastProgressWall = 0.0
try {
    while ($audioSeconds -lt 28.5) {
        $wall = $clock.Elapsed.TotalSeconds
        if ($wall -gt 180) { throw 'Emulator did not produce 28.5 seconds of PCM within three minutes.' }
        $bytes = & $adb shell run-as com.drivesoundtrack.app --user 10 stat -c %s files/live-demo.wav
        if ($LASTEXITCODE -ne 0) {
            if ($wall -lt 30) { Start-Sleep -Milliseconds 400; continue }
            throw 'Cannot read active PCM capture size.'
        }
        $audioSeconds = [Math]::Max(0.0, ([double]$bytes - 44.0) / 176400.0)
        if ($audioSeconds -le $lastAudioSeconds) {
            if ($wall - $lastProgressWall -gt 15) { throw 'PCM output stalled.' }
            Start-Sleep -Milliseconds 400
            continue
        }
        $lastAudioSeconds = $audioSeconds
        $lastProgressWall = $wall
        $point = Profile $audioSeconds
        $rows.Add([pscustomobject]@{
            seconds = [Math]::Round($audioSeconds, 3)
            speed_kmh = [Math]::Round([double]$point[0], 2)
            phase = [string]$point[1]
            input_source = 'app_debug_pcm_clock'
            wall_seconds = [Math]::Round($wall, 3)
        })
        Start-Sleep -Milliseconds 400
    }
} finally {
    $clock.Stop()
    Service 'PAUSE'
    & $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_SPEED_JOURNEY --ez enabled false | Out-Null
    & $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled false | Out-Null
}

$rows | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'journey.csv') -NoTypeInformation -Encoding utf8
$signature = ''
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $signature = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav
    if ($signature -eq 'RIFF') { break }
    Start-Sleep -Seconds 1
}
if ($signature -ne 'RIFF') { throw 'WAV capture did not finish.' }
& python "$PSScriptRoot/pull-journey-v042.py" $OutputDirectory
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Saved $($rows.Count) PCM-clock speed points over $([Math]::Round($clock.Elapsed.TotalSeconds,1)) wall seconds."
