# 9.3 dynamic-layer second-judge run: same gameState (kagetora, figureKey-stable subject),
# unitJson identical to DYN-MAIN 774649a2 (glm-5.2) -> verdict mismatch will form divergence pairs (tasks 9.3)
param([int]$TimeoutSeconds = 420)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'
$gsId = '0ad5d71a-8880-4d6d-b529-35cdb6084793'

$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null

$rb = [System.Text.Encoding]::UTF8.GetBytes('{"layer":"dynamic","gameStateId":"' + $gsId + '"}')
$run = Invoke-RestMethod -Method Post -Uri "$base/rpg/eval/run" -WebSession $s -ContentType 'application/json' -Body $rb
$runId = $run.runId
Write-Output "RUN3 (dynamic, judge=glm-4-7): $runId"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$status = $null; $r = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $r = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$runId" -WebSession $s
    $status = $r.status
    $n = 0; if ($r.records) { $n = @($r.records).Count }
    Write-Output ("poll: status={0} records={1}" -f $status, $n)
    if ($status -eq 'done' -or $status -eq 'failed') { break }
}
if ($status -ne 'done') { throw "run not done: $status" }

Write-Output ("DONE: judge={0} rubric={1} summary={2}" -f $r.judgeModel, $r.rubricVersion, ($r.summary | ConvertTo-Json -Compress))
Write-Output ("costTokens={0} amountMicro={1}" -f $r.costTokens, $r.costAmountMicro)
foreach ($rec in $r.records) {
    Write-Output ("  rec: subject={0} sub={1} verdict={2} group={3} fingerprint={4}" -f `
        $rec.subject, $rec.subDimension, $rec.verdict, $rec.divergenceGroup, $rec.judgeFingerprint)
    if ($rec.targetFingerprint) { Write-Output ("       targetFingerprint={0}" -f $rec.targetFingerprint) }
}

# review queue: divergence-marked rows
$q = Invoke-RestMethod -Uri "$base/rpg/eval/review-queue?limit=200" -WebSession $s
$dg = @($q | Where-Object { $_.divergenceGroup })
Write-Output ("review-queue total: {0}  divergence-marked: {1}" -f @($q).Count, $dg.Count)
foreach ($d in ($dg | Select-Object -First 12)) {
    Write-Output ("  DIVERGE: subject={0} sub={1} verdict={2} group={3}" -f $d.subject, $d.subDimension, $d.verdict, $d.divergenceGroup)
}
