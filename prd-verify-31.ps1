$ErrorActionPreference = 'Stop'
# planner-run-delete tasks 3.1 manual walkthrough:
# create run -> list contains -> workspace dir exists -> DELETE 204 ->
# list excludes / detail 404 / repeat delete idempotent 404 / dir cleared
# abandon semantics regression: another run abandoned keeps workspace + listed (soft-final)
$base = 'http://localhost:8080/rpg/planner'
$ts = Get-Date -Format 'MMddHHmmss'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function PostJson($uri, $body) {
    Invoke-WebRequest -Uri $uri -Method Post -WebSession $session `
        -ContentType 'application/json; charset=utf-8' -Body $body -UseBasicParsing
}
function Expect($name, $expected, $actual) {
    if ($actual -eq $expected) { Write-Output "PASS $name = $actual" }
    else { Write-Output "FAIL $name expected=$expected actual=$actual" }
}

# 1. register (Set-Cookie into WebSession)
$reg = PostJson 'http://localhost:8080/api/auth/register' (@{email = "prd31-$ts@test.local"; password = 'Passw0rd!123'} | ConvertTo-Json)
Write-Output "register: $($reg.StatusCode)"

# 2. create run A (delete target)
$runA = (PostJson "$base/runs" (@{sourceMode = 'text'; styleMode = 'faithful'; text = 'walkthrough: delete end to end.'; name = "prd31-del-$ts"} | ConvertTo-Json)).Content | ConvertFrom-Json
$runAId = $runA.id
Write-Output "create runA: $runAId (state=$($runA.status))"

# 3. list contains runA
$listA = (Invoke-WebRequest -Uri "$base/runs" -WebSession $session -UseBasicParsing).Content | ConvertFrom-Json
Expect 'list contains runA' 1 @($listA | Where-Object { $_.id -eq $runAId }).Count

# 4. workspace dir exists (wildcard scan across tenants)
$dirA = Get-Item "$env:USERPROFILE\.agent\tenants\*\planner-runs\$runAId" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dirA) { Write-Output "PASS workspace dir exists: $($dirA.FullName)" } else { Write-Output 'FAIL workspace dir missing before delete' }

# 5. DELETE -> 204
try {
    $del = Invoke-WebRequest -Uri "$base/runs/$runAId" -Method Delete -WebSession $session -UseBasicParsing -ErrorAction Stop
    Expect 'DELETE status' 204 ([int]$del.StatusCode)
} catch {
    Expect 'DELETE status' 204 ([int]$_.Exception.Response.StatusCode)
}

# 6. after delete: list excludes / detail 404 / repeat delete 404 / workspace dir cleared
$listB = (Invoke-WebRequest -Uri "$base/runs" -WebSession $session -UseBasicParsing).Content | ConvertFrom-Json
Expect 'list excludes runA' 0 @($listB | Where-Object { $_.id -eq $runAId }).Count
try {
    Invoke-WebRequest -Uri "$base/runs/$runAId" -WebSession $session -UseBasicParsing -ErrorAction Stop | Out-Null
    Expect 'detail after delete' 404 200
} catch {
    Expect 'detail after delete' 404 ([int]$_.Exception.Response.StatusCode)
}
try {
    Invoke-WebRequest -Uri "$base/runs/$runAId" -Method Delete -WebSession $session -UseBasicParsing -ErrorAction Stop | Out-Null
    Expect 'repeat delete idempotent' 404 200
} catch {
    Expect 'repeat delete idempotent' 404 ([int]$_.Exception.Response.StatusCode)
}
$dirAfter = Test-Path "$env:USERPROFILE\.agent\tenants\*\planner-runs\$runAId"
if (-not $dirAfter) { Write-Output 'PASS workspace dir cleared after delete' }
else { Write-Output 'FAIL workspace dir still exists after delete' }

# 7. abandon semantics regression: run B soft-final keeps workspace
$runB = (PostJson "$base/runs" (@{sourceMode = 'text'; styleMode = 'faithful'; text = 'walkthrough: abandon semantics unchanged.'; name = "prd31-ab-$ts"} | ConvertTo-Json)).Content | ConvertFrom-Json
$runBId = $runB.id
$ab = PostJson "$base/runs/$runBId/abandon" '{}'
Expect 'abandon status' 204 ([int]$ab.StatusCode)
$dirB = Test-Path "$env:USERPROFILE\.agent\tenants\*\planner-runs\$runBId"
if ($dirB) { Write-Output 'PASS abandon keeps workspace (soft-final, reviewable)' }
else { Write-Output 'FAIL abandon lost workspace' }
$listC = (Invoke-WebRequest -Uri "$base/runs" -WebSession $session -UseBasicParsing).Content | ConvertFrom-Json
Expect 'abandoned run still listed' 1 @($listC | Where-Object { $_.id -eq $runBId }).Count
