$ErrorActionPreference = 'Stop'
$files = @(
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\StageGateServiceTest.java',
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\PlannerWorldInstallerTest.java',
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\PlannerInstallEndToEndTest.java'
)
$sb = New-Object System.Text.StringBuilder
foreach ($f in $files) {
  $bytes = [System.IO.File]::ReadAllBytes($f)
  $n = $bytes.Length
  [void]$sb.AppendLine('=== ' + (Split-Path $f -Leaf) + '  size=' + $n)
  $i = 0
  $found = 0
  while ($i -lt $n) {
    $b = $bytes[$i]
    if ($b -lt 0x80) { $i++; continue }
    if ($b -ge 0xC2 -and $b -le 0xDF) { $need = 1 }
    elseif ($b -ge 0xE0 -and $b -le 0xEF) { $need = 2 }
    elseif ($b -ge 0xF0 -and $b -le 0xF4) { $need = 3 }
    else {
      [void]$sb.AppendLine(('  @{0}: invalid lead 0x{1:X2}' -f $i, $b)); $found++; $i++; continue
    }
    $ok = $true; $j = 1
    for ($j = 1; $j -le $need; $j++) {
      $cb = $bytes[$i + $j]
      if ($cb -lt 0x80 -or $cb -gt 0xBF) { $ok = $false; break }
    }
    if ($ok) { $i += $need + 1; continue }
    $start = [Math]::Max(0, $i - 34)
    $end = [Math]::Min($n - 1, $i + 34)
    $seg = New-Object byte[] ($end - $start + 1)
    [Array]::Copy($bytes, $start, $seg, 0, $seg.Length)
    $ctx = [System.Text.Encoding]::UTF8.GetString($seg)
    $line = 1
    for ($k = 0; $k -lt $i; $k++) { if ($bytes[$k] -eq 0x0A) { $line++ } }
    [void]$sb.AppendLine(('  @{0} L{1}: {2:X2} {3:X2} 3F' -f $i, $line, $b, $bytes[$i + 1]))
    [void]$sb.AppendLine('    ' + $ctx)
    $found++
    $i += $j
  }
  [void]$sb.AppendLine('  total issues: ' + $found)
}
[System.IO.File]::WriteAllText("d:\Agent-Platform\scan-result.txt", $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'done'
