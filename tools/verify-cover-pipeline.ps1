$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$base = "http://localhost:8080"
$coversDir = "d:\Agent-Platform\data\covers"
$pngPath = "d:\Agent-Platform\src\main\resources\rpg\preset-adventure-cover.png"
$utf8 = New-Object System.Text.UTF8Encoding($false)

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)
function S($resp) { [int]$resp.StatusCode }
function JsonBody($str) { New-Object System.Net.Http.StringContent($str, $utf8, "application/json") }

# 0) 登录
$r = $client.PostAsync("$base/api/auth/login", (JsonBody '{"email":"admin@default.local","password":"123456"}')).Result
Write-Output ("LOGIN => " + (S $r))

# 0b) 清理上一步手动创建的残留世界（若存在）
if ($args.Count -ge 1 -and $args[0]) {
  $r = $client.DeleteAsync("$base/rpg/workshop/world/" + $args[0]).Result
  Write-Output ("CLEANUP leftover world " + $args[0] + " => " + (S $r))
}

# 1) 建一次性世界（ASCII 名，规避脚本编码问题）
$r = $client.PostAsync("$base/rpg/workshop/world", (JsonBody '{"name":"WCG-Verify-World","era":"test","settingDesc":"temp world for cover pipeline verification","rules":"[]","atmosphere":"test"}')).Result
$wj = $r.Content.ReadAsStringAsync().Result
$worldId = ([regex]'"id":"([^"]+)"').Match($wj).Groups[1].Value
$coverNull = $wj -match '"cover":null'
Write-Output ("CREATE WORLD => " + (S $r) + " id=" + $worldId + " coverIsNull=" + $coverNull)

# 2) 上传前 cover => 404
$r = $client.GetAsync("$base/rpg/worlds/$worldId/cover").Result
Write-Output ("COVER before upload (expect 404) => " + (S $r))

# 3) 上传封面
$fileBytes = [IO.File]::ReadAllBytes($pngPath)
$mp = New-Object System.Net.Http.MultipartFormDataContent
$fc = New-Object System.Net.Http.ByteArrayContent(,$fileBytes)
$fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/png")
$mp.Add($fc, "file", "cover.png")
$r = $client.PostAsync("$base/rpg/workshop/worlds/$worldId/cover", $mp).Result
$uj = $r.Content.ReadAsStringAsync().Result
$coverFile = ([regex]'"cover":"([^"]+)"').Match($uj).Groups[1].Value
Write-Output ("UPLOAD cover (expect 200) => " + (S $r) + " file=" + $coverFile)

# 4) 上传后伺服 => 200 image/png
$r = $client.GetAsync("$base/rpg/worlds/$worldId/cover").Result
Write-Output ("COVER after upload (expect 200 png) => " + (S $r) + " " + $r.Content.Headers.ContentType)

# 5) 文件落盘 data/covers
$coverPath = Join-Path $coversDir $coverFile
Write-Output ("COVER file on disk (expect True) => " + (Test-Path $coverPath))

# 6) 替换上传 => 新文件名，旧文件清理
$mp2 = New-Object System.Net.Http.MultipartFormDataContent
$fc2 = New-Object System.Net.Http.ByteArrayContent(,$fileBytes)
$fc2.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/png")
$mp2.Add($fc2, "file", "cover2.png")
$r = $client.PostAsync("$base/rpg/workshop/worlds/$worldId/cover", $mp2).Result
$rj = $r.Content.ReadAsStringAsync().Result
$newCoverFile = ([regex]'"cover":"([^"]+)"').Match($rj).Groups[1].Value
Write-Output ("REPLACE cover => " + (S $r) + " new=" + $newCoverFile + " oldRemoved(expect False)=" + (Test-Path $coverPath))

# 7) 删除世界 => 204 + 连带清理封面文件
$r = $client.DeleteAsync("$base/rpg/workshop/world/$worldId").Result
$newPath = Join-Path $coversDir $newCoverFile
Write-Output ("DELETE world (expect 204) => " + (S $r) + " coverFileRemovedAfterDelete(expect False)=" + (Test-Path $newPath))

# 8) 非法 MIME => 400（建 ASCII 世界2 承载）
$r = $client.PostAsync("$base/rpg/workshop/world", (JsonBody '{"name":"WCG-Verify-World2","settingDesc":"tmp","rules":"[]"}')).Result
$wj2 = $r.Content.ReadAsStringAsync().Result
$worldId2 = ([regex]'"id":"([^"]+)"').Match($wj2).Groups[1].Value
$mp3 = New-Object System.Net.Http.MultipartFormDataContent
$gif = New-Object System.Net.Http.ByteArrayContent(,[byte[]](0x47,0x49,0x46,0x38,0x39,0x61,0x01,0x00))
$gif.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/gif")
$mp3.Add($gif, "file", "bad.gif")
$r = $client.PostAsync("$base/rpg/workshop/worlds/$worldId2/cover", $mp3).Result
Write-Output ("UPLOAD gif (expect 400) => " + (S $r))
$r = $client.DeleteAsync("$base/rpg/workshop/world/$worldId2").Result
Write-Output ("DELETE world2 (expect 204) => " + (S $r))

$client.Dispose()
