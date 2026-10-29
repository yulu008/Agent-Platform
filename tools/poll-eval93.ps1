# 9.3 poll second-judge run until done, then print summary (tasks 9.3)
param([string]$RunId = '72e288b2-2789-4ce8-8abd-a9ad842e7bcf', [int]$TimeoutSeconds = 1500)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'

$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$status = $null
$r = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    $r = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$RunId" -WebSession $s
    $status = $r.status
    $recCount = 0
    if ($r.records) { $recCount = @($r.records).Count }
    Write-Output ("poll: status={0} records={1}" -f $status, $recCount)
    if ($status -eq 'done' -or $status -eq 'failed') { break }
}
if ($status -ne 'done') { throw "run not done: $status" }

$sum = $r.summary
Write-Output ("DONE: judge={0} rubric={1} total={2} pass={3} borderline={4} fail={5} invalid={6} invalidRatePct={7} tokens={8} amountMicro={9}" -f $r.judgeModel, $r.rubricVersion, $sum.total, $sum.pass, $sum.borderline, $sum.fail, $sum.invalid, $sum.invalidRatePct, $r.costTokens, $r.costAmountMicro)
