# 5.3 gameplay E2E: graph start -> walk edges -> variant hit -> improvise convergence
# -> orbit gate divergence -> backtrack re-choose -> new-cycle clean start
# Part A: planner-installed graph (chain from 5.2); Part B: hand-crafted tiny graph (deterministic mechanics)
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$email = 'story52e-084846@test.dev'
$password = 'Passw0rd123'
$worldId = '195e4f81-cafc-4c22-a626-90d526bbf89c'
$plannerGraphId = '1b1af15c-8957-415c-8a99-7655be322a93'
$script:step = 0

function Next-Step($msg) { $script:step++; Write-Output ("[{0}] {1}" -f $script:step, $msg) }

function Post-Json($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes
}

function Get-Options($gsId, $sess) {
    Invoke-RestMethod -Uri "$base/rpg/game/story-options?gameStateId=$gsId" -WebSession $sess
}

function Assert-True($cond, $msg) {
    if (-not $cond) { Write-Output "FAIL: $msg"; exit 1 }
}

# SSE turn: POST /rpg/game/turn, returns full stream text after [DONE]
# sessionId follows frontend contract (crypto.randomUUID per game, rpgSessionMemoryAdvisor 消费)
function Sse-Turn($gsId, $sessId, $msg, $edgeId, $sess) {
    $obj = @{ gameStateId = $gsId; sessionId = $sessId; message = $msg }
    if ($edgeId) { $obj.edgeId = $edgeId }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    $resp = Invoke-WebRequest -Method Post -Uri "$base/rpg/game/turn" -WebSession $sess `
        -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 600 -UseBasicParsing
    return $resp.Content
}

# ---- 1. login ----
Next-Step 'login'
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = $email; password = $password } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable sess | Out-Null
Write-Output "  login: $email"

# ==================== Part A: planner-installed graph ====================
Next-Step 'A: create player char in installed world'
$charBody = @{ worldId = $worldId; name = 'smoke53-player'; type = 'player'; identity = '抄经人' }
$char = Post-Json "$base/rpg/workshop/character" $charBody $sess
Write-Output "  char: $($char.id)"

Next-Step 'A: start with planner-installed graph (select graph at start)'
$sessA = [guid]::NewGuid().ToString()
$gsA = Post-Json "$base/rpg/workshop/start" @{ worldId = $worldId; playerCharId = $char.id; storyGraphId = $plannerGraphId; sessionId = $sessA } $sess
$gsAId = $gsA.id
Write-Output "  gameA: $gsAId"

Next-Step 'A: story-options view (mode/nodeId/edges)'
$opts = Get-Options $gsAId $sess
Write-Output ("  opts: mode={0} nodeId={1} edges={2} improvise={3}" -f $opts.mode, $opts.nodeId, @($opts.edges).Count, $opts.improviseAllowed)
Assert-True ($opts.mode -eq 'bounded') "A: mode should be bounded (planner install default)"
Assert-True ($opts.nodeId -ne $null -and $opts.nodeId -ne '') "A: nodeId should be entry node"
Assert-True (@($opts.edges).Count -ge 1) "A: entry should have outgoing edges"
Assert-True ($opts.improviseAllowed -eq $true) "A: bounded allows improvise at scene node"
$entryA = $opts.nodeId

Next-Step 'A: walk first graph edge (SSE turn with edgeId)'
$edgeA = $opts.edges[0]
$stream = Sse-Turn $gsAId $sessA $edgeA.quote $edgeA.id $sess
$done = $stream -match '\[DONE\]'
$err = $stream -match '"error"'
Write-Output ("  turn: {0} chars, DONE={1} error={2} (edge {3} -> advanced)" -f $stream.Length, $done, $err, $edgeA.id)
Assert-True $done "A: SSE stream should end with [DONE]"
Assert-True (-not $err) "A: SSE stream should have no error"

Next-Step 'A: node advanced'
$opts2 = Get-Options $gsAId $sess
Write-Output ("  opts: nodeId={0} (was {1})" -f $opts2.nodeId, $entryA)
Assert-True ($opts2.nodeId -ne $entryA) "A: nodeId should advance after edge walk"

Next-Step 'A: story-forks list (entry + arrived)'
$forks = Invoke-RestMethod -Uri "$base/rpg/game/story-forks?gameStateId=$gsAId" -WebSession $sess
Write-Output ("  forks: {0} nodes: {1}" -f @($forks).Count, ((@($forks) | ForEach-Object { $_.nodeId }) -join ','))
Assert-True (@($forks).Count -ge 2) "A: forks should contain entry + arrived node"

Next-Step 'A: backtrack to entry'
$bt = Post-Json "$base/rpg/game/story-backtrack" @{ gameStateId = $gsAId; nodeId = $entryA } $sess
$optsBt = Get-Options $gsAId $sess
Write-Output ("  after backtrack: nodeId={0} (target {1})" -f $optsBt.nodeId, $entryA)
Assert-True ($optsBt.nodeId -eq $entryA) "A: backtrack should restore entry node"

# ==================== Part B: hand-crafted tiny graph ====================
Next-Step 'B: create tiny graph (bounded, depthLimit=1 for fast convergence)'
$tiny = Post-Json "$base/rpg/workshop/worlds/$worldId/story-graphs" @{
    name = 'smoke53-tiny'; improviseMode = 'bounded'; improviseDepthLimit = 1;
    stateSchema = '{"trust_arong":{"type":"number","min":0,"max":100}}'
} $sess
$tinyId = $tiny.id
Write-Output "  graph: $tinyId"

Next-Step 'B: save graphJson (2 acts, variant node, x-edge cross-act)'
$graphJson = [System.IO.File]::ReadAllText('d:\Agent-Platform\tiny-graph.json')
$saved = Invoke-RestMethod -Method Put -Uri "$base/rpg/workshop/story-graphs/$tinyId" -WebSession $sess `
    -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes((@{ graphJson = $graphJson } | ConvertTo-Json -Depth 10)))
Write-Output "  saved: id=$($saved.id) graphJsonLen=$($saved.graphJson.Length)"

Next-Step 'B: start with tiny graph'
$sessB = [guid]::NewGuid().ToString()
$gsB = Post-Json "$base/rpg/workshop/start" @{ worldId = $worldId; playerCharId = $char.id; storyGraphId = $tinyId; sessionId = $sessB } $sess
$gsBId = $gsB.id
Write-Output "  gameB: $gsBId"

Next-Step 'B: options at entry (2 edges: e1_01 + e1_03)'
$opts = Get-Options $gsBId $sess
$edgeIds = (@($opts.edges) | ForEach-Object { $_.id }) -join ','
Write-Output ("  opts: nodeId={0} edges=[{1}] improvise={2}" -f $opts.nodeId, $edgeIds, $opts.improviseAllowed)
Assert-True ($opts.nodeId -eq 'n1_01') "B: entry should be n1_01"
Assert-True ($opts.improviseAllowed -eq $true) "B: bounded allows improvise"

Next-Step 'B: walk e1_01 -> n1_02 (variant guard hit: on_enter trust=5 >= 5)'
$stream = Sse-Turn $gsBId $sessB '与灯下人结伴同行' 'e1_01' $sess
$done = $stream -match '\[DONE\]'
$err = $stream -match '"error"'
Write-Output ("  turn: {0} chars, DONE={1} error={2}" -f $stream.Length, $done, $err)
Assert-True $done "B: variant turn should complete"
Assert-True (-not $err) "B: variant turn should have no error"

Next-Step 'B: nodeId=n1_02 AND guarded edge e1_02 visible (effects settled: trust=5)'
$opts = Get-Options $gsBId $sess
$edgeIds = (@($opts.edges) | ForEach-Object { $_.id }) -join ','
Write-Output ("  opts: nodeId={0} edges=[{1}]" -f $opts.nodeId, $edgeIds)
Assert-True ($opts.nodeId -eq 'n1_02') "B: should arrive at n1_02"
Assert-True ($edgeIds -match 'e1_02') "B: guarded edge e1_02 visible = on_enter effects settled (trust=5)"

Next-Step 'B: 3 consecutive improvised turns (depthLimit=1: 3rd turn materializes with convergence directive)'
for ($i = 1; $i -le 3; $i++) {
    $msg = '我停在原地，仔细回味方才的对话'
    $stream = Sse-Turn $gsBId $sessB $msg $null $sess
    $done = $stream -match '\[DONE\]'
    $err = $stream -match '"error"'
    Write-Output ("  improvise turn {0}: {1} chars, DONE={2} error={3}" -f $i, $stream.Length, $done, $err)
    Assert-True $done "B: improvise turn $i should complete"
    Assert-True (-not $err) "B: improvise turn $i should have no error"
}

Next-Step 'B: backtrack to entry n1_01 (fork re-choose)'
$bt = Post-Json "$base/rpg/game/story-backtrack" @{ gameStateId = $gsBId; nodeId = 'n1_01' } $sess
$opts = Get-Options $gsBId $sess
$edgeIds = (@($opts.edges) | ForEach-Object { $_.id }) -join ','
Write-Output ("  after backtrack: nodeId={0} edges=[{1}]" -f $opts.nodeId, $edgeIds)
Assert-True ($opts.nodeId -eq 'n1_01') "B: backtrack should restore n1_01"

Next-Step 'B: re-choose different fork e1_03 -> n1_03 (solo path, trust stays 0)'
$stream = Sse-Turn $gsBId $sessB '独自绕开人群' 'e1_03' $sess
$done = $stream -match '\[DONE\]'
$err = $stream -match '"error"'
Write-Output ("  turn: {0} chars, DONE={1} error={2}" -f $stream.Length, $done, $err)
Assert-True $done "B: fork re-choose turn should complete"
Assert-True (-not $err) "B: fork re-choose turn should have no error"

Next-Step 'B: at anchor n1_03: x-edge x1_01 visible, improvise forbidden'
$opts = Get-Options $gsBId $sess
$edgeIds = (@($opts.edges) | ForEach-Object { $_.id }) -join ','
Write-Output ("  opts: nodeId={0} edges=[{1}] improvise={2}" -f $opts.nodeId, $edgeIds, $opts.improviseAllowed)
Assert-True ($opts.nodeId -eq 'n1_03') "B: should arrive at anchor n1_03"
Assert-True ($edgeIds -match 'x1_01') "B: cross-act stitched edge x1_01 should be visible at act exit"
Assert-True ($opts.improviseAllowed -eq $false) "B: anchor node should forbid improvise"

Next-Step 'B: cross act via x1_01 -> n2_01 (orbit gate: trust=0 < 50 divergent -> convergence directive)'
$stream = Sse-Turn $gsBId $sessB '推开钟楼的门' 'x1_01' $sess
$done = $stream -match '\[DONE\]'
$err = $stream -match '"error"'
Write-Output ("  turn: {0} chars, DONE={1} error={2}" -f $stream.Length, $done, $err)
Assert-True $done "B: cross-act turn should complete"
Assert-True (-not $err) "B: cross-act turn should have no error"
$opts = Get-Options $gsBId $sess
Write-Output ("  opts: nodeId={0}" -f $opts.nodeId)
Assert-True ($opts.nodeId -eq 'n2_01') "B: should arrive at n2_01 (act2 entry)"

Next-Step 'B: walk e2_01 -> n2_02 (ending, cycle complete)'
$stream = Sse-Turn $gsBId $sessB '归还遗页' 'e2_01' $sess
$done = $stream -match '\[DONE\]'
$err = $stream -match '"error"'
Write-Output ("  turn: {0} chars, DONE={1} error={2}" -f $stream.Length, $done, $err)
Assert-True $done "B: ending turn should complete"
Assert-True (-not $err) "B: ending turn should have no error"
$opts = Get-Options $gsBId $sess
Write-Output ("  opts: nodeId={0} (ending)" -f $opts.nodeId)
Assert-True ($opts.nodeId -eq 'n2_02') "B: should arrive at ending n2_02"

Next-Step 'B: new-cycle clean start (walked edges cleared, back to entry)'
$nc = Post-Json "$base/rpg/game/story-new-cycle" @{ gameStateId = $gsBId; rematerialize = $false } $sess
Write-Output ("  new-cycle: nodeId={0}" -f $nc.nodeId)
Assert-True ($nc.nodeId -eq 'n1_01') "B: new-cycle should restart at entry n1_01"
$opts = Get-Options $gsBId $sess
$edgeIds = (@($opts.edges) | ForEach-Object { $_.id }) -join ','
Write-Output ("  opts: nodeId={0} edges=[{1}]" -f $opts.nodeId, $edgeIds)
Assert-True ($opts.nodeId -eq 'n1_01') "B: options should show entry after new-cycle"
$forks = Invoke-RestMethod -Uri "$base/rpg/game/story-forks?gameStateId=$gsBId" -WebSession $sess
$forkIds = (@($forks) | ForEach-Object { $_.nodeId }) -join ','
Write-Output ("  forks after new-cycle: [{0}] (should be entry only = clean)" -f $forkIds)
Assert-True ($forkIds.Trim() -eq 'n1_01') "B: forks should be entry only after new-cycle (clean start)"

# ---- final: engine log evidence (orbit gate divergence + new cycle) ----
Next-Step 'log evidence: orbit gate divergence + new cycle'
$recent = Get-Content d:\Agent-Platform\logs\application.log -Tail 400 -Encoding UTF8
$gateHit = ($recent | Select-String -Pattern '轨道闸发散').Count -gt 0
$cycleHit = ($recent | Select-String -Pattern '故事图新周目').Count -gt 0
Write-Output ("  log: orbit-gate-divergence={0} new-cycle={1}" -f $gateHit, $cycleHit)
Assert-True $gateHit "B: engine log should record orbit gate divergence (story_orbit_diverge)"
Assert-True $cycleHit "B: engine log should record new cycle"

Write-Output 'SMOKE-53: PASS'
