param([string]$Log = 'd:\Agent-Platform\startup.log')
$ErrorActionPreference = 'Stop'
# kill port-8080 owner (app) + stale maven hosts from today's runs
try {
    $owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -First 1
    if ($owner) {
        Write-Output "killing app pid $owner"
        Stop-Process -Id $owner -Force
        Start-Sleep -Seconds 2
    } else {
        Write-Output 'no listener on 8080'
    }
} catch { Write-Output "kill failed: $($_.Exception.Message)" }
Set-Location d:\Agent-Platform
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
.\mvnw.cmd spring-boot:run > $Log 2>&1
