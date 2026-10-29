# planner-s1-chained-refinement tasks 7.4: verify s1_refine call type + tenant passthrough
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$base = "http://localhost:8080"
$utf8 = New-Object System.Text.UTF8Encoding($false)

$handler = New-Object System.Net.Http.HttpClientHandler
$handler.CookieContainer = New-Object System.Net.CookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)
function JsonBody($str) { New-Object System.Net.Http.StringContent($str, $utf8, "application/json") }

# login as seeded platform admin
$r = $client.PostAsync("$base/api/auth/login", (JsonBody '{"email":"admin@default.local","password":"123456"}')).Result
Write-Output ("LOGIN => " + [int]$r.StatusCode)

# per-tenant call type breakdown (window: today only, tenant = e2e session tenant)
$tenant = "t-4415745d-db3f-420b-9bca-84681efe5f19"
$r = $client.GetAsync("$base/api/admin/metering/usage/$tenant" + "?windowDays=1").Result
Write-Output ("USAGE DETAIL => " + [int]$r.StatusCode)
$body = $r.Content.ReadAsStringAsync().Result
Write-Output $body
