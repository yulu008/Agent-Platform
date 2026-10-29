$ErrorActionPreference = 'Stop'
$runDir = 'C:\Users\62754\.agent\tenants\default\planner-runs\5ffd6d5b-1c82-45f8-8a10-0a5058557d7f'
# S1 名册（人物全量名册 section 的 - **名**： 行）
$s1 = Get-Content "$runDir\01-ip-analysis.md" -Encoding UTF8 -Raw
$rosterNames = [System.Collections.Generic.List[string]]::new()
foreach ($m in [regex]::Matches($s1, '(?m)^\s*-\s*\*\*(.+?)\*\*')) { $rosterNames.Add($m.Groups[1].Value.Trim()) }
Write-Output ("roster names: " + $rosterNames.Count)
# manifest 候选标题集合
$manifest = Get-Content "$runDir\manifest.yaml" -Encoding Default -Raw
$titles = [regex]::Matches($manifest, 'id: candidate-\d+, title: ([^,]+),') | ForEach-Object { $_.Groups[1].Value.Trim() }
Write-Output ("manifest candidates: " + $titles.Count)
# 名册人名是否被任一候选标题包含（与 rosterHitNames 语义近似）
$missing = @()
foreach ($n in $rosterNames) {
    $hit = $false
    foreach ($t in $titles) { if ($t.Contains($n)) { $hit = $true; break } }
    if (-not $hit) { $missing += $n }
}
Write-Output ("uncovered roster names: " + $missing.Count)
$missing | ForEach-Object { Write-Output ("  MISSING: " + $_) }
# 空壳卡（标题无有效人名）
$shells = $titles | Where-Object { $_ -match '^人物\s*\d*\s*[：:]?\s*$' -or $_ -match '^人物\s*\d+\s*[：:]?$' }
Write-Output ("shell cards: " + $shells.Count)
$shells | ForEach-Object { Write-Output ("  SHELL: " + $_) }
