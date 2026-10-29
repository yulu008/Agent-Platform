# 9.3c simulated divergence seed + rerun (tasks 9.3):
# both real judges (glm-5.2 / glm-4.7) scored identically on available data, so no natural
# divergence occurred. Seed one simulated disagreement: flip DYN-MAIN (glm-5.2, run 774649a2)
# kagetora/relations verdict pass->borderline offline via H2 shell, then rerun the dynamic
# layer with glm-4-7 judge -> findDivergences must pair both ends (same subject+dimension+
# unit_json, different judge_fingerprint, different verdict) and mark divergence_group;
# review-queue must expose the divergence rows.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'
$dynRun = '774649a2-815e-42ad-807c-0fd9ee8c7572'
$gsId = '0ad5d71a-8880-4d6d-b529-35cdb6084793'

# active key = first NON-commented api-key line in application-local.yml
$key = ((Get-Content 'd:\Agent-Platform\src\main\resources\application-local.yml' | Where-Object { $_ -notmatch '^\s*#' } | Select-String -Pattern 'api-key: "(.+)"')[0].Matches[0].Groups[1].Value)

# 1) stop service to release the H2 file lock
$owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -First 1
if ($owner) { Stop-Process -Id $owner -Force; Write-Output "killed $owner"; Start-Sleep -Seconds 3 } else { Write-Output 'no listener' }

# 2) seed simulated divergence via H2 shell (offline)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$sql = "UPDATE rpg_eval_record SET verdict='borderline' WHERE run_id='$dynRun' AND subject='kagetora' AND sub_dimension='relations' AND verdict='pass'; SELECT id, sub_dimension, verdict, judge_fingerprint, divergence_group FROM rpg_eval_record WHERE run_id='$dynRun' AND subject='kagetora';"
& "$env:JAVA_HOME\bin\java.exe" -cp 'C:\Users\62754\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar' org.h2.tools.Shell -url 'jdbc:h2:file:d:/Agent-Platform/data/agent-platform' -user sa -sql $sql

# 3) restart service with glm-4-7 as judge
$saj = '{"spring":{"profiles":{"active":"local"}},"agent":{"models":{"glm-4-7":{"base-url":"https://open.bigmodel.cn/api/paas/v4","api-key":"' + $key + '","model":"glm-4.7","max-tokens":32768,"timeout":"180s","roles":["judge"]}}}}'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
$env:SPRING_APPLICATION_JSON = $saj
Start-Process -FilePath 'cmd.exe' -WindowStyle Hidden -WorkingDirectory 'd:\Agent-Platform' -ArgumentList '/c d:\Agent-Platform\mvnw.cmd spring-boot:run > "d:\Agent-Platform\startup-eval-93c.log" 2>&1'
$ok = $false; $t = 0
while ($t -lt 60 -and -not $ok) { $t++; Start-Sleep -Seconds 4; try { $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $ok = $true; Write-Output "HEALTH UP after $($t*4)s" } } catch { } }
if (-not $ok) { throw 'service not up' }

# 4) login + trigger dynamic rerun
$bytes = [System.Text.Encoding]::UTF8.GetBytes((@{ email = 'admin@default.local'; password = '123456' } | ConvertTo-Json))
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $bytes -SessionVariable s | Out-Null
$rb = [System.Text.Encoding]::UTF8.GetBytes('{"layer":"dynamic","gameStateId":"' + $gsId + '"}')
$run = Invoke-RestMethod -Method Post -Uri "$base/rpg/eval/run" -WebSession $s -ContentType 'application/json' -Body $rb
$runId = $run.runId
Write-Output "RUN4 (dynamic, glm-4-7, divergence test): $runId"

# 5) poll until done
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
Write-Output "--- RUN4 records ---"
foreach ($rec in $r.records) {
    Write-Output ("  rec: sub={0} verdict={1} group={2}" -f $rec.subDimension, $rec.verdict, $rec.divergenceGroup)
}

# 6) verify the seeded (old) run records got the divergence mark + review queue rows
$rOld = Invoke-RestMethod -Uri "$base/rpg/eval/runs/$dynRun?subject=kagetora" -WebSession $s
Write-Output "--- DYN-MAIN (seeded) records after rerun ---"
foreach ($rec in $rOld.records) {
    Write-Output ("  rec: sub={0} verdict={1} group={2}" -f $rec.subDimension, $rec.verdict, $rec.divergenceGroup)
}
$q = Invoke-RestMethod -Uri "$base/rpg/eval/review-queue?limit=200" -WebSession $s
$dg = @($q | Where-Object { $_.divergenceGroup })
Write-Output ("review-queue total: {0}  divergence-marked: {1}" -f @($q).Count, $dg.Count)
foreach ($d in ($dg | Select-Object -First 12)) {
    Write-Output ("  DIVERGE: subject={0} sub={1} verdict={2} group={3}" -f $d.subject, $d.subDimension, $d.verdict, $d.divergenceGroup)
}
