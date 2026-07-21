@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM ----------------------------------------------------------------------------

@echo off

@REM Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set WRAPPER_JAR="%~dp0\.mvn\wrapper\maven-wrapper.jar"
set WRAPPER_PROPS="%~dp0\.mvn\wrapper\maven-wrapper.properties"

@REM Provide a "normalized" command line environment
set MAVEN_CMD_LINE_ARGS=%*
if "%OS%"=="Windows_NT" set MAVEN_CMD_LINE_ARGS=%MAVEN_CMD_LINE_ARGS:"=%

set MAVEN_PROJECTBASEDIR=%~dp0
if "%OS%"=="Windows_NT" goto winNT1
goto okBaseDir

:winNT1
set MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%
if not "%MAVEN_PROJECTBASEDIR%"=="" goto okBaseDir
goto error

:okBaseDir

@REM Decide on the Java command to use
if not "%JAVA_HOME%"=="" goto OkJdkHome
for %%i in (java.exe) do set JAVA_CMD=%%~$PATH:i
goto checkJdk

:OkJdkHome
set JAVA_CMD="%JAVA_HOME%\bin\java.exe"

:checkJdk
if exist %JAVA_CMD% goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
goto error

:init

@REM Find the project base dir
set MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%

@REM Download maven-wrapper.jar if not present
if exist %WRAPPER_JAR% if not %WRAPPER_PROPS%=="" goto findWrapperJar
if not exist %WRAPPER_JAR% goto downloadWrapper

:findWrapperJar
echo Using MAVEN_OPTS: %MAVEN_OPTS%
echo Using wrapper jar: %WRAPPER_JAR%
goto execute

:downloadWrapper
if not %WRAPPER_PROPS%=="" (
    @REM Download the maven-wrapper.jar from the URL specified in the properties file
    findstr "^wrapperUrl" %WRAPPER_PROPS% >nul 2>&1
    if %ERRORLEVEL%==0 (
        for /f "tokens=2 delims==" %%a in ('findstr "^wrapperUrl" %WRAPPER_PROPS%') do set WRAPPER_URL=%%a
        echo Downloading Maven Wrapper from %WRAPPER_URL%
        powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
        if exist %WRAPPER_JAR% goto findWrapperJar
        goto error
    )
)

@REM Use the maven-wrapper.properties distributionUrl directly
goto findMaven

:findMaven
if not %WRAPPER_PROPS%=="" (
    for /f "tokens=2 delims==" %%a in ('findstr "^distributionUrl" %WRAPPER_PROPS%') do set MAVEN_DIST_URL=%%a
    goto downloadMaven
)
goto error

:downloadMaven
@REM Download Apache Maven distribution
set MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9
if exist "%MAVEN_HOME%\bin\mvn.cmd" goto executeMaven

echo Downloading Apache Maven from %MAVEN_DIST_URL%
set MAVEN_ZIP=%TEMP%\apache-maven-3.9.9-bin.zip
powershell -Command "Invoke-WebRequest -Uri '%MAVEN_DIST_URL%' -OutFile '%MAVEN_ZIP%'"
if not exist "%MAVEN_ZIP%" goto error

powershell -Command "Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%USERPROFILE%\.m2\wrapper\dists' -Force"
set MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9
del "%MAVEN_ZIP%"

:executeMaven
set PATH=%MAVEN_HOME%\bin;%PATH%

:execute
%JAVA_CMD% %MAVEN_OPTS% -classpath %WRAPPER_JAR% org.apache.maven.wrapper.MavenWrapperMain %MAVEN_CMD_LINE_ARGS%
if ERRORLEVEL 1 goto error
goto end

:error
set ERROR_CODE=1

:end
@if "%OS%"=="Windows_NT" endlocal
exit /b %ERROR_CODE%
