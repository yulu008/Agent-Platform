# Agent-Platform 启动脚本
# 规范：JAVA_HOME 指向完整 JDK 21 路径；MAVEN_OPTS 指定 multiModuleProjectDirectory 避免 mvnw.cmd 报错
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
Set-Location 'd:\Agent-Platform'
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "MAVEN_OPTS=$env:MAVEN_OPTS"
Write-Host 'Starting Agent-Platform (mvnw spring-boot:run)...'
& .\mvnw.cmd spring-boot:run
