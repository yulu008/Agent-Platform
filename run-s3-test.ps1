$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Xmx2g -Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
Set-Location d:\Agent-Platform
& .\mvnw.cmd test '-Dtest=S3BatchGenerationServiceTest' '-DfailIfNoTests=true' 2>&1 | Select-Object -Last 30
