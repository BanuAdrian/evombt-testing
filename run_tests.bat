@echo off
echo ======================================
echo  EvoMBT Test Runner (E-Commerce Order API)
echo ======================================
echo Make sure the backend is running first (start_backend.bat)
echo.

:: Try to automatically discover a JDK if JAVA_HOME is not set
if "%JAVA_HOME%"=="" (
    for /d %%i in ("%USERPROFILE%\.jdks\*") do (
        if exist "%%i\bin\javac.exe" set "JAVA_HOME=%%i"
    )
    if "%JAVA_HOME%"=="" (
        for /d %%i in ("C:\Program Files\Java\jdk*") do (
            if exist "%%i\bin\javac.exe" set "JAVA_HOME=%%i"
        )
    )
)

set JAVA_CMD=java
if not "%JAVA_HOME%"=="" (
    echo [INFO] Found JDK at: %JAVA_HOME%
    set JAVA_CMD="%JAVA_HOME%\bin\java.exe"
) else (
    echo [WARNING] No JDK found automatically. Maven might fail if 'java' in PATH is only a JRE.
)

:: Try to automatically discover Maven if MAVEN_HOME is not set
if "%MAVEN_HOME%"=="" (
    for /d %%i in ("C:\apache-maven-*") do (
        if exist "%%i\bin\mvn.cmd" set "MAVEN_HOME=%%i"
    )
    if "%MAVEN_HOME%"=="" (
        for /d %%i in ("C:\Program Files\apache-maven-*") do (
            if exist "%%i\bin\mvn.cmd" set "MAVEN_HOME=%%i"
        )
    )
)

set MVN_CMD=mvn.cmd
if not "%MAVEN_HOME%"=="" (
    echo [INFO] Found Maven at: %MAVEN_HOME%
    set MVN_CMD="%MAVEN_HOME%\bin\mvn.cmd"
) else (
    echo [WARNING] No Maven found automatically. Assuming 'mvn' is in PATH.
)

cd evombt-tests
echo Building Java project...
call %MVN_CMD% -q clean compile dependency:build-classpath -Dmdep.outputFile=cp.txt
if errorlevel 1 (
    echo [ERROR] Build failed. Make sure Maven is installed and in your PATH, or set MAVEN_HOME.
    pause
    exit /b 1
)

set /p CP=<cp.txt
echo.
echo Running EvoMBT Order EFSM tests...
echo.
%JAVA_CMD% -cp "target\classes;lib\EvoMBT.jar;%CP%" org.evombt.OrderRunner

echo.
echo ======================================
echo  Done! Check the dashboard for results.
echo ======================================
pause
