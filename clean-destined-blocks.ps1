$ErrorActionPreference = 'Stop'
$src = 'C:\Users\62754\.agent\tenants\default\planner-runs\5ffd6d5b-1c82-45f8-8a10-0a5058557d7f\03-characters'
$bak = 'd:\Agent-Platform\t5b-characters-backup'
if (-not (Test-Path $bak)) { New-Item -ItemType Directory -Path $bak | Out-Null }
# 备份原卡（run 目录外，避免应用扫描混入）
Copy-Item -Path (Join-Path $src '*.md') -Destination $bak -Force

# 「### 命定档案」用字符码构造，规避 ps1 无 BOM 时中文被按 ANSI 读取的坑
$marker = '### ' + [char]0x547D + [char]0x5B9A + [char]0x6863 + [char]0x6848
$utf8 = New-Object System.Text.UTF8Encoding($false)
$cleaned = 0; $scanned = 0
Get-ChildItem $src -Filter 'candidate-*.md' | ForEach-Object {
    $scanned++
    $text = [System.IO.File]::ReadAllText($_.FullName, $utf8)
    $idx = $text.IndexOf($marker)
    if ($idx -lt 0) { return }
    $before = $text.Substring(0, $idx)
    $rest = $text.Substring($idx + $marker.Length)
    # 子节后若还有后续 ## / ### 标题则保留其后内容（防御：当前数据均为文件尾）
    $m = [regex]::Match($rest, '(?m)^#{2,3}\s')
    if ($m.Success) { $before += $rest.Substring($m.Index) }
    # 保持原行尾风格，去尾部空行、保留单个结尾换行
    $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $before = $before.TrimEnd("`r", "`n") + $eol
    [System.IO.File]::WriteAllText($_.FullName, $before, $utf8)
    $cleaned++
}
Write-Output ("scanned={0} cleaned={1} backup={2}" -f $scanned, $cleaned, $bak)
