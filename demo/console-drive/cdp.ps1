# Minimal CDP client (dot-source). Connect-Cdp <port>; Cdp <method> <paramsJson>
function Connect-Cdp([int]$Port) {
    $t = (Invoke-RestMethod "http://127.0.0.1:$Port/json") | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
    $script:cdpWs = New-Object System.Net.WebSockets.ClientWebSocket
    $script:cdpWs.ConnectAsync([Uri]$t.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait()
    $script:cdpBuf = New-Object byte[] (4MB); $script:cdpId = 1000
}
function Cdp([string]$method, [string]$params = '{}') {
    $script:cdpId++
    $msg = [Text.Encoding]::UTF8.GetBytes("{`"id`":$script:cdpId,`"method`":`"$method`",`"params`":$params}")
    $script:cdpWs.SendAsync([ArraySegment[byte]]::new($msg), 'Text', $true, [Threading.CancellationToken]::None).Wait()
    while ($true) {
        $ms = New-Object IO.MemoryStream
        do { $r = $script:cdpWs.ReceiveAsync([ArraySegment[byte]]::new($script:cdpBuf), [Threading.CancellationToken]::None).Result; $ms.Write($script:cdpBuf, 0, $r.Count) } while (-not $r.EndOfMessage)
        $text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
        if ($text -match "^\{`"id`":$script:cdpId,") { return $text }
    }
}
