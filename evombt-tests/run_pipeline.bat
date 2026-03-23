@echo off
color 0A
echo ========================================================
echo      EvoMBT Automated Pipeline (Compile + Gen + Exec)
echo ========================================================
echo.
echo [ATENTIE] Asigura-te ca API-ul Python este deja pornit!
echo.
pause

echo.
echo ========================================================
echo [FAZA 0] COMPILAREA CODULUI JAVA (Maven)
echo ========================================================
call mvn compile
if %errorlevel% neq 0 (
    echo.
    echo [EROARE] Compilarea Java a esuat! Verifica erorile din cod.
    pause
    exit /b %errorlevel%
)

:: Setam calea comuna pentru librarii si clasele compilate
set CLASSPATH="lib\EvoMBT.jar;target\classes"

echo.
echo ========================================================
echo [FAZA 1] GENERAREA TESTELOR ABSTRACTE (EvoSuite MOSA)
echo ========================================================
java -cp %CLASSPATH% eu.fbk.iv4xr.mbt.Main -sbt -Dsut_efsm="org.evombt.OrderEFSM"
if %errorlevel% neq 0 (
    echo.
    echo [EROARE] Faza de generare a esuat!
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================================
echo [FAZA 2] CONCRETIZAREA SI EXECUTIA PE SUT
echo ========================================================
timeout /t 2 >nul
java -cp %CLASSPATH% org.evombt.OrderRunner

echo.
echo ========================================================
echo [SUCCES] Pipeline-ul complet a fost executat!
echo ========================================================
pause