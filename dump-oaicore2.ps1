$ErrorActionPreference = 'Stop'
$jar = 'C:\Users\62754\.m2\repository\com\openai\openai-java-core\4.39.1\openai-java-core-4.39.1.jar'
$tmp = 'd:\Agent-Platform\oaicore-verify2'
New-Item -ItemType Directory -Force $tmp | Out-Null
$jarExe = 'C:\Program Files\Java\jdk-21.0.10\bin\jar.exe'
Set-Location $tmp
& $jarExe xf $jar
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
$cp = $tmp
& $javap -c -p -classpath $cp 'com.openai.client.OpenAIClientImpl' 2>&1 | Out-File d:\Agent-Platform\oaiclientimpl-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'com.openai.core.ClientOptions' 2>&1 | Out-File d:\Agent-Platform\clientoptions-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'com.openai.core.RequestOptions' 2>&1 | Out-File d:\Agent-Platform\requestoptions-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'com.openai.core.Timeout' 2>&1 | Out-File d:\Agent-Platform\timeout-dump2.txt -Encoding utf8
Write-Output 'done'
