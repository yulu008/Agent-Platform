# 5.2 续跑：复用 run bb2506d8（S1-S4 已确认），从 S4.5 起走完链路（含 SSE 瞬断重试）
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$email = 'story52e-084846@test.dev'
$runId = 'bb2506d8-e4bb-4b59-b4bd-6ce328a2a2fd'
$script:step = 0

function Next-Step($msg) { $script:step++; Write-Output ("[{0}] {1}" -f $script:step, $msg) }

function Post-Json($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes
}

function Assert-True($cond, $msg) {
    if (-not $cond) { Write-Output "FAIL: $msg"; exit 1 }
}

# SSE 全流消费：POST 等 [DONE]，返回完整流文本
function Sse-Post($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    $resp = Invoke-WebRequest -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 900 -UseBasicParsing
    return $resp.Content
}

# SSE 消费 + 瞬断重试（LLM 上游 IO 异常时最多重试 3 次）
function Sse-Post-Retry($uri, $obj, $sess, $stageName) {
    $maxRetry = 3
    for ($i = 1; $i -le $maxRetry; $i++) {
        $c = Sse-Post $uri $obj $sess
        if ($c -match 'draft_ready') { return $c }
        $isIo = $c -match '"error"'
        $tail = if ($c.Length -gt 400) { $c.Substring($c.Length - 400) } else { $c }
        Write-Output ("  {0} attempt {1} failed (io/transient): {2}" -f $stageName, $i, ($tail -replace "`n", ' | '))
        if ($i -lt $maxRetry -and $isIo) { Write-Output "  retry in 15s..."; Start-Sleep -Seconds 15 }
    }
    $tail = if ($c.Length -gt 800) { $c.Substring($c.Length - 800) } else { $c }
    Write-Output ("FAIL: {0} SSE 未达 draft_ready；流尾部: {1}" -f $stageName, ($tail -replace "`n", ' | '))
    exit 1
}

function Get-Stage($detail, $code) {
    return @($detail.stages) | Where-Object { $_.code -eq $code } | Select-Object -First 1
}

# ============ 0. 登录既有用户 ============
Next-Step "login $email"
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = $email; password = 'Passw0rd123' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable sess | Out-Null
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
foreach ($s in @($detail.stages)) {
    Write-Output ("  stage {0} status={1} v{2} cands={3}" -f $s.code, $s.status, $s.version, @($s.candidates).Count)
}

# ============ 1. S4.5 逐幕骨架：generate（带重试）→ 第一幕 rework → 逐幕 confirm → 整稿 confirm ============
Next-Step "S4.5 story-graph generate (SSE, per-act, retry)"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$c = Sse-Post-Retry "$base/rpg/planner/runs/$runId/story-graph/generate" @{} $sess 'S4.5'
$actDone = ([regex]::Matches($c, 'act_done')).Count
Write-Output ("  SSE {0:mm\:ss} draft_ready, act_done x{1}" -f $sw.Elapsed, $actDone)
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s45 = Get-Stage $detail 'S4.5'
Assert-True ($null -ne $s45) "S4.5 阶段视图缺失"
$acts = @($s45.candidates)
Assert-True ($acts.Count -gt 0) "S4.5 无幕候选"
Write-Output "  acts: $($acts.Count) — $(($acts | ForEach-Object { $_.title }) -join ' / ')"

Next-Step "S4.5 act rework (幕级返工, SSE, retry)"
$targetAct = $acts[0]
$feedback = "第一幕的冲突张力不足：强化沈砚与白榆的对峙，并让信任轨道 trust 的收敛条件更清晰。"
$sw.Restart()
$c = Sse-Post-Retry "$base/rpg/planner/runs/$runId/stages/S4.5/candidates/$($targetAct.id)/rework" @{ feedback = $feedback } $sess 'S4.5-rework'
Write-Output ("  rework SSE {0:mm\:ss} draft_ready (act: {1})" -f $sw.Elapsed, $targetAct.title)

Next-Step "S4.5 act confirms + stage confirm"
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s45 = Get-Stage $detail 'S4.5'
$acts = @($s45.candidates)
Assert-True ($acts.Count -gt 0) "S4.5 rework 后无幕候选"
foreach ($act in $acts) {
    $cr = Post-Json "$base/rpg/planner/runs/$runId/stages/S4.5/candidates/$($act.id)/confirm" @{} $sess
    Write-Output "  confirmed act $($act.id) [$($act.title)] -> $($cr.status)"
}
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S4.5/confirm" @{} $sess
Assert-True ($r.stage -eq 'S4.5') "S4.5 整稿 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S4.5 confirmed -> $($r.nextStage)"

# ============ 2. S5 分支设计（L2）：generate → 逐候选 confirm → 整稿 confirm ============
Next-Step "S5 generate (SSE, L2, retry)"
$sw.Restart()
$c = Sse-Post-Retry "$base/rpg/planner/runs/$runId/stages/S5/generate" @{} $sess 'S5'
Write-Output ("  SSE {0:mm\:ss} draft_ready" -f $sw.Elapsed)
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s5 = Get-Stage $detail 'S5'
$cands = @($s5.candidates)
Assert-True ($cands.Count -gt 0) "S5 无候选"
Write-Output "  candidates: $($cands.Count) — $(($cands | ForEach-Object { $_.title }) -join ' / ')"
foreach ($cd in $cands) {
    $cr = Post-Json "$base/rpg/planner/runs/$runId/stages/S5/candidates/$($cd.id)/confirm" @{} $sess
    Write-Output "  confirmed card $($cd.id) [$($cd.title)] -> $($cr.status)"
}
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S5/confirm" @{} $sess
Assert-True ($r.stage -eq 'S5') "S5 整稿 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S5 confirmed -> $($r.nextStage)"

# ============ 3. 安装物化：world + 故事图 ============
Next-Step "install (S1-S5 all confirmed)"
$sw.Restart()
$inst = Invoke-RestMethod -Method Post -Uri "$base/rpg/planner/runs/$runId/install" -WebSession $sess -ContentType 'application/json' -Body '{}'
$worldId = $inst.worldId
Assert-True (-not [string]::IsNullOrEmpty($worldId)) "install 未返回 worldId: $($inst | ConvertTo-Json -Compress)"
Assert-True (-not [string]::IsNullOrEmpty($inst.storyGraphId)) "install 未返回 storyGraphId: $($inst | ConvertTo-Json -Compress)"
Assert-True ($inst.storyGraphNodeCount -gt 0) "install storyGraphNodeCount 非正: $($inst | ConvertTo-Json -Compress)"
Write-Output "  installed: worldId=$worldId world=$($inst.worldName) entries=$($inst.entryCount)"
Write-Output "  storyGraphId=$($inst.storyGraphId) nodes=$($inst.storyGraphNodeCount)"

# ============ 4. 工坊模板层断言：安装图在 world 下可见 ============
Next-Step "workshop story-graphs visible"
$graphs = Invoke-RestMethod -Uri "$base/rpg/workshop/worlds/$worldId/story-graphs" -WebSession $sess
$found = @($graphs) | Where-Object { $_.id -eq $inst.storyGraphId }
Assert-True ($null -ne $found) "world 下未见安装图 storyGraphId=$($inst.storyGraphId)"
Write-Output "  graph listed: $($found.name) mode=$($found.improviseMode) worldId=$($found.worldId)"

Write-Output "RETRY-52: PASS"
