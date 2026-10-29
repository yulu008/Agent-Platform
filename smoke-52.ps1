# 5.2 planner 全管线 E2E：原文 → S1-S4 → S4.5 逐幕骨架（含一次返工）→ 整稿通过 → 安装物化
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$ts = Get-Date -Format HHmmss
$script:step = 0

function Next-Step($msg) { $script:step++; Write-Output ("[{0}] {1}" -f $script:step, $msg) }

function Post-Json($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes
}

function Assert-True($cond, $msg) {
    if (-not $cond) { Write-Output "FAIL: $msg"; exit 1 }
}

# SSE 全流消费：POST 等 [DONE] 流结束，返回完整流文本
function Sse-Post($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    $resp = Invoke-WebRequest -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 900 -UseBasicParsing
    return $resp.Content
}

function Sse-Assert($content, $stageName) {
    if ($content -notmatch 'draft_ready') {
        $tail = if ($content.Length -gt 800) { $content.Substring($content.Length - 800) } else { $content }
        Write-Output ("FAIL: {0} SSE 未达 draft_ready；流尾部: {1}" -f $stageName, ($tail -replace "`n", ' | '))
        exit 1
    }
}

function Get-Stage($detail, $code) {
    return @($detail.stages) | Where-Object { $_.code -eq $code } | Select-Object -First 1
}

# ============ 1. 注册（带 session） ============
Next-Step "register story52e-$ts@test.dev"
$regBytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = "story52e-$ts@test.dev"; password = 'Passw0rd123' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/register" -ContentType 'application/json; charset=utf-8' -Body $regBytes -SessionVariable sess | Out-Null
Write-Output "  registered"

# ============ 2. 创建 run（中文短篇原文，faithful 忠实改编） ============
Next-Step "create run"
$src = "天明城的老钟敲了七下。抄经人沈砚从藏书楼的阴影里抬起头，他守护的《星轨遗卷》被人撕去了最后一页，那一页写着钥在灯灭处。追索遗页的三天里，沈砚先后遇到守灯人盲女阿蓉、黑市贩子乌鸦、城主府的年轻剑客白榆。盲女说灯灭之夜钟楼会开门，乌鸦索要遗页的一半拓本做交易，白榆声称城主才是撕页人。三人的话互相矛盾，而钟楼在第四天夜里真的开了门。沈砚在门后找到的不是纸页，是一面刻满星轨的铜镜，镜中映出他自己撕下遗页的手。"
$run = Post-Json "$base/rpg/planner/runs" @{ sourceMode = 'text'; styleMode = 'faithful'; text = $src } $sess
$runId = $run.id
Write-Output "  runId=$runId (source $($src.Length) chars)"

# ============ 3. S1 IP 分析：generate + confirm ============
Next-Step "S1 generate (SSE)"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S1/generate" @{} $sess
Sse-Assert $c 'S1'
Write-Output ("  SSE {0:mm\:ss} draft_ready" -f $sw.Elapsed)
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S1/confirm" @{} $sess
Assert-True ($r.stage -eq 'S1') "S1 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S1 confirmed -> $($r.nextStage)"

# ============ 4. S2 世界设定：generate + confirm ============
Next-Step "S2 generate (SSE)"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S2/generate" @{} $sess
Sse-Assert $c 'S2'
Write-Output ("  SSE {0:mm\:ss} draft_ready" -f $sw.Elapsed)
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S2/confirm" @{} $sess
Assert-True ($r.stage -eq 'S2') "S2 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S2 confirmed -> $($r.nextStage)"

# ============ 5. S3 人物卡（L2）：generate → 逐候选 confirm → 整稿 confirm ============
Next-Step "S3 generate (SSE, L2)"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S3/generate" @{} $sess
Sse-Assert $c 'S3'
Write-Output ("  SSE {0:mm\:ss} draft_ready" -f $sw.Elapsed)
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s3 = Get-Stage $detail 'S3'
Assert-True ($null -ne $s3) "S3 阶段视图缺失"
$cands = @($s3.candidates)
Assert-True ($cands.Count -gt 0) "S3 无候选"
Write-Output "  candidates: $($cands.Count) — $(($cands | ForEach-Object { $_.title }) -join ' / ')"
foreach ($cd in $cands) {
    $cr = Post-Json "$base/rpg/planner/runs/$runId/stages/S3/candidates/$($cd.id)/confirm" @{} $sess
    Write-Output "  confirmed card $($cd.id) [$($cd.title)] -> $($cr.status)"
}
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S3/confirm" @{} $sess
Assert-True ($r.stage -eq 'S3') "S3 整稿 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S3 confirmed -> $($r.nextStage)"

# ============ 6. S4 剧情结构：generate + confirm ============
Next-Step "S4 generate (SSE)"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S4/generate" @{} $sess
Sse-Assert $c 'S4'
Write-Output ("  SSE {0:mm\:ss} draft_ready" -f $sw.Elapsed)
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S4/confirm" @{} $sess
Assert-True ($r.stage -eq 'S4') "S4 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S4 confirmed -> $($r.nextStage)"

# ============ 7. S4.5 逐幕骨架（L2）：generate → 第一幕 rework → 逐幕 confirm → 整稿 confirm ============
Next-Step "S4.5 story-graph generate (SSE, per-act)"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/story-graph/generate" @{} $sess
Sse-Assert $c 'S4.5'
$actDone = ([regex]::Matches($c, 'act_done')).Count
Write-Output ("  SSE {0:mm\:ss} draft_ready, act_done x{1}" -f $sw.Elapsed, $actDone)
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s45 = Get-Stage $detail 'S4.5'
Assert-True ($null -ne $s45) "S4.5 阶段视图缺失"
$acts = @($s45.candidates)
Assert-True ($acts.Count -gt 0) "S4.5 无幕候选"
Write-Output "  acts: $($acts.Count) — $(($acts | ForEach-Object { $_.title }) -join ' / ')"

Next-Step "S4.5 act rework (幕级返工, SSE)"
$targetAct = $acts[0]
$feedback = "第一幕的冲突张力不足：强化沈砚与白榆的对峙，并让信任轨道 trust 的收敛条件更清晰。"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S4.5/candidates/$($targetAct.id)/rework" @{ feedback = $feedback } $sess
Sse-Assert $c 'S4.5-rework'
Write-Output ("  rework SSE {0:mm\:ss} draft_ready (act: {1})" -f $sw.Elapsed, $targetAct.title)

Next-Step "S4.5 act confirms + stage confirm"
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
$s45 = Get-Stage $detail 'S4.5'
$acts = @($s45.candidates)
foreach ($act in $acts) {
    $cr = Post-Json "$base/rpg/planner/runs/$runId/stages/S4.5/candidates/$($act.id)/confirm" @{} $sess
    Write-Output "  confirmed act $($act.id) [$($act.title)] -> $($cr.status)"
}
$r = Post-Json "$base/rpg/planner/runs/$runId/stages/S4.5/confirm" @{} $sess
Assert-True ($r.stage -eq 'S4.5') "S4.5 整稿 confirm 返回异常: $($r | ConvertTo-Json -Compress)"
Write-Output "  S4.5 confirmed -> $($r.nextStage)"

# ============ 8. S5 分支设计（L2）：generate → 逐候选 confirm → 整稿 confirm ============
Next-Step "S5 generate (SSE, L2)"
$sw.Restart()
$c = Sse-Post "$base/rpg/planner/runs/$runId/stages/S5/generate" @{} $sess
Sse-Assert $c 'S5'
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

# ============ 9. 安装物化：world + 故事图 ============
Next-Step "install (S1-S5 all confirmed)"
$sw.Restart()
$inst = Invoke-RestMethod -Method Post -Uri "$base/rpg/planner/runs/$runId/install" -WebSession $sess -ContentType 'application/json' -Body '{}'
$worldId = $inst.worldId
Assert-True (-not [string]::IsNullOrEmpty($worldId)) "install 未返回 worldId: $($inst | ConvertTo-Json -Compress)"
Assert-True (-not [string]::IsNullOrEmpty($inst.storyGraphId)) "install 未返回 storyGraphId: $($inst | ConvertTo-Json -Compress)"
Assert-True ($inst.storyGraphNodeCount -gt 0) "install storyGraphNodeCount 非正: $($inst | ConvertTo-Json -Compress)"
Write-Output "  installed: worldId=$worldId world=$($inst.worldName) entries=$($inst.entryCount)"
Write-Output "  storyGraphId=$($inst.storyGraphId) nodes=$($inst.storyGraphNodeCount)"

# ============ 10. 工坊模板层断言：安装图在 world 下可见 ============
Next-Step "workshop story-graphs visible"
$graphs = Invoke-RestMethod -Uri "$base/rpg/workshop/worlds/$worldId/story-graphs" -WebSession $sess
$found = @($graphs) | Where-Object { $_.id -eq $inst.storyGraphId }
Assert-True ($null -ne $found) "world 下未见安装图 storyGraphId=$($inst.storyGraphId)"
Write-Output "  graph listed: $($found.name) mode=$($found.improviseMode) worldId=$($found.worldId)"

Write-Output "SMOKE-52: PASS"
