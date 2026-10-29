$ErrorActionPreference = 'Stop'
# 6.1 play one round: SSE game/start, then re-verify isolation guards hold with events present
$base = 'http://localhost:8080'
$email = 'browse-shot-1026114447@test.local'
$gameStateId = '7acdbf71-39ff-47c2-91cf-876811650f66'
$sessionId = 'shot-rpg-1026114447'
$ws = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -WebSession $ws `
    -ContentType 'application/json' -Body (@{email = $email; password = 'Passw0rd!123'} | ConvertTo-Json) -UseBasicParsing | Out-Null
$token = ($ws.Cookies.GetCookies($base) | Where-Object { $_.Name -eq 'auth_token' }).Value
if (-not $token) { throw 'auth_token cookie missing' }

$body = @{ gameStateId = $gameStateId; sessionId = $sessionId } | ConvertTo-Json -Compress
$body | Out-File d:\Agent-Platform\sse-body.json -Encoding ascii
& curl.exe -s -N -m 120 -X POST -H 'Content-Type: application/json' -H "Cookie: auth_token=$token" --data "@d:\Agent-Platform\sse-body.json" "$base/rpg/game/start" -o d:\Agent-Platform\sse-turn.txt
$raw = Get-Content d:\Agent-Platform\sse-turn.txt -Raw
$eventLines = @($raw -split "`n" | Where-Object { $_ -like 'event:*' })
$deltaLines = @($raw -split "`n" | Where-Object { $_ -like 'data:*' })
Write-Output ("sse: totalBytes=" + $raw.Length + " eventLines=" + $eventLines.Count + " dataLines=" + $deltaLines.Count)
Write-Output ("sse first events: " + (($eventLines | Select-Object -First 5) -join ' | '))

# isolation re-verify with events now present
try {
    Invoke-WebRequest -Uri "$base/chat/history?sessionId=$sessionId" -WebSession $ws -UseBasicParsing -ErrorAction Stop | Out-Null
    Write-Output 'FAIL history = 200 (should be 404)'
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    if ($code -eq 404) { Write-Output 'PASS history = 404 (with events present)' }
    else { Write-Output "FAIL history = $code" }
}
$list = Invoke-WebRequest -Uri "$base/chat/sessions" -WebSession $ws -UseBasicParsing
$json = [System.Text.Encoding]::UTF8.GetString($list.RawContentStream.ToArray())
$ids = ($json | ConvertFrom-Json) | ForEach-Object { $_.id }
if ($ids -contains $sessionId) { Write-Output 'FAIL listSessions leaks rpg session' }
else { Write-Output "PASS listSessions excludes rpg session (count=$($ids.Count))" }
$ctx = Invoke-WebRequest -Uri "$base/chat/context-info?sessionId=$sessionId" -WebSession $ws -UseBasicParsing
Write-Output "PASS context-info = $($ctx.StatusCode)"
