$ErrorActionPreference = 'Stop'
$javap = 'C:\Program Files\Java\jdk-21.0.10\bin\javap.exe'
$cp = 'd:\Agent-Platform\oai-verify2\x'
& $javap -c -p -classpath $cp 'org.springframework.ai.openai.setup.OpenAiSetup' 2>&1 | Out-File d:\Agent-Platform\opensetup-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient$Builder' 2>&1 | Out-File d:\Agent-Platform\saihttp-builder-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient' 2>&1 | Out-File d:\Agent-Platform\saihttp-dump2.txt -Encoding utf8
& $javap -c -p -classpath $cp 'org.springframework.ai.openai.OpenAiChatModel$Builder' 2>&1 | Out-File d:\Agent-Platform\oaichatmodel-builder-dump2.txt -Encoding utf8
Write-Output 'done'
