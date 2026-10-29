$ErrorActionPreference = 'Stop'
# separate-rpg-chat-sessions tasks 6.1 e2e verify:
# register -> rpg start -> /chat/sessions excludes rpg -> history 404 -> compact 404 -> context-info 200
$base = 'http://localhost:8080'
$ts = Get-Date -Format 'MMddHHmmss'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function PostJson($uri, $body) {
    Invoke-WebRequest -Uri $uri -Method Post -WebSession $session `
        -ContentType 'application/json; charset=utf-8' -Body $body -UseBasicParsing
}
function ExpectStatus($name, $expected, $actual) {
    if ($actual -eq $expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual" }
}

# 1. register (Set-Cookie auth_token into WebSession)
$reg = PostJson "$base/api/auth/register" (@{email = "rpg-sep-$ts@test.local"; password = 'Passw0rd!123'} | ConvertTo-Json)
Write-Output "register: $($reg.StatusCode) $($reg.Content)"

# 2. create world + player character
$worldId = "w-verify-$ts"
$charId = "c-verify-$ts"
PostJson "$base/rpg/workshop/world" (@{id = $worldId; name = "verify-world-$ts"; settingDesc = "e2e verify world"; era = "test-era"} | ConvertTo-Json) | Out-Null
PostJson "$base/rpg/workshop/character" (@{id = $charId; worldId = $worldId; name = "verify-player"; type = "player"} | ConvertTo-Json) | Out-Null
Write-Output "world+character created: $worldId / $charId"

# 3. rpg start (custom sessionId, verifies tagging path)
$rpgSessionId = "rpg-verify-session-$ts"
$gs = PostJson "$base/rpg/workshop/start" (@{worldId = $worldId; playerCharId = $charId; sessionId = $rpgSessionId} | ConvertTo-Json)
$gameStateId = ($gs.Content | ConvertFrom-Json).id
Write-Output "startGame: rpgSessionId=$rpgSessionId gameStateId=$gameStateId"

# 4. main session list excludes rpg session (kind=rpg filter)
$list = Invoke-WebRequest -Uri "$base/chat/sessions" -WebSession $session -UseBasicParsing
$sessions = $list.Content | ConvertFrom-Json
$leak = @($sessions | Where-Object { $_.id -eq $rpgSessionId }).Count
if ($leak -eq 0) { Write-Output "PASS listSessions excludes rpg session (total=$($sessions.Count))" }
else { Write-Output "FAIL listSessions leaks rpg session: $rpgSessionId" }

# 5. history 404 (isRpg guard)
try {
    Invoke-WebRequest -Uri "$base/chat/history?sessionId=$rpgSessionId" -WebSession $session -UseBasicParsing -ErrorAction Stop | Out-Null
    ExpectStatus 'history' 404 200
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    ExpectStatus 'history' 404 $code
}

# 6. compact 404 (isRpg guard, zero side effect)
try {
    Invoke-WebRequest -Uri "$base/chat/sessions/$rpgSessionId/compact" -Method Post -WebSession $session -UseBasicParsing -ErrorAction Stop | Out-Null
    ExpectStatus 'compact' 404 200
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    ExpectStatus 'compact' 404 $code
}

# 7. context-info 200 (whitelist: rpg page context bar data source)
$ctx = Invoke-WebRequest -Uri "$base/chat/context-info?sessionId=$rpgSessionId" -WebSession $session -UseBasicParsing
ExpectStatus 'context-info' 200 $ctx.StatusCode
Write-Output "context-info body: $($ctx.Content)"

# save state for 6.2 deletion verify
"rpgSessionId=$rpgSessionId`ngameStateId=$gameStateId" | Out-File d:\Agent-Platform\verify-61-state.txt -Encoding ascii
Write-Output "state saved: d:\Agent-Platform\verify-61-state.txt"
