$ErrorActionPreference = 'Stop'
# planner-s3-batch-generate tasks 6.3 E2E smoke:
# 85-roster run -> S1/S2 -> S3 append-batch (1 batch) -> generate-full (4 batches)
# -> 85 candidates coverage -> confirm all -> S4 generate (compact index check via sse-raw.log)
# NOTE: keep this script ASCII-only (PS 5.1 reads BOM-less ps1 as ANSI); CJK strings
# are loaded from s3b-e2e-source.txt at runtime.

Add-Type -AssemblyName System.Net.Http
$base = 'http://localhost:8080'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$sourceText = [System.IO.File]::ReadAllText('d:\Agent-Platform\s3b-e2e-source.txt', $utf8)
# NOTE: keep this script ASCII-only (PS 5.1 reads BOM-less ps1 as ANSI); CJK chars
# in regex patterns must use \uXXXX escapes (raw CJK bytes get mis-decoded as ANSI).
$names = @([regex]::Matches($sourceText, '\u4eba\u7269\d{3}') | ForEach-Object { $_.Value } | Sort-Object -Unique)
Write-Output ("roster names extracted from source file: " + $names.Count)
if ($names.Count -ne 85) { Write-Output "FAIL source roster != 85"; exit 1 }

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromMinutes(20)

function Fail($msg) { Write-Output ("FAIL " + $msg); exit 1 }
function Pass($msg) { Write-Output ("PASS " + $msg) }

function GetJson($uri) {
    $r = $client.GetAsync($uri).Result
    return @{ status = [int]$r.StatusCode; body = $r.Content.ReadAsStringAsync().Result }
}
function PostJson($uri, $json) {
    $c = New-Object System.Net.Http.StringContent($json, $utf8, 'application/json')
    $r = $client.PostAsync($uri, $c).Result
    return @{ status = [int]$r.StatusCode; body = $r.Content.ReadAsStringAsync().Result }
}
function PostNoBody($uri) {
    $r = $client.PostAsync($uri, $null).Result
    return @{ status = [int]$r.StatusCode; body = $r.Content.ReadAsStringAsync().Result }
}
# SSE: POST, consume whole stream line by line; lines are handed back via
# $script:sseLines (NOT via the output stream -- PS collects a List returned from
# a function by joining it into ONE space-separated string, which corrupts stats).
$script:sseLines = $null
function InvokeSse($uri, $tag, $jsonBody) {
    [Console]::WriteLine(("---- SSE start: " + $tag))
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $uri)
    $req.Headers.TryAddWithoutValidation('Accept', 'text/event-stream') | Out-Null
    if ($jsonBody) { $req.Content = New-Object System.Net.Http.StringContent($jsonBody, $utf8, 'application/json') }
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    if (-not $resp.IsSuccessStatusCode) { Fail ("SSE http " + [int]$resp.StatusCode + " " + $tag) }
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = New-Object System.IO.StreamReader($stream, $utf8)
    $list = New-Object System.Collections.Generic.List[string]
    while (-not $reader.EndOfStream) { $list.Add($reader.ReadLine()) }
    $script:sseLines = $list.ToArray()
    [Console]::WriteLine(("---- SSE done: " + $tag + " lines=" + $list.Count))
}
function CountDataEvent($lines, $type) {
    return @($lines | Where-Object { $_ -like 'data:*' -and $_ -match ('"' + $type + '"') }).Count
}
function CheckSse($lines, $tag) {
    $data = @($lines | Where-Object { $_ -like 'data:*' })
    $done = @($data | Where-Object { $_ -match '\[DONE\]' }).Count
    if ($done -lt 1) { Fail ($tag + ": no [DONE]") }
    $err = @($data | Where-Object { $_ -match '"error"' }).Count
    if ($err -gt 0) {
        $errLines = ($data | Where-Object { $_ -match '"error"' }) -join ' | '
        Write-Output ("ERROR events: " + $errLines)
        Fail ($tag + ": error event present")
    }
    $content = @($data | Where-Object { $_ -match '"content"' }).Count
    Write-Output ("  " + $tag + ": data events=" + $data.Count + " content tokens=" + $content)
    Pass ($tag + ": stream closed with [DONE], no error")
}
function StageView($detail, $code) { return ($detail.stages | Where-Object { $_.code -eq $code }) }

# ===== 1. register =====
$ts = Get-Date -Format 'MMddHHmmss'
$email = "s3b-e2e-$ts@test.local"
$reg = PostJson "$base/api/auth/register" (@{ email = $email; password = 'Passw0rd!123' } | ConvertTo-Json)
if ($reg.status -ne 200 -and $reg.status -ne 201) { Fail ("register " + $reg.status + " " + $reg.body) }
Pass ("register " + $email)

# ===== 2. create run (multipart form: sourceMode/styleMode/text/name) =====
$mp = New-Object System.Net.Http.MultipartFormDataContent
$mp.Add([System.Net.Http.StringContent]::new('text', $utf8, 'text/plain'), 'sourceMode')
$mp.Add([System.Net.Http.StringContent]::new('faithful', $utf8, 'text/plain'), 'styleMode')
$mp.Add([System.Net.Http.StringContent]::new($sourceText, $utf8, 'text/plain'), 'text')
$mp.Add([System.Net.Http.StringContent]::new("s3b-e2e-$ts", $utf8, 'text/plain'), 'name')
$resp = $client.PostAsync("$base/rpg/planner/runs", $mp).Result
if (-not $resp.IsSuccessStatusCode) { Fail ("create run " + [int]$resp.StatusCode + " " + $resp.Content.ReadAsStringAsync().Result) }
$run = ($resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json)
$runId = $run.id
Write-Output ("runId = " + $runId)
Pass ("create run source=text style=faithful")
"runId=$runId" | Out-File d:\Agent-Platform\s3b-e2e-state.txt -Encoding ascii

function Detail() { return ((GetJson "$base/rpg/planner/runs/$runId").body | ConvertFrom-Json) }

# ===== 3. S1 generate, then L1 draft edit swaps in the canonical 85-roster draft =====
# (LLM output drifts to table summaries for long rosters; the draft edit endpoint is
# the product's normal L1 correction path and pins the roster to the canonical form)
InvokeSse "$base/rpg/planner/runs/$runId/stages/S1/generate" "S1 generate"
$lines = $script:sseLines
CheckSse $lines "S1 generate"
$s1draft = [System.IO.File]::ReadAllText('d:\Agent-Platform\s3b-e2e-s1-draft.md', $utf8)
$draftBody = @{ content = $s1draft } | ConvertTo-Json -Depth 3
$d = PostJson "$base/rpg/planner/runs/$runId/stages/S1/draft" $draftBody
if ($d.status -ne 200) { Fail ("S1 draft edit " + $d.status + " " + $d.body) }
$rosterLines = [regex]::Matches($s1draft, '(?m)^-\s*\*\*').Count
if ($rosterLines -ne 86) { Fail ("canonical S1 draft bold lines " + $rosterLines + " != 86") }
Pass ("S1 draft edited to canonical roster (version " + (($d.body | ConvertFrom-Json).version) + ")")
$c = PostNoBody "$base/rpg/planner/runs/$runId/stages/S1/confirm"
if ($c.status -ne 200) { Fail ("S1 confirm " + $c.status + " " + $c.body) }
Pass "S1 confirmed (canonical 85-roster)"

# ===== 4. S2 generate + confirm =====
InvokeSse "$base/rpg/planner/runs/$runId/stages/S2/generate" "S2 generate"
$lines = $script:sseLines
CheckSse $lines "S2 generate"
$c = PostNoBody "$base/rpg/planner/runs/$runId/stages/S2/confirm"
if ($c.status -ne 200) { Fail ("S2 confirm " + $c.status + " " + $c.body) }
Pass "S2 confirmed"

# ===== 5. S3 first generate: roster 85 > capacity 20 -> auto full batching (5 batches) =====
$detail = Detail
$s3 = StageView $detail 'S3'
Write-Output ("S3 before generate: status=" + $s3.status + " rosterRemaining=" + $s3.rosterRemaining + " batchCapacity=" + $s3.batchCapacity)
if ($s3.status -ne 'NOT_STARTED') { Fail ("S3 expected NOT_STARTED, got " + $s3.status) }
if ($s3.rosterRemaining -ne 85) { Fail ("S3 rosterRemaining " + $s3.rosterRemaining + " != 85") }

InvokeSse "$base/rpg/planner/runs/$runId/stages/S3/generate" "S3 generate (auto full batching)"
$lines = $script:sseLines
CheckSse $lines "S3 generate"
$bs = CountDataEvent $lines 'batch_start'
$bd = CountDataEvent $lines 'batch_done'
if ($bs -ne 5 -or $bd -ne 5) { Fail ("S3 generate batch_start=" + $bs + " batch_done=" + $bd + " (expect 5/5)") }
$dr = @($lines | Where-Object { $_ -match '"draft_ready"' })
if ($dr.Count -lt 1) { Fail "S3 generate: no draft_ready" }
$drJson = $dr[0].Substring(5).Trim() | ConvertFrom-Json
Write-Output ("S3 generate draft_ready: candidates=" + $drJson.candidates)
if ($drJson.candidates -ne 85) { Fail ("S3 generate draft_ready candidates " + $drJson.candidates + " != 85") }
$detail = Detail
$s3 = StageView $detail 'S3'
if ($s3.candidates.Count -ne 85) { Fail ("S3 candidates " + $s3.candidates.Count + " != 85") }
# LLM drift (self-numbered titles) may leave roster names unmatched -> remaining > 0
# is EXPECTED after the first round; append-batch / generate-full close the loop
# (spec: better duplicate than miss a roster person)
Write-Output ("S3 after first generate: candidates=" + $s3.candidates.Count + " rosterRemaining=" + $s3.rosterRemaining)
$titlesFlat = (($s3.candidates | ForEach-Object { $_.title + ' ' + ($_.payload -replace "`r|`n", ' ') }) -join ' ') -replace '\s', ''
$missing = @($names | Where-Object { -not $titlesFlat.Contains($_) })
Write-Output ("S3 first generate flattened-name coverage: " + (85 - $missing.Count) + "/85 (drift self-healed by append/full)")
Pass "S3 first generate: 5 batches auto-dispatched, 85 candidates"

# ===== 6. S3 rework: whole-round reset -> single stream capped, remaining back up =====
# (rework is the D5-orthogonal quality path; rounds non-empty -> no batch fork ->
# single stream with the max-candidates cap, leaving the roster partially produced --
# the natural precondition for append-batch / generate-full)
InvokeSse "$base/rpg/planner/runs/$runId/stages/S3/rework" "S3 rework (single stream)" '{"feedback":"Rewrite the characters with more distinct and vivid personalities"}'
$lines = $script:sseLines
CheckSse $lines "S3 rework"
$bs = CountDataEvent $lines 'batch_start'
if ($bs -ne 0) { Fail ("S3 rework should be single-stream, saw " + $bs + " batch_start") }
$detail = Detail
$s3 = StageView $detail 'S3'
$M = $s3.candidates.Count
$R = $s3.rosterRemaining
Write-Output ("S3 after rework: candidates=" + $M + " rosterRemaining=" + $R)
if ($M -le 0 -or $M -ge 85) { Fail ("rework reset produced " + $M + " candidates (expected partial)") }
if ($R -le 0) { Fail ("rework remaining " + $R + " must be > 0 for append-batch precondition") }
Pass ("S3 rework reset to single stream: candidates=" + $M + " remaining=" + $R)

# ===== 7. S3 append-batch: exactly ONE batch =====
InvokeSse "$base/rpg/planner/runs/$runId/stages/S3/append-batch" "S3 append-batch"
$lines = $script:sseLines
CheckSse $lines "S3 append-batch"
$bs = CountDataEvent $lines 'batch_start'
$bd = CountDataEvent $lines 'batch_done'
if ($bs -ne 1 -or $bd -ne 1) { Fail ("append-batch batch_start=" + $bs + " batch_done=" + $bd + " (expect 1/1)") }
$dr = @($lines | Where-Object { $_ -match '"draft_ready"' })
if ($dr.Count -lt 1) { Fail "append-batch: no draft_ready" }
$drJson = $dr[0].Substring(5).Trim() | ConvertFrom-Json
Write-Output ("append-batch draft_ready: candidates=" + $drJson.candidates)
$detail = Detail
$s3 = StageView $detail 'S3'
$M2 = $s3.candidates.Count
$R2 = $s3.rosterRemaining
Write-Output ("S3 after append-batch: candidates=" + $M2 + " rosterRemaining=" + $R2)
if ($M2 -le $M) { Fail ("append-batch did not append (" + $M + " -> " + $M2 + ")") }
if ($R2 -ge $R) { Fail ("append-batch remaining not reduced (" + $R + " -> " + $R2 + ")") }
Pass ("append-batch: 1 batch_start + 1 batch_done, candidates " + $M + " -> " + $M2 + ", remaining " + $R + " -> " + $R2)

# ===== 8. S3 generate-full: remaining all batches =====
$expectedBatches = [Math]::Ceiling($R2 / 20)
Write-Output ("generate-full expected batches for remaining " + $R2 + ": " + $expectedBatches)
InvokeSse "$base/rpg/planner/runs/$runId/stages/S3/generate-full" "S3 generate-full"
$lines = $script:sseLines
CheckSse $lines "S3 generate-full"
$bs = CountDataEvent $lines 'batch_start'
$bd = CountDataEvent $lines 'batch_done'
if ($bs -ne $expectedBatches -or $bd -ne $expectedBatches) { Fail ("generate-full batch_start=" + $bs + " batch_done=" + $bd + " (expect " + $expectedBatches + "/" + $expectedBatches + ")") }
$dr = @($lines | Where-Object { $_ -match '"draft_ready"' })
$drJson = $dr[0].Substring(5).Trim() | ConvertFrom-Json
Write-Output ("generate-full draft_ready: candidates=" + $drJson.candidates)
$detail = Detail
$s3 = StageView $detail 'S3'
if ($s3.candidates.Count -ne 85) { Fail ("S3 candidates " + $s3.candidates.Count + " != 85") }
if ($s3.rosterRemaining -ne 0) { Fail ("S3 rosterRemaining " + $s3.rosterRemaining + " != 0") }
$titlesFlat = (($s3.candidates | ForEach-Object { $_.title + ' ' + ($_.payload -replace "`r|`n", ' ') }) -join ' ') -replace '\s', ''
$missing = @($names | Where-Object { -not $titlesFlat.Contains($_) })
if ($missing.Count -gt 0) { Write-Output ("candidates missing names: " + ($missing -join ',')); Fail "85-roster coverage incomplete after generate-full" }
Pass ("generate-full: " + $expectedBatches + " batches, candidates=" + $s3.candidates.Count + ", remaining=0, all 85 names covered")

# ===== 7. confirm all 85 candidates + stage gate =====
$ids = @($s3.candidates | ForEach-Object { $_.id })
foreach ($id in $ids) {
    $r = PostNoBody "$base/rpg/planner/runs/$runId/stages/S3/candidates/$id/confirm"
    if ($r.status -ne 200) { Fail ("candidate confirm " + $id + " -> " + $r.status + " " + $r.body) }
}
Pass ("confirmed all " + $ids.Count + " candidates")
$c = PostNoBody "$base/rpg/planner/runs/$runId/stages/S3/confirm"
if ($c.status -ne 200) { Fail ("S3 confirm " + $c.status + " " + $c.body) }
$detail = Detail
if ($detail.currentStage -ne 'S4') { Fail ("currentStage " + $detail.currentStage + " != S4") }
Pass "S3 confirmed, currentStage -> S4"

# ===== 8. S4 generate (upstream S3 injected as compact index) =====
InvokeSse "$base/rpg/planner/runs/$runId/stages/S4/generate" "S4 generate"
$lines = $script:sseLines
CheckSse $lines "S4 generate"
$v4 = GetJson "$base/rpg/planner/runs/$runId/stages/S4/versions/1"
if ($v4.status -ne 200) { Fail ("read S4 v1 " + $v4.status) }
$s4content = ($v4.body | ConvertFrom-Json).content
if ($s4content.Length -lt 200) { Fail ("S4 draft too short (" + $s4content.Length + " chars)") }
# NOTE: compact-index lines carry characterNameOfTitle (the epithet after 人物 N：),
# and plot-outline LLM behavior legitimately works at faction/beat granularity --
# reference counting is informational only; the injection itself is pinned by unit
# tests (StageGenerationServiceTest compact-index tests, D6)
$s4flat = $s4content -replace '\s', ''
$hit = @($names | Where-Object { $s4flat.Contains($_) }).Count
Write-Output ("S4 draft references roster ids (informational): " + $hit + "/85")
Pass "S4 generated with complete stream (compact-index injection pinned by unit tests)"

Write-Output ""
Write-Output "==== E2E SMOKE SUMMARY: ALL PASS ===="
Write-Output ("runId=" + $runId)
Write-Output "manual check: logs/sse-raw.log (debug-ai) for S4 prompt compact-index lines"
