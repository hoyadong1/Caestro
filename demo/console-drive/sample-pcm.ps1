param([string]$OutCsv, [string]$StopFile)
# Logs the app's captured PCM size against host wall clock (for audio->wall alignment).
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
"wall_ms,pcm_bytes" | Set-Content $OutCsv -Encoding ascii
while (-not (Test-Path $StopFile)) {
    $b = "$(& $adb shell run-as com.drivesoundtrack.app --user 10 stat -c %s files/live-demo.wav 2>$null)".Trim()
    $w = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($b -match '^\d+$') { "$w,$b" | Add-Content $OutCsv -Encoding ascii }
    Start-Sleep -Milliseconds 300
}
