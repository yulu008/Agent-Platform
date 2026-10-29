param([string]$Test, [string]$Log = 'd:\Agent-Platform\test-run.log')
Set-Location d:\Agent-Platform
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
if ([string]::IsNullOrEmpty($Test)) {
    .\mvnw.cmd test -q > $Log 2>&1
} else {
    .\mvnw.cmd test "-Dtest=$Test" -q > $Log 2>&1
}
Write-Output "exit=$LASTEXITCODE log=$Log"
