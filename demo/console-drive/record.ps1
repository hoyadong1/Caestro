param([string]$Out, [double]$Duration = 100)
# D-gear drive performed on the Pleos Virtual Console page: an in-page driver works the console's
# ACCEL/BRAKE pedals to follow the in-app scenario speed curve; the console's own physics produces the
# vehicle speed. The app runs its normal vehicle-speed path (no in-app scenario).
# Records emulator screen (host-side), console page (CDP screenshots), app PCM (debug capture).
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cdp.ps1')
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$b = 'http://127.0.0.1:8088'
New-Item -ItemType Directory -Force $Out | Out-Null
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Svc([string]$action) { & $adb shell am start-foreground-service --user 10 -n com.drivesoundtrack.app/.SoundtrackService -a "com.drivesoundtrack.$action" | Out-Null }
function Ev([string]$js) { ((Cdp 'Runtime.evaluate' (@{ expression = $js; returnByValue = $true } | ConvertTo-Json -Compress)) | ConvertFrom-Json).result.result.value }
function KillChrome { Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | Where-Object { $_.CommandLine -match 'remote-debugging-port=9333' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }

# --- vehicle: D, stopped
Invoke-RestMethod -Method Post "$b/browser/session?client=drive-soundtrack-rec" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Put "$b/vehicle/controls?speed=0&brake=0&steering=0" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Put "$b/vehicle/gear?gear=Drive" -TimeoutSec 5 | Out-Null
Svc 'PAUSE'
& $adb shell run-as com.drivesoundtrack.app --user 10 rm -f files/live-demo.wav 2>$null | Out-Null
& $adb shell am start --user 10 -n com.drivesoundtrack.app/.MainActivity | Out-Null

# --- console page
KillChrome
$stop = Join-Path $Out 'stop.flag'; Remove-Item $stop -ErrorAction SilentlyContinue
Start-Process $chrome -ArgumentList @('--headless=new','--use-angle=d3d11','--remote-debugging-port=9333',"--user-data-dir=$(Join-Path $Out 'chrome-profile')",'--window-size=1600,1000',"$b/") | Out-Null
Start-Sleep 7
Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | Where-Object { $_.CommandLine -match 'remote-debugging-port=9333' } | ForEach-Object { try { (Get-Process -Id $_.ProcessId).PriorityClass = 'BelowNormal' } catch {} }
Connect-Cdp 9333
$gear = Ev 'currentGear'; if ($gear -ne 'Drive') { throw "Console gear is $gear" }
$cap = Start-Process powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'capture-screencast.ps1'),'-OutDir',(Join-Path $Out 'console'),'-StopFile',$stop) -PassThru -WindowStyle Hidden

# --- emulator screen (host-side recorder)
$screenStart = Now
& $adb emu screenrecord start --time-limit 180 --size 1280x720 --bit-rate 12000000 --fps 30 (Join-Path $Out 'emulator.webm') | Out-Null
Start-Sleep 2

# --- play (normal path) + PCM capture + sampler
Svc 'ARM_DEBUG_CAPTURE'
$playStart = Now
Svc 'PLAY'
$sampler = Start-Process powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'sample-pcm.ps1'),'-OutCsv',(Join-Path $Out 'pcm.csv'),'-StopFile',$stop) -PassThru -WindowStyle Hidden
Ev "window.__driveDuration=$Duration" | Out-Null
$driveStart = Now
Write-Host (Ev (Get-Content (Join-Path $PSScriptRoot 'driver.js') -Raw))
try {
    while (-not (Ev 'window.__drive.done')) {
        if (((Now) - $driveStart) / 1000 -gt $Duration + 30) { throw 'driver did not finish' }
        Start-Sleep 1
    }
    Start-Sleep 3
} finally {
    $log = Ev 'JSON.stringify(window.__drive ? window.__drive.log : [])'
    Ev "pedals.accelerator=false;pedals.brake=false" | Out-Null
    Svc 'PAUSE'
    $stopWall = Now
    & $adb emu screenrecord stop | Out-Null
    New-Item $stop -ItemType File | Out-Null
    $cap.WaitForExit(15000) | Out-Null; $sampler.WaitForExit(15000) | Out-Null
    KillChrome
}
$log | Set-Content (Join-Path $Out 'drive.json') -Encoding utf8
@{ screen_start_ms = $screenStart; play_start_ms = $playStart; drive_start_ms = $driveStart; stop_ms = $stopWall } | ConvertTo-Json | Set-Content (Join-Path $Out 'meta.json') -Encoding utf8
for ($k = 0; $k -lt 30; $k++) { $sig = & $adb shell run-as com.drivesoundtrack.app --user 10 head -c 4 files/live-demo.wav; if ($sig -eq 'RIFF') { break }; Start-Sleep 1 }
cmd /c "`"$adb`" exec-out run-as com.drivesoundtrack.app --user 10 cat files/live-demo.wav > `"$(Join-Path $Out 'app-audio.wav')`""
Write-Host "console=$((Get-ChildItem (Join-Path $Out 'console')).Count) pcm samples=$((Import-Csv (Join-Path $Out 'pcm.csv')).Count)"
