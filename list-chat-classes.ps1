$ErrorActionPreference = 'Stop'
$root = 'd:\Agent-Platform\oaicore-verify2'
Get-ChildItem (Join-Path $root 'com\openai\client') -Filter '*.class' |
    Where-Object { $_.Name -match 'Chat|Completion' } |
    ForEach-Object { Write-Output $_.Name }
