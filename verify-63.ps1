$ErrorActionPreference = 'Stop'
# 6.3 live verify: new tenant -> create chat session -> list shows it with proper title
$base = 'http://localhost:8080'
$ts = Get-Date -Format 'MMddHHmmss'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$reg = Invoke-WebRequest -Uri "$base/api/auth/register" -Method Post -WebSession $session `
    -ContentType 'application/json' -Body (@{email = "chat-live-$ts@test.local"; password = 'Passw0rd!123'} | ConvertTo-Json) -UseBasicParsing
Write-Output "register: $($reg.StatusCode)"

$create = Invoke-WebRequest -Uri "$base/chat/sessions" -Method Post -WebSession $session -UseBasicParsing
$newId = ($create.Content | ConvertFrom-Json).id
Write-Output "createSession: id=$newId title=$(($create.Content | ConvertFrom-Json).title)"

$list = Invoke-WebRequest -Uri "$base/chat/sessions" -WebSession $session -UseBasicParsing
$sessions = $list.Content | ConvertFrom-Json
$found = @($sessions | Where-Object { $_.id -eq $newId })
if ($found.Count -eq 1 -and $found[0].title -eq ([char]0x65B0 + [char]0x5BF9 + [char]0x8BDD)) {
    Write-Output "PASS chat session listed with default title"
} else {
    Write-Output "FAIL chat session listing: count=$($found.Count) title=$($found[0].title)"
}
Write-Output "sessions json: $($list.Content)"
