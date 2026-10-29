# content-eval-admin-ui 6.1 manual-verify boot:
# kill 8080, read ACTIVE api-key (skip commented lines), inject glm-4-7 judge entry
# via SPRING_APPLICATION_JSON, restart with fresh target/classes (template+static hot),
# wait health. No login/materialize here - browser walkthrough does the rest.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -First 1
if ($owner) { Stop-Process -Id $owner -Force; Write-Output "killed $owner"; Start-Sleep -Seconds 2 } else { Write-Output 'no listener' }

# active key = first NON-commented api-key line in application-local.yml
$key = ((Get-Content 'd:\Agent-Platform\src\main\resources\application-local.yml' | Where-Object { $_ -notmatch '^\s*#' } | Select-String -Pattern 'api-key: "(.+)"')[0].Matches[0].Groups[1].Value)
Write-Output "KEY-LEN: $($key.Length) PREFIX: $($key.Substring(0,8))"

$saj = '{"spring":{"profiles":{"active":"local"}},"agent":{"models":{"glm-4-7":{"base-url":"https://open.bigmodel.cn/api/paas/v4","api-key":"' + $key + '","model":"glm-4.7","max-tokens":32768,"timeout":"180s","roles":["judge"]}}}}'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
$env:SPRING_APPLICATION_JSON = $saj
Write-Output "BOOT: judge=glm-4-7 key-prefix=$($key.Substring(0,8))"

Start-Process -FilePath 'cmd.exe' -WindowStyle Hidden -WorkingDirectory 'd:\Agent-Platform' -ArgumentList '/c d:\Agent-Platform\mvnw.cmd spring-boot:run > "d:\Agent-Platform\startup-eval-admin-ui.log" 2>&1'

$ok = $false; $t = 0
while ($t -lt 60 -and -not $ok) { $t++; Start-Sleep -Seconds 4; try { $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $ok = $true; Write-Output "HEALTH UP after $($t*4)s" } } catch { } }
if (-not $ok) { throw 'service not up' }
Write-Output 'READY for browser walkthrough'
