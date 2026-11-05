param([string]$OutDir, [string]$StopFile, [int]$Port = 9333)
# Streams the console page via CDP Page.startScreencast. File name = host ms at receipt.
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$target = (Invoke-RestMethod "http://127.0.0.1:$Port/json") | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.ConnectAsync([Uri]$target.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait()
$buf = New-Object byte[] (4MB); $id = 1
function Send([string]$s) { $m = [Text.Encoding]::UTF8.GetBytes($s); $ws.SendAsync([ArraySegment[byte]]::new($m), 'Text', $true, [Threading.CancellationToken]::None).Wait() }
Send '{"id":1,"method":"Page.startScreencast","params":{"format":"jpeg","quality":85,"maxWidth":1600,"maxHeight":1000,"everyNthFrame":1}}'
$ms = New-Object IO.MemoryStream; $pending = $null
while (-not (Test-Path $StopFile)) {
    if (-not $pending) { $pending = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), [Threading.CancellationToken]::None) }
    if (-not $pending.Wait(500)) { continue }
    $r = $pending.Result; $pending = $null
    $ms.Write($buf, 0, $r.Count)
    if (-not $r.EndOfMessage) { continue }
    $text = [Text.Encoding]::UTF8.GetString($ms.ToArray()); $ms.SetLength(0)
    if ($text -match '"method":"Page.screencastFrame"') {
        $t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if ($text -match '"sessionId":(\d+)') { $id++; Send "{`"id`":$id,`"method`":`"Page.screencastFrameAck`",`"params`":{`"sessionId`":$($matches[1])}}" }
        if ($text -match '"data":"([^"]+)"') { [IO.File]::WriteAllBytes((Join-Path $OutDir "$t.jpg"), [Convert]::FromBase64String($matches[1])) }
    }
}
$ws.Dispose()
