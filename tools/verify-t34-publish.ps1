[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/auth/login" -ContentType "application/json" -Body '{"email":"sg32test@test.com","password":"Test1234!"}' -SessionVariable s
$gid = "01a2fe56-fd75-4af1-a06f-8f5a2db9e504"
$wid = "209eb18b-fe48-4584-829c-c6ee869768f9"

# 撤回后重发布：应走 refreshPack 复活覆盖（overwrite=true）
$body = [System.Text.Encoding]::UTF8.GetBytes((@{graphId=$gid; name="夜雾纪行共享包v2"; blurb="UTF-8复核"} | ConvertTo-Json))
$pub = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rpg/shared-story-graphs" -WebSession $s -ContentType "application/json" -Body $body
Write-Host ("PUBLISH: overwrite=" + $pub.overwrite + " nodeCount=" + $pub.nodeCount + " (期望 overwrite=True 复活覆盖)")

# 详情：UTF-8 精确解码
$resp = Invoke-WebRequest -Uri "http://localhost:8080/rpg/shared-story-graphs/$($pub.packId)" -WebSession $s -UseBasicParsing
$resp.RawContentStream.Position = 0
$detail = (New-Object System.IO.StreamReader($resp.RawContentStream, [System.Text.Encoding]::UTF8)).ReadToEnd() | ConvertFrom-Json
$snap = $detail.snapshotJson | ConvertFrom-Json
Write-Host ("快照三件套: improviseMode=" + $snap.improviseMode + " stateSchema=" + $snap.stateSchema + " graphJson长=" + $snap.graphJson.Length)
Write-Host ("快照含act2节拍[清洲密议]: " + ($snap.graphJson -match "清洲密议"))

# 导入 + 副本与快照内层 graphJson 逐字一致
$imp = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/rpg/shared-story-graphs/$($pub.packId)/import" -WebSession $s -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes((@{worldId=$wid} | ConvertTo-Json)))
$gResp = Invoke-WebRequest -Uri "http://localhost:8080/rpg/workshop/story-graphs/$($imp.graphId)" -WebSession $s -UseBasicParsing
$gResp.RawContentStream.Position = 0
$g = (New-Object System.IO.StreamReader($gResp.RawContentStream, [System.Text.Encoding]::UTF8)).ReadToEnd() | ConvertFrom-Json
Write-Host ("IMPORT: 新图id=" + $imp.graphId + " 名称=" + $g.name)
Write-Host ("导入副本.graphJson === 快照.graphJson: " + ($g.graphJson -eq $snap.graphJson))
Write-Host ("导入副本 improviseMode=" + $g.improviseMode + " schema一致=" + ($g.stateSchema -eq $snap.stateSchema))

# 清场：撤回（204）
$code = (Invoke-WebRequest -Method Delete -Uri "http://localhost:8080/rpg/shared-story-graphs/$($pub.packId)" -WebSession $s -UseBasicParsing).StatusCode
Write-Host ("REVOKE: " + $code)
