# 9.3 second-judge restart (tasks 9.3):
# kill 8080, read ACTIVE api-key (skip commented lines, Select-String anchor bug workaround),
# inject glm-4-7 full model entry via SPRING_APPLICATION_JSON, restart, wait health,
# login admin, materialize preset world, trigger static eval run, print runId.
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

Start-Process -FilePath 'cmd.exe' -WindowStyle Hidden -WorkingDirectory 'd:\Agent-Platform' -ArgumentList '/c d:\Agent-Platform\mvnw.cmd spring-boot:run > "d:\Agent-Platform\startup-eval-93b.log" 2>&1'

$ok = $false; $t = 0
while ($t -lt 60 -and -not $ok) { $t++; Start-Sleep -Seconds 4; try { $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $ok = $true; Write-Output "HEALTH UP after $($t*4)s" } } catch { } }
if (-not $ok) { throw 'service not up' }

# login as seeded admin (AuthBootstrapProperties)
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/auth/login' -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null
Write-Output 'login ok (admin@default.local)'

# idempotent materialize (same world row as 9.1 run de829e4a)
$world = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/rpg/preset/materialize' -WebSession $s -ContentType 'application/json' -Body '{"presetKey":"adventure"}'
Write-Output "worldId: $($world.worldId)"

# trigger static-layer run with SECOND judge (glm-4-7)
$rb = [System.Text.Encoding]::UTF8.GetBytes('{"layer":"static","worldId":"' + $world.worldId + '"}')
$run = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/rpg/eval/run' -WebSession $s -ContentType 'application/json' -Body $rb
Write-Output "RUN: $($run.runId)"
