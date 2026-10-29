# 5.1 Zero-increment smoke: no-story-graph world keeps GM free-sandbox behavior (spec: no story graph world behavior is zero-increment)
$ErrorActionPreference = 'Stop'
$ts = Get-Date -Format HHmmss
$base = 'http://localhost:8080'

# 1. Register a new user (get an isolated WebSession)
$regBody = '{"email":"story51-' + $ts + '@test.dev","password":"Passw0rd123"}'
$reg = Invoke-RestMethod -Method Post -Uri "$base/api/auth/register" -ContentType 'application/json' -Body $regBody -SessionVariable sess
Write-Output "1.register: email=story51-$ts@test.dev"

# 2. Create a world without a story graph (pure manual world)
$world = Invoke-RestMethod -Method Post -Uri "$base/rpg/workshop/world" -ContentType 'application/json' -WebSession $sess `
    -Body '{"name":"smoke51-world","settingDesc":"zero-increment smoke world without story graph","era":"sengoku","atmosphere":"tense"}'
Write-Output "2.world: id=$($world.id) name=$($world.name)"

# 3. Create a player character
$charBody = '{"worldId":"' + $world.id + '","name":"smoke51-swordsman","type":"player","identity":"ronin"}'
$char = Invoke-RestMethod -Method Post -Uri "$base/rpg/workshop/character" -ContentType 'application/json' -WebSession $sess -Body $charBody
Write-Output "3.char: id=$($char.id) type=$($char.type)"

# 4. Start the game WITHOUT storyGraphId (default path does not touch the engine)
$startBody = '{"worldId":"' + $world.id + '","playerCharId":"' + $char.id + '"}'
$gs = Invoke-RestMethod -Method Post -Uri "$base/rpg/workshop/start" -ContentType 'application/json' -WebSession $sess -Body $startBody
Write-Output "4.start(no graph): gameStateId=$($gs.id)"

# 5. story-options empty-view assertion (mode empty / edges empty / improviseAllowed true)
$opts = Invoke-RestMethod -Uri "$base/rpg/game/story-options?gameStateId=$($gs.id)" -WebSession $sess
$optsJson = $opts | ConvertTo-Json -Compress
Write-Output "5.story-options: $optsJson"

$okMode = ($opts.mode -eq '')
$okEdges = (@($opts.edges).Count -eq 0)
$okImprov = ($opts.improviseAllowed -eq $true)
Write-Output ("ASSERT mode_empty={0} edges_empty={1} improvise_allowed={2}" -f $okMode, $okEdges, $okImprov)
if ($okMode -and $okEdges -and $okImprov) { Write-Output 'SMOKE-51: PASS' } else { Write-Output 'SMOKE-51: FAIL'; exit 1 }
