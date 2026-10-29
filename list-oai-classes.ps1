$ErrorActionPreference = 'Stop'
Get-ChildItem -Recurse 'd:\Agent-Platform\oai-verify2\x' -Filter '*.class' |
    Where-Object { $_.Name -match 'OpenAiSetup|SpringAiOpenAiHttpClient|HttpClientBuilderCustomizer|OpenAiChatModel' } |
    ForEach-Object { $_.FullName } | Out-File d:\Agent-Platform\oai-classes2.txt -Encoding utf8
Write-Output 'done'
