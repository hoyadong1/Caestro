param([string]$OutputDirectory = 'C:\Users\as864\Downloads\drive-soundtrack-bass-journey-v042')

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\as864\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$base = 'http://127.0.0.1:8088'
$client = 'drive-soundtrack-continuous-bass'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $adb forward tcp:8088 tcp:8088 | Out-Null

function Heartbeat {
    Invoke-RestMethod -Method Post -Uri "$base/browser/session?client=$client" -TimeoutSec 3 | Out-Null
}
function Service([string]$action) {
    & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null
}
function Gear([string]$value) {
    $state = Invoke-RestMethod -Method Put -Uri "$base/vehicle/gear?gear=$value" -TimeoutSec 3
    if ($state.actual.value -ne $value) { throw "Gear did not change to $value" }
}
function Profile([double]$t) {
    if ($t -lt 10) { return @(0.0, 0.0, 'D READY') }
    if ($t -lt 24) { $speed = (($t - 10) / 14) * 40; return @($speed, 0.0, 'CITY ACCEL') }
    if ($t -lt 31) { $speed = 40 + (($t - 24) / 7) * 5; return @($speed, 0.0, 'CITY CRUISE') }
    if ($t -lt 45) { $speed = 45 + (($t - 31) / 14) * 40; return @($speed, 0.0, 'OPEN ROAD ACCEL') }
    if ($t -lt 52) { $speed = 85 + (($t - 45) / 7) * 3; return @($speed, 0.0, 'OPEN ROAD CRUISE') }
    if ($t -lt 65) { $speed = 88 - (($t - 52) / 13) * 33; return @($speed, 0.0, 'COAST') }
    if ($t -lt 78) { $speed = 55 - (($t - 65) / 13) * 55; return @($speed, 50.0, 'BRAKE TO STOP') }
    return @(0.0, 0.0, 'STOP')
}

Service 'PAUSE'
Heartbeat
Gear 'Park'
Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 3 | Out-Null
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null
& $adb shell pm grant --user 10 com.drivesoundtrack.app android.car.permission.CAR_SPEED | Out-Null
Service 'RECONNECT_SPEED'
Start-Sleep -Seconds 3
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_PRESET --ei preset 4 | Out-Null
& $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled true | Out-Null
Start-Sleep -Seconds 2
$storedPreset = (& $adb shell run-as com.drivesoundtrack.app --user 10 cat shared_prefs/soundtrack.xml) -join "`n"
if ($storedPreset -notmatch 'name="preset" value="4"') { throw 'Circuit preset was not selected.' }

$rows = [System.Collections.Generic.List[object]]::new()
$timer = [System.Diagnostics.Stopwatch]::StartNew()
$lastHeartbeat = -10.0
$driveSelected = $false
Service 'ARM_DEBUG_CAPTURE'
Service 'PLAY'
try {
    while ($timer.Elapsed.TotalSeconds -lt 82.0) {
        $t = $timer.Elapsed.TotalSeconds
        if ($t -ge 4.0 -and !$driveSelected) {
            Gear 'Drive'
            $driveSelected = $true
            Write-Host 'D gear selected; continuous drive profile running'
        }
        if ($t - $lastHeartbeat -ge 2.0) {
            Heartbeat
            $lastHeartbeat = $t
        }
        $point = Profile $t
        $speed = [int][Math]::Round([double]$point[0])
        $brake = [int][Math]::Round([double]$point[1])
        $phase = [string]$point[2]
        Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=$speed&brake=$brake&steering=0" -TimeoutSec 3 | Out-Null
        $console = Invoke-RestMethod -Uri "$base/vehicle/controls" -TimeoutSec 3
        $rows.Add([pscustomobject]@{
            seconds = [Math]::Round($t, 3)
            commanded_kmh = $speed
            console_kmh = $console.speed_kph
            brake_percent = $brake
            gear_command = if ($driveSelected) { 'Drive' } else { 'Park' }
            phase = $phase
        })
        Start-Sleep -Milliseconds 250
    }
} finally {
    Service 'PAUSE'
    $timer.Stop()
    Invoke-RestMethod -Method Put -Uri "$base/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 3 | Out-Null
    Gear 'Park'
    & $adb shell am start-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a com.drivesoundtrack.DEBUG_BASS_SOLO --ez enabled false | Out-Null
}

$rows | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'journey.csv') -NoTypeInformation -Encoding utf8
$signature = ''
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $signature = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav
    if ($signature -eq 'RIFF') { break }
    Start-Sleep -Seconds 1
}
if ($signature -ne 'RIFF') { throw 'Audio capture did not finish.' }
& python "$PSScriptRoot/pull-journey-v042.py" $OutputDirectory
Write-Host "Saved one continuous bass performance and $($rows.Count) speed updates."
