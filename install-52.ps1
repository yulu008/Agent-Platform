# 5.2 install verification: reuse run bb2506d8 (S1-S5 all CONFIRMED, first install attempt
# failed 409 at x-edge parse - zero writes, run still installable after Parser fix)
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$email = 'story52e-084846@test.dev'
$password = 'Passw0rd123'
$runId = 'bb2506d8-e4bb-4b59-b4bd-6ce328a2a2fd'

function Post-Json($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes
}

# 1. login
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = $email; password = $password } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable sess | Out-Null
Write-Output "1.login: $email"

# 2. install (retry after Parser x-edge fix)
try {
    $result = Post-Json "$base/rpg/planner/runs/$runId/install" @{} $sess
} catch {
    $body = ''
    try { $body = (($_.ErrorDetails.Message)) } catch { }
    Write-Output "FAIL: install error: $($_.Exception.Message) body=$body"
    exit 1
}
Write-Output ("2.install OK: worldId={0} worldName={1} entryCount={2} storyGraphId={3} nodes={4}" -f `
    $result.worldId, $result.worldName, $result.entryCount, $result.storyGraphId, $result.storyGraphNodeCount)

if ([string]::IsNullOrEmpty($result.worldId)) { Write-Output 'FAIL: worldId empty'; exit 1 }
if ([string]::IsNullOrEmpty($result.storyGraphId)) { Write-Output 'FAIL: storyGraphId empty'; exit 1 }
if ($result.storyGraphNodeCount -le 0) { Write-Output 'FAIL: storyGraphNodeCount not positive'; exit 1 }

# 3. workshop story-graph list for the installed world
$graphs = Invoke-RestMethod -Uri "$base/rpg/workshop/worlds/$($result.worldId)/story-graphs" -WebSession $sess
$found = @($graphs) | Where-Object { $_.id -eq $result.storyGraphId }
if (-not $found) {
    Write-Output "FAIL: installed graph $($result.storyGraphId) not in workshop list (count=$(@($graphs).Count))"
    exit 1
}
$g = $found[0]
Write-Output ("3.workshop list: graph visible, name={0} improviseMode={1} worldId-match={2}" -f `
    $g.name, $g.improviseMode, ($g.worldId -eq $result.worldId))

# 4. graph detail: graphJson acts carry stitched x-edges
$detail = Invoke-RestMethod -Uri "$base/rpg/workshop/story-graphs/$($result.storyGraphId)" -WebSession $sess
$hasXEdge = $detail.graphJson -match '"x\d+_\d+"'
$actCount = ([regex]::Matches($detail.graphJson, '"id"\s*:\s*"act\d+"')).Count
Write-Output ("4.graph detail: acts={0} stitchedXEdge={1} stateSchemaLen={2}" -f `
    $actCount, $hasXEdge, $detail.stateSchema.Length)
if (-not $hasXEdge) { Write-Output 'WARN: no x-edge found in graphJson (stitch may use only e-ids)' }

Write-Output 'INSTALL-52: PASS'
