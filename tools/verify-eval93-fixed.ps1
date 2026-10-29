# 9.3c final verification with FIXED findDivergences (sub_dimension-aligned join):
# stop service -> clear RUN4 over-captured divergence marks (3 rows) via H2 shell ->
# restart (fixed code + glm-4-7 judge) -> dynamic RUN5 on same gameState ->
# expect: ONLY relations rows on both ends get divergence_group; hard-facts/motivation/
# speech-style rows stay null; review-queue exposes divergence rows.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'
$dynRun = '774649a2-815e-42ad-807c-0fd9ee8c7572'
$run4 = '4936f0b2-5d75-4619-90b2-5c23689abdbf'
$gsId = '0ad5d71a-8880-4d6d-b529-35cdb6084793'

$key = ((Get-Content 'd:\Agent-Platform\src\main\resources\application-local.yml' | Where-Object { $_ -notmatch '^\s*#' } | Select-String -Pattern 'api-key: "(.+)"')[0].Matches[0].Groups[1].Value)

# 1) stop service (releases H2 lock; old code had the over-capturing SQL)
$owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -First 1
if ($owner) { Stop-Process -Id $owner -Force; Write-Output "killed $owner"; Start-Sleep -Seconds 3 } else { Write-Output 'no listener' }

# 2) clear RUN4 over-captured marks (relations row keeps its mark: it IS a real divergence end)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$sql = "UPDATE rpg_eval_record SET divergence_group = NULL WHERE run_id='$run4' AND subject='kagetora' AND sub_dimension IN ('hard-facts','motivation','speech-style'); SELECT sub_dimension, verdict, divergence_group FROM rpg_eval_record WHERE run_id='$run4' AND subject='kagetora';"
& "$env:JAVA_HOME\bin\java.exe" -cp 'C:\Users\62754\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar' org.h2.tools.Shell -url 'jdbc:h2:file:d:/Agent-Platform/data/agent-platform' -user sa -sql $sql

# 3) restart with fixed code + glm-4-7 judge (spring-boot:run recompiles main sources)
$saj = '{"spring":{"profiles":{"active":"local"}},"agent":{"models":{"glm-4-7":{"base-url":"https://open.bigmodel.cn/api/paas/v4","api-key":"' + $key + '","model":"glm-4.7","max-tokens":32768,"timeout":"180s","roles":["judge"]}}}}'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
$env:SPRING_APPLICATION_JSON = $saj
Start-Process -FilePath 'cmd.exe' -WindowStyle Hidden -WorkingDirectory 'd:\Agent-Platform' -ArgumentList '/c d:\Agent-Platform\mvnw.cmd spring-boot:run > "d:\Agent-Platform\startup-eval-93d.log" 2>&1'
$ok = $false; $t = 0
while ($t -lt 80 -and -not $ok) { $t++; Start-Sleep -Seconds 4; try { $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $ok = $true; Write-Output "HEALTH UP after $($t*4)s" } } catch { } }
if (-not $ok) { throw 'service not up' }

# 4) login + RUN5
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null
$rb = [System.Text.Encoding]::UTF8.GetBytes('{"layer":"dynamic","gameStateId":"' + $gsId + '"}')
$run = Invoke-RestMethod -Method Post -Uri "$base/rpg/eval/run" -WebSession $s -ContentType 'application/json' -Body $rb
$runId = $run.runId
Write-Output "RUN5 (dynamic, glm-4-7, fixed-join test): $runId"

# 5) poll done
$deadline = (Get-Date).AddSeconds(420)
$status = $null; $r = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 10
    $r = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$runId" -WebSession $s
    $status = $r.status
    $n = 0; if ($r.records) { $n = @($r.records).Count }
    Write-Output ("poll: status={0} records={1}" -f $status, $n)
    if ($status -eq 'done' -or $status -eq 'failed') { break }
}
if ($status -ne 'done') { throw "run not done: $status" }
Write-Output ("DONE: judge={0} summary={1}" -f $r.judgeModel, ($r.summary | ConvertTo-Json -Compress))
Write-Output "--- RUN5 records (expect: only relations has group, others null) ---"
foreach ($rec in $r.records) {
    Write-Output ("  rec: sub={0} verdict={1} group={2}" -f $rec.subDimension, $rec.verdict, $rec.divergenceGroup)
}

# 6) DYN-MAIN records (expect: relations row keeps group; others null)
$rOld = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$dynRun?subject=kagetora" -WebSession $s
Write-Output "--- DYN-MAIN (seeded) records ---"
foreach ($rec in $rOld.records) {
    Write-Output ("  rec: sub={0} verdict={1} group={2}" -f $rec.subDimension, $rec.verdict, $rec.divergenceGroup)
}

# 7) review queue divergence rows
$q = Invoke-RestMethod -Uri "$base/rpg/eval/review-queue?limit=200" -WebSession $s
$dg = @($q | Where-Object { $_.divergenceGroup })
Write-Output ("review-queue total: {0}  divergence-marked: {1}" -f @($q).Count, $dg.Count)
foreach ($d in ($dg | Select-Object -First 12)) {
    Write-Output ("  DIVERGE: subject={0} sub={1} verdict={2} group={3}" -f $d.subject, $d.subDimension, $d.verdict, $d.divergenceGroup)
}
