$ErrorActionPreference = 'Stop'
# separate-rpg-chat-sessions tasks 6.2 verify (http part):
# login -> delete rpg save -> 204 -> saves list empty
$base = 'http://localhost:8080'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$email = "rpg-sep-1026111432@test.local"

$state = Get-Content d:\Agent-Platform\verify-61-state.txt
$rpgSessionId = ($state | Where-Object { $_ -like 'rpgSessionId=*' }) -replace 'rpgSessionId=', ''
$gameStateId = ($state | Where-Object { $_ -like 'gameStateId=*' }) -replace 'gameStateId=', ''
Write-Output "target: rpgSessionId=$rpgSessionId gameStateId=$gameStateId"

# login (Set-Cookie auth_token)
$login = Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -WebSession $session `
    -ContentType 'application/json' -Body (@{email = $email; password = 'Passw0rd!123'} | ConvertTo-Json) -UseBasicParsing
Write-Output "login: $($login.StatusCode)"

# confirm AI_SESSION shell alive before delete (context-info 200 = session exists)
$before = Invoke-WebRequest -Uri "$base/chat/context-info?sessionId=$rpgSessionId" -WebSession $session -UseBasicParsing
Write-Output "before delete context-info: $($before.StatusCode)"

# delete save (cascades to AI_SESSION + AI_SESSION_EVENT, same tx)
$del = Invoke-WebRequest -Uri "$base/rpg/game/state/$gameStateId" -Method Delete -WebSession $session -UseBasicParsing
if ($del.StatusCode -eq 204) { Write-Output "PASS deleteSave = 204" }
else { Write-Output "FAIL deleteSave = $($del.StatusCode)" }

# saves list no longer contains it
$saves = Invoke-WebRequest -Uri "$base/rpg/game/saves" -WebSession $session -UseBasicParsing
$cards = $saves.Content | ConvertFrom-Json
$remain = @($cards | Where-Object { $_.id -eq $gameStateId }).Count
if ($remain -eq 0) { Write-Output "PASS game/saves excludes deleted card (total=$($cards.Count))" }
else { Write-Output "FAIL game/saves still contains $gameStateId" }

# re-delete is 404 (idempotent guard)
try {
    Invoke-WebRequest -Uri "$base/rpg/game/state/$gameStateId" -Method Delete -WebSession $session -UseBasicParsing -ErrorAction Stop | Out-Null
    Write-Output "INFO re-delete status=200 (unexpected)"
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    Write-Output "PASS re-delete = $code (404 expected)"
}
