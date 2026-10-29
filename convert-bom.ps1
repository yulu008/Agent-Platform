param([string]$Target)
$p = $Target
if ([string]::IsNullOrEmpty($p)) { $p = 'd:\Agent-Platform\smoke-52.ps1' }
[System.IO.File]::WriteAllText($p, [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8), (New-Object System.Text.UTF8Encoding $true))
Write-Output "converted to UTF8-BOM: $p"
