$ErrorActionPreference = 'Stop'
$log = 'd:\Agent-Platform\startup.log'
# 新实例启动（20:58）之后的分批/收口/对账相关行（GBK 日志用 Default 编码读）
$lines = Get-Content $log -Encoding Default | Where-Object { $_ -match '^2026-10-28T2(1:|0:5[89])' }
$hits = $lines | Select-String -Pattern '批|收口|对账|名册|候选|返工|version|generate|失败' | ForEach-Object { $_.Line }
$hits | Select-Object -Last 30 | ForEach-Object { Write-Output $_ }
