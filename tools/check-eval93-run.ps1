# 9.3c verify second-judge run: summary detail, verdict distribution, divergenceGroup marks (tasks 9.3)
param([string]$RunId = '72e288b2-2789-4ce8-8abd-a9ad842e7bcf')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'

$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null

$r = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$RunId" -WebSession $s
Write-Output ("status: {0}  judgeModel: {1}  rubricVersion: {2}" -f $r.status, $r.judgeModel, $r.rubricVersion)
Write-Output ("summary: {0}" -f ($r.summary | ConvertTo-Json -Compress))
Write-Output ("costTokens: {0}  costAmountMicro: {1}" -f $r.costTokens, $r.costAmountMicro)

# verdict distribution
$verdicts = @{}
foreach ($rec in $r.records) {
    $v = $rec.verdict
    if ($null -eq $v) { $v = '<null>' }
    if (-not $verdicts.ContainsKey($v)) { $verdicts[$v] = 0 }
    $verdicts[$v]++
}
Write-Output ("verdict dist: {0}" -f ($verdicts | ConvertTo-Json -Compress))

# invalid reasons
$invalids = @($r.records | Where-Object { $_.invalidReason })
Write-Output ("invalid records: {0}" -f $invalids.Count)
foreach ($i in $invalids) {
    Write-Output ("  - subject={0} sub={1} reason={2}" -f $i.subjectLabel, $i.subDimension, $i.invalidReason)
}

# divergence group marks
$dg = @($r.records | Where-Object { $_.divergenceGroup })
Write-Output ("records with divergenceGroup: {0}" -f $dg.Count)
foreach ($d in $dg) {
    Write-Output ("  - subject={0} sub={1} verdict={2} group={3}" -f $d.subjectLabel, $d.subDimension, $d.verdict, $d.divergenceGroup)
}
