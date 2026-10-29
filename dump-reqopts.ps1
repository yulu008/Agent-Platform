$ErrorActionPreference = 'Stop'
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
$root = 'd:\Agent-Platform\oaicore-verify2'
& $javap -c -p -classpath $root 'com.openai.core.RequestOptions$Companion' 2>&1 |
    Out-File d:\Agent-Platform\requestoptions-companion-dump2.txt -Encoding utf8
Select-String -Path d:\Agent-Platform\requestoptions-companion-dump2.txt -Pattern 'from|timeout|Timeout|ClientOptions' |
    Select-Object -First 25 | ForEach-Object { Write-Output $_.Line.Trim() }
