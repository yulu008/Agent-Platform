# 5.2 E2E probe: create run + S1 generate (SSE) + confirm + detail structure
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$ts = Get-Date -Format HHmmss

function Post-Json($uri, $obj, $sess) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 10))
    Invoke-RestMethod -Method Post -Uri $uri -WebSession $sess -ContentType 'application/json; charset=utf-8' -Body $bytes
}

# 1. register
Post-Json "$base/api/auth/register" @{ email = "story52-$ts@test.dev"; password = 'Passw0rd123' } $null | Out-Null
# re-register with session capture (session needed)
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = "story52-$ts@test.dev"; password = 'Passw0rd123' } | ConvertTo-Json))
try { Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable sess | Out-Null } catch { Write-Output "login failed: $($_.Exception.Message)" }
Write-Output "1.register: story52-$ts@test.dev"

# 2. create run (short Chinese source, faithful mode)
$src = "天明城的老钟敲了七下。抄经人沈砚从藏书楼的阴影里抬起头，他守护的《星轨遗卷》被人撕去了最后一页，那一页写着钥在灯灭处。追索遗页的三天里，沈砚先后遇到守灯人盲女阿蓉、黑市贩子乌鸦、城主府的年轻剑客白榆。盲女说灯灭之夜钟楼会开门，乌鸦索要遗页的一半拓本做交易，白榆声称城主才是撕页人。三人的话互相矛盾，而钟楼在第四天夜里真的开了门。沈砚在门后找到的不是纸页，是一面刻满星轨的铜镜，镜中映出他自己撕下遗页的手。"
$run = Post-Json "$base/rpg/planner/runs" @{ sourceMode = 'text'; styleMode = 'faithful'; text = $src } $sess
$runId = $run.id
Write-Output "2.create run: $runId (source text: $($src.Length) chars)"

# 3. S1 generate (SSE, long timeout) — POST returns full stream text after [DONE]
Write-Output "3.S1 generate: streaming (up to 15 min)..."
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$resp = Invoke-WebRequest -Method Post -Uri "$base/rpg/planner/runs/$runId/stages/S1/generate" -WebSession $sess -ContentType 'application/json' -Body '{}' -TimeoutSec 900 -UseBasicParsing
$sw.Stop()
$content = $resp.Content
$hasDraft = $content -match 'draft_ready'
$hasError = $content -match '"error"'
Write-Output ("  SSE done in {0:mm\:ss}, {1} bytes, draft_ready={2} error={3}" -f $sw.Elapsed, $content.Length, $hasDraft, $hasError)
# show tail events (last 500 chars)
$tail = if ($content.Length -gt 500) { $content.Substring($content.Length - 500) } else { $content }
Write-Output "  tail: $($tail -replace "`n", ' | ')"

# 4. S1 confirm
$confirm = Post-Json "$base/rpg/planner/runs/$runId/stages/S1/confirm" @{} $sess
Write-Output "4.S1 confirm: $($confirm | ConvertTo-Json -Compress)"

# 5. run detail structure (probe candidate shape for later stages)
$detail = Invoke-RestMethod -Uri "$base/rpg/planner/runs/$runId" -WebSession $sess
Write-Output "5.detail keys: $(($detail | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name) -join ',')"
if ($detail.stages) {
    foreach ($s in $detail.stages) { Write-Output "  stage $($s.stage) status=$($s.status) version=$($s.version) candidates=$(@($s.candidates).Count)" }
}
Write-Output "PROBE-52: DONE"
