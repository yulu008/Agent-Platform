$ErrorActionPreference = 'Stop'
$root = 'd:\Agent-Platform\oaicore-verify2'
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
$dir = Join-Path $root 'com\openai\services\blocking'
$files = Get-ChildItem $dir -Filter '*.class' | Where-Object { $_.Name -match '^Chat|^Completions' }
foreach ($f in $files) {
    $cn = 'com.openai.services.blocking.' + $f.BaseName
    $out = & $javap -c -p -classpath $root $cn 2>&1 |
        Select-String -Pattern 'Timeout|RequestOptions' | ForEach-Object { $_.Line.Trim() }
    if ($out) {
        Write-Output "===== $($f.Name) ====="
        $out | Select-Object -First 12
    }
}
Write-Output 'scan-done'
