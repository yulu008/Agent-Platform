$tries = 0
$ok = $false
while ($tries -lt 40 -and -not $ok) {
    $tries++
    Start-Sleep -Seconds 4
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) {
            $ok = $true
            Write-Output ("health UP after " + ($tries * 4) + "s")
        }
    } catch { }
}
if (-not $ok) { Write-Output 'health NOT up yet' }
