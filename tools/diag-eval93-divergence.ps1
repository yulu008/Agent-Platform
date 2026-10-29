# 9.3c diagnosis: compare run1 (glm-5.2) vs run2 (glm-4.7) records on alignment keys
# (subject + dimension + unitJson), verdict agreement, judge fingerprint (tasks 9.3)
param(
    [string]$Run1 = 'de829e4a-1d61-40a7-8932-b3fd3513d141',
    [string]$Run2 = '72e288b2-2789-4ce8-8abd-a9ad842e7bcf'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'

$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null

function Get-RunMap([string]$runId) {
    $r = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$runId" -WebSession $s
    $map = @{}
    $fps = @{}
    foreach ($rec in $r.records) {
        $k = "{0}|{1}|{2}" -f $rec.subject, $rec.dimension, $rec.subDimension
        if ($rec.subDimension) { $map[$k] = $rec }
        $fps[$rec.judgeFingerprint] = $true
    }
    Write-Host ("run {0}: judgeModel={1} records={2} verdictRows={3} fingerprints={4}" -f `
        $runId.Substring(0,8), $r.judgeModel, @($r.records).Count, $map.Count, ($fps.Keys -join ','))
    return $map
}

$m1 = Get-RunMap $Run1
$m2 = Get-RunMap $Run2

$sha = [System.Security.Cryptography.SHA256]::Create()
function Hash16([string]$s) {
    if ($null -eq $s) { return '<null>' }
    $h = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($s))
    $hex = ($h | ForEach-Object { $_.ToString('x2') }) -join ''
    return $hex.Substring(0, 16)
}

$common = @($m1.Keys | Where-Object { $m2.ContainsKey($_) })
Write-Output ("common alignment keys (subject|dimension|sub): {0}" -f $common.Count)

$unitSame = 0; $unitDiff = 0; $verdictDiff = 0; $verdictDiffAligned = 0
$diffList = New-Object System.Collections.Generic.List[string]
foreach ($k in $common) {
    $r1 = $m1[$k]; $r2 = $m2[$k]
    $same = ($r1.unitJson -eq $r2.unitJson)
    if ($same) { $unitSame++ } else { $unitDiff++ }
    if (-not $same) {
        $diffList.Add(("UNIT-DIFF {0}: len1={1} hash1={2} len2={3} hash2={4}" -f $k, `
            $r1.unitJson.Length, (Hash16 $r1.unitJson), $r2.unitJson.Length, (Hash16 $r2.unitJson)))
    }
    $v1 = $r1.verdict; $v2 = $r2.verdict
    if ($v1 -ne $v2) {
        $verdictDiff++
        if ($same) { $verdictDiffAligned++ }
    }
}
Write-Output ("unitJson same: {0}  diff: {1}" -f $unitSame, $unitDiff)
if ($diffList.Count -gt 0) {
    Write-Output "--- unit diffs (first 10) ---"
    $diffList | Select-Object -First 10 | ForEach-Object { Write-Output $_ }
}
Write-Output ("verdict mismatch among common: {0} (of which unitJson-aligned: {1} -> expected divergence pairs)" -f $verdictDiff, $verdictDiffAligned)

# sample one unitJson to see its structure
$k0 = $common[0]
Write-Output "--- sample unitJson (key: $k0, len=$($m1[$k0].unitJson.Length)) first 600 chars ---"
$j = $m1[$k0].unitJson
if ($j.Length -gt 600) { $j = $j.Substring(0, 600) + '...' }
Write-Output $j
