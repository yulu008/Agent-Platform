$base = 'http://localhost:8080'
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'story52e-084846@test.dev'; password = 'Passw0rd123' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable sess | Out-Null
try {
    $r = Invoke-RestMethod -Method Post -Uri "$base/rpg/planner/runs/bb2506d8-e4bb-4b59-b4bd-6ce328a2a2fd/install" -WebSession $sess -ContentType 'application/json' -Body '{}'
    Write-Output ('install OK: ' + ($r | ConvertTo-Json -Compress))
} catch {
    $resp = $_.Exception.Response
    if ($resp) {
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $body = $reader.ReadToEnd()
        Write-Output ('install ' + [int]$resp.StatusCode + ': ' + $body)
    } else {
        Write-Output ('install error: ' + $_.Exception.Message)
    }
}
