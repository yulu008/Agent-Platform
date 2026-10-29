# content-eval tasks 9.1 端到端验证：起服务（judge 角色注入）→ 静态层批跑
#（preset-adventure 4 命定档案）→ 轮询 run done → 断言 records 与汇总结构 → 查复核队列。
# 用法：powershell tools\verify-eval-pipeline.ps1 [-JudgeModel glm-5-1] [-SkipStart]
#   -JudgeModel：指定裁判模型（9.3 换 judge 重跑时改挂 glm-5-2 再跑一遍即可对比两次 run）
#   -SkipStart：服务已在跑（且已配 judge）时跳过起服务直接验证
param(
    [string]$JudgeModel = 'glm-5-2',
    [switch]$SkipStart,
    [string]$Log = 'd:\Agent-Platform\startup-eval-verify.log',
    [int]$TimeoutSeconds = 1500
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

function Get-JsonUtf8($url, $session) {
    # UTF-8 精确解码（对齐 verify-t34-publish.ps1 惯例，防 Invoke-RestMethod 中文乱码）
    $resp = Invoke-WebRequest -Uri $url -WebSession $session -UseBasicParsing
    $resp.RawContentStream.Position = 0
    return ((New-Object System.IO.StreamReader($resp.RawContentStream, [System.Text.Encoding]::UTF8)).ReadToEnd() | ConvertFrom-Json)
}

# ===== 0) 起服务（local profile + judge 角色注入：roles=[chat, judge]） =====
$healthUp = $false
try {
    $r = Invoke-WebRequest -Uri "$base/actuator/health" -UseBasicParsing -TimeoutSec 3
    $healthUp = ($r.StatusCode -eq 200)
} catch { }

if ($healthUp) {
    if ($SkipStart) {
        Write-Host "SKIP-START: 服务已在跑（按 -SkipStart 直接验证；若 judge 未配会在触发批跑时 503）"
    } else {
        Write-Host "RESTART: 8080 已被占用，kill 旧进程后带 judge 角色重启"
        $owner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -First 1
        if ($owner) { Stop-Process -Id $owner -Force; Start-Sleep -Seconds 2 }
        $healthUp = $false
    }
}
if (-not $healthUp) {
    Write-Host "START: spring-boot:run（local profile + $JudgeModel 挂 judge 角色）→ $Log"
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
    $env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
    # 参数经 SPRING_APPLICATION_JSON 环境变量注入（优先级高于 application.yml，子进程自动
    # 继承）——避开 Start-Process 对引号的剥离（命令行 roles[1]=judge 直传会被 maven 拒解析）。
    # 注：glm-5-1 在当前 key 下 429（1113 资源上限），默认裁判挂 glm-5-2（保留原有角色集再追加 judge）
    $judgeRoles = switch ($JudgeModel) {
        'glm-5-2' { '"chat","compaction","title","subagent","workshop","planner","judge"' }
        default   { '"chat","judge"' }
    }
    $env:SPRING_APPLICATION_JSON = '{"spring":{"profiles":{"active":"local"}},"agent":{"models":{"' + $JudgeModel + '":{"roles":[' + $judgeRoles + ']}}}}'
    Write-Host "BOOT-PROPS: $env:SPRING_APPLICATION_JSON"
    Start-Process -FilePath 'cmd.exe' -WindowStyle Hidden -WorkingDirectory 'd:\Agent-Platform' `
        -ArgumentList "/c d:\Agent-Platform\mvnw.cmd spring-boot:run > `"$Log`" 2>&1"
    $env:SPRING_APPLICATION_JSON = $null
    $tries = 0
    while ($tries -lt 50 -and -not $healthUp) {
        $tries++
        Start-Sleep -Seconds 4
        try {
            $r = Invoke-WebRequest -Uri "$base/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { $healthUp = $true; Write-Host "HEALTH: UP after $($tries * 4)s" }
        } catch { }
    }
    if (-not $healthUp) { throw "服务 200s 内未起（查 $Log）" }
}

# ===== 1) admin 登录（批跑/复核端点走 admin 域） =====
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' `
    -Body '{"email":"admin@default.local","password":"123456"}' -SessionVariable s
Write-Host "LOGIN: admin@default.local ok"

# ===== 2) 物化 preset-adventure（幂等，取 default 租户 worldId；4 命定档案真源） =====
# presetKey 白名单是 adventure（“preset-adventure” 是 worldId 前缀而非键）
$matBody = [System.Text.Encoding]::UTF8.GetBytes('{"presetKey":"adventure"}')
$mat = Invoke-RestMethod -Method Post -Uri "$base/rpg/preset/materialize" -WebSession $s `
    -ContentType 'application/json' -Body $matBody
$worldId = $mat.worldId
Write-Host "MATERIALIZE: preset-adventure worldId=$worldId"
if (-not $worldId) { throw '物化未返回 worldId' }

# ===== 3) 触发静态层批跑（judge 未配 → 503 明确报错不静默） =====
$runBody = [System.Text.Encoding]::UTF8.GetBytes("{`"layer`":`"static`",`"worldId`":`"$worldId`"}")
try {
    $run = Invoke-RestMethod -Method Post -Uri "$base/rpg/eval/run" -WebSession $s `
        -ContentType 'application/json' -Body $runBody
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    throw "POST /rpg/eval/run 失败（$code）：judge 模型未配置或 eval 未启用——用 -JudgeModel $JudgeModel 重起服务（-SkipStart 去掉）"
}
$runId = $run.runId
Write-Host "EVAL-RUN: runId=$runId layer=static judge=$JudgeModel"

# ===== 4) 轮询 run done（judge 串行逐案判分：glm-5.2 reasoning 每案 60-90s，
# 18 案全批实测约 19 分钟，默认超时 1500s） =====
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$status = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 6
    $detail = Get-JsonUtf8 "$base/rpg/eval/runs/$runId" $s
    $status = $detail.status
    Write-Host "POLL: status=$status records=$($detail.records.Count)"
    if ($status -eq 'done' -or $status -eq 'failed') { break }
}
if ($status -ne 'done') { throw "run 未 done（status=$status，超时 ${TimeoutSeconds}s 或批跑失败——查 logs/application.log）" }

# ===== 5) 断言：records 覆盖 4 命定档案 + 汇总结构 + 成本计量 =====
$records = $detail.records
$subjects = @($records | ForEach-Object { $_.subject } | Sort-Object -Unique)
$summary = $detail.summary
$pass = @($records | Where-Object { $_.verdict -eq 'pass' }).Count
$border = @($records | Where-Object { $_.verdict -eq 'borderline' }).Count
$fail = @($records | Where-Object { $_.verdict -eq 'fail' }).Count
$invalid = @($records | Where-Object { $_.invalidReason }).Count
Write-Host "RECORDS: total=$($records.Count) subjects=$($subjects.Count)($($subjects -join ',')) pass=$pass borderline=$border fail=$fail invalid=$invalid"
Write-Host "SUMMARY: total=$($summary.total) invalidRatePct=$($summary.invalidRatePct) judgeModel=$($detail.judgeModel) rubric=$($detail.rubricVersion)"
Write-Host "COST: tokens=$($detail.costTokens) amountMicro=$($detail.costAmountMicro)"
if ($records.Count -lt 4) { throw "records 少于 4（preset-adventure 4 命定档案须全覆盖）" }
if ($subjects.Count -lt 4) { throw "被评主体少于 4（期望 4 命定档案 figureKey 全参评）" }
if (-not $detail.judgeModel) { throw 'judgeModel 为空（判分非真实 LLM 路径）' }
if ($summary.total -lt 4) { throw "summary.total 少于 4: $($summary.total)" }

# ===== 6) 复核队列（borderline + 分歧标记行入队；内容随判分浮动，断言 200 可查） =====
$queue = Get-JsonUtf8 "$base/rpg/eval/review-queue?limit=50" $s
$queueItems = @($queue)
Write-Host "REVIEW-QUEUE: length=$($queueItems.Count)（borderline / 分歧 / 回归集行；随判分浮动）"
foreach ($q in ($queueItems | Select-Object -First 5)) {
    Write-Host ("  - subject={0} sub={1} verdict={2} final={3} divergence={4}" -f `
        $q.subject, $q.subDimension, $q.verdict, $q.reviewFinalVerdict, $q.divergenceGroup)
}
Write-Host 'VERIFY: 静态层批跑端到端 PASS'
