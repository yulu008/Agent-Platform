$ErrorActionPreference = 'Stop'
# inspect actual title value with unicode escapes (register fresh tenant, create session, list)
$base = 'http://localhost:8080'
$ts = Get-Date -Format 'HHmmss'
$ws = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest -Uri "$base/api/auth/register" -Method Post -WebSession $ws `
    -ContentType 'application/json' -Body (@{email = "title-chk-$ts@test.local"; password = 'Passw0rd!123'} | ConvertTo-Json) -UseBasicParsing | Out-Null
Invoke-WebRequest -Uri "$base/chat/sessions" -Method Post -WebSession $ws -UseBasicParsing | Out-Null
$resp = Invoke-WebRequest -Uri "$base/chat/sessions" -WebSession $ws -UseBasicParsing
$json = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
$title = ($json | ConvertFrom-Json)[0].title
Write-Output ('title-length: ' + $title.Length)
Write-Output ('title-escaped: ' + [regex]::Replace($title, '[^\x00-\x7f]', { param($m) '\u{0:x4}' -f [int][char]$m.Value }))
