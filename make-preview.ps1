$ErrorActionPreference = 'Stop'
$files = @(
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\StageGateServiceTest.java',
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\PlannerWorldInstallerTest.java',
  'd:\Agent-Platform\src\test\java\com\luyu\agent\rpg\planner\service\PlannerInstallEndToEndTest.java'
)
foreach ($f in $files) {
  $bytes = [System.IO.File]::ReadAllBytes($f)
  # lenient decode: invalid sequences -> U+FFFD, then write clean UTF-8 preview
  $text = [System.Text.Encoding]::UTF8.GetString($bytes)
  # also mark: append byte offset map is too noisy; preview only
  $dest = $f + '.preview.txt'
  [System.IO.File]::WriteAllText($dest, $text, (New-Object System.Text.UTF8Encoding($false)))
  Write-Output ('preview: ' + $dest + '  FFFD count: ' + ([regex]::Matches($text, [char]0xFFFD)).Count)
}
