$ErrorActionPreference = 'Stop'
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
$root = 'd:\Agent-Platform\oaicore-verify2'
$targets = @(
    'com.openai.core.http.RetryingHttpClient',
    'com.openai.core.http.LoggingHttpClient',
    'com.openai.core.http.WorkloadIdentityHttpClient',
    'com.openai.core.http.PhantomReachableClosingHttpClient',
    'com.openai.core.ClientOptions$Builder'
)
foreach ($t in $targets) {
    Write-Output "===== $t ====="
    & $javap -c -p -classpath $root $t 2>&1 | Select-String -Pattern 'getTimeout|Timeout|timeout' | ForEach-Object { $_.Line.Trim() } | Select-Object -First 15
}
