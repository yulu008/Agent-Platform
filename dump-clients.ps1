$ErrorActionPreference = 'Stop'
$root = 'd:\Agent-Platform\oaicore-verify2'
$files = Get-ChildItem (Join-Path $root 'com\openai\client') -Filter '*.class'
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
foreach ($f in $files) {
    $cn = 'com.openai.client.' + $f.BaseName
    $out = & $javap -c -p -classpath $root $cn 2>&1 | Select-String -Pattern 'ClientOptions.*timeout|getTimeout|RequestOptions' | ForEach-Object { $_.Line.Trim() }
    if ($out) {
        Write-Output "===== $cn ====="
        $out | Select-Object -First 8
    }
}
