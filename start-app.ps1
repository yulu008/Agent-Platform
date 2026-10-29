param([string]$Log = 'd:\Agent-Platform\startup.log')
Set-Location d:\Agent-Platform
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:MAVEN_OPTS = '-Dmaven.multiModuleProjectDirectory=d:\Agent-Platform'
.\mvnw.cmd spring-boot:run > $Log 2>&1
