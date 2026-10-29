$ErrorActionPreference = 'Stop'
$reportDir = 'd:\Agent-Platform\target\surefire-reports'
if (-not (Test-Path $reportDir)) { Write-Output 'NO_REPORT_DIR'; exit 1 }
$files = Get-ChildItem $reportDir -Filter '*.txt' | Sort-Object LastWriteTime -Descending
$total = 0; $fail = 0; $err = 0; $stale = 0
$now = Get-Date
foreach ($f in $files) {
    $line = Select-String -Path $f.FullName -Pattern 'Tests run:' | Select-Object -First 1
    if ($line -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)') {
        $total += [int]$Matches[1]; $fail += [int]$Matches[2]; $err += [int]$Matches[3]
        if (($now - $f.LastWriteTime).TotalMinutes -gt 30) { $stale++ }
    }
}
Write-Output ("suites={0} total={1} failures={2} errors={3} staleFiles={4}" -f $files.Count, $total, $fail, $err, $stale)
# 找 S3 单套结果
$s3 = Select-String -Path "$reportDir\com.luyu.agent.rpg.planner.engine.S3BatchGenerationServiceTest.txt" -Pattern 'Tests run:'
if ($s3) { Write-Output ("S3: " + $s3.Line) }
# 失败套清单（近 30 分钟内修改的）
foreach ($f in $files) {
    if (($now - $f.LastWriteTime).TotalMinutes -le 30) {
        $line = Select-String -Path $f.FullName -Pattern 'Tests run:' | Select-Object -First 1
        if ($line -and $line.Line -notmatch 'Failures: 0, Errors: 0') {
            Write-Output ("FAILED_SUITE: " + $f.Name + " -> " + $line.Line)
        }
    }
}
