$ErrorActionPreference = 'Stop'
$targets = @{
  'StageGateServiceTest.java' = @(1668, 1719, 1778, 2908, 3478, 4517, 5602, 6475, 11170, 14045, 15384, 17262)
  'PlannerWorldInstallerTest.java' = @(2301, 3424, 3645, 4088, 4232, 4379, 5700, 8272, 9415, 10643)
  'PlannerInstallEndToEndTest.java' = @(2388, 2566, 9176, 13022, 13243, 13476, 13830, 14870, 15129)
}
$base = 'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\'
$sb = New-Object System.Text.StringBuilder
foreach ($fname in $targets.Keys) {
  $bytes = [System.IO.File]::ReadAllBytes($base + $fname)
  [void]$sb.AppendLine('=== ' + $fname)
  foreach ($pos in ($targets[$fname] | Sort-Object)) {
    $s = [Math]::Max(0, $pos - 16)
    $e = [Math]::Min($bytes.Length - 1, $pos + 16)
    $hex = ($bytes[$s..$e] | ForEach-Object { $_.ToString('X2') }) -join ' '
    [void]$sb.AppendLine(('  @{0}: {1}' -f $pos, $hex))
  }
}
[System.IO.File]::WriteAllText('d:\Agent-Platform\dump-result.txt', $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'done'
