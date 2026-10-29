$ErrorActionPreference = 'Stop'
# 6.4 screenshot tenant: 2 chat sessions + 1 rpg game (list must show only chat)
$base = 'http://localhost:8080'
$ts = Get-Date -Format 'MMddHHmmss'
$email = "browse-shot-$ts@test.local"
$ws = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$reg = Invoke-WebRequest -Uri "$base/api/auth/register" -Method Post -WebSession $ws `
    -ContentType 'application/json' -Body (@{email = $email; password = 'Passw0rd!123'} | ConvertTo-Json) -UseBasicParsing
Write-Output "register: $($reg.StatusCode)"

# two chat sessions
1..2 | ForEach-Object {
    Invoke-WebRequest -Uri "$base/chat/sessions" -Method Post -WebSession $ws -UseBasicParsing | Out-Null
}
Write-Output "chat sessions created: 2"

# one rpg game
$worldId = "w-shot-$ts"
$charId = "c-shot-$ts"
Invoke-WebRequest -Uri "$base/rpg/workshop/world" -Method Post -WebSession $ws -UseBasicParsing `
    -ContentType 'application/json' -Body (@{id = $worldId; name = "shot-world-$ts"; settingDesc = "screenshot world"; era = "shot-era"} | ConvertTo-Json) | Out-Null
Invoke-WebRequest -Uri "$base/rpg/workshop/character" -Method Post -WebSession $ws -UseBasicParsing `
    -ContentType 'application/json' -Body (@{id = $charId; worldId = $worldId; name = "shot-player"; type = "player"} | ConvertTo-Json) | Out-Null
$gs = Invoke-WebRequest -Uri "$base/rpg/workshop/start" -Method Post -WebSession $ws -UseBasicParsing `
    -ContentType 'application/json' -Body (@{worldId = $worldId; playerCharId = $charId; sessionId = "shot-rpg-$ts"} | ConvertTo-Json)
Write-Output "rpg started: shot-rpg-$ts gameStateId=$(($gs.Content | ConvertFrom-Json).id)"

# verify list: only 2 chat sessions, no rpg
$list = Invoke-WebRequest -Uri "$base/chat/sessions" -WebSession $ws -UseBasicParsing
$json = [System.Text.Encoding]::UTF8.GetString($list.RawContentStream.ToArray())
Write-Output "sessions json: $json"

"email=$email" | Out-File d:\Agent-Platform\verify-shot-tenant.txt -Encoding ascii
