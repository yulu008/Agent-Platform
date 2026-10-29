$ErrorActionPreference = 'Stop'
$ver = 'C:\Users\62754\.agent\tenants\default\planner-runs\5ffd6d5b-1c82-45f8-8a10-0a5058557d7f\03-characters\versions'
$bak = 'd:\Agent-Platform\t5b-characters-backup\versions'
if (-not (Test-Path $bak)) { New-Item -ItemType Directory -Path $bak | Out-Null }
Copy-Item -Path (Join-Path $ver '*.md') -Destination $bak -Force

# 「### 命定档案」用字符码构造，规避 ps1 无 BOM 中文编码坑
$marker = '### ' + [char]0x547D + [char]0x5B9A + [char]0x6863 + [char]0x6848
$utf8 = New-Object System.Text.UTF8Encoding($false)
Get-ChildItem $ver -Filter '*.md' | ForEach-Object {
    $text = [System.IO.File]::ReadAllText($_.FullName, $utf8)
    $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $blocks = 0
    # 循环删除所有命定档案块：标记行起，至下一个 ## / ### 标题或文件尾
    while ($true) {
        $idx = $text.IndexOf($marker)
        if ($idx -lt 0) { break }
        $before = $text.Substring(0, $idx)
        $rest = $text.Substring($idx + $marker.Length)
        $m = [regex]::Match($rest, '(?m)^#{2,3}\s')
        if ($m.Success) {
            $text = $before + $rest.Substring($m.Index)
        } else {
            $text = $before.TrimEnd("`r", "`n") + $eol
        }
        $blocks++
    }
    if ($blocks -gt 0) {
        [System.IO.File]::WriteAllText($_.FullName, $text, $utf8)
        Write-Output ("{0}: removed {1} blocks" -f $_.Name, $blocks)
    } else {
        Write-Output ("{0}: no block" -f $_.Name)
    }
}
Write-Output 'versions-clean-done'
