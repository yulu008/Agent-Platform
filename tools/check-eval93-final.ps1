# 9.3c final checks: DYN-MAIN records + review queue (uses ${var} interpolation to avoid $var? parsing)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'
$dynRun = '774649a2-815e-42ad-807c-0fd9ee8c7572'

$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null

$rOld = Invoke-RestMethod -Uri "${base}/rpg/eval/runs/${dynRun}?subject=kagetora" -WebSession $s
Write-Output "--- DYN-MAIN (seeded) records ---"
foreach ($rec in $rOld.records) {
    Write-Output ("  rec: sub={0} verdict={1} group={2}" -f $rec.subDimension, $rec.verdict, $rec.divergenceGroup)
}

$q = Invoke-RestMethod -Uri "$base/rpg/eval/review-queue?limit=200" -WebSession $s
$dg = @($q | Where-Object { $_.divergenceGroup })
Write-Output ("review-queue total: {0}  divergence-marked: {1}" -f @($q).Count, $dg.Count)
foreach ($d in ($dg | Select-Object -First 12)) {
    Write-Output ("  DIVERGE: subject={0} sub={1} verdict={2} group={3}" -f $d.subject, $d.subDimension, $d.verdict, $d.divergenceGroup)
}
