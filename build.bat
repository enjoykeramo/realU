@echo off
rem ============================================================================
rem  realU build script (Windows)
rem  Compiles the project and packages a portable trainer: realu.exe + bundled Java.
rem
rem  Usage:   build.bat                   output to  dist\realu
rem           build.bat D:\tools\realu    output to the given folder
rem           build.bat test              run unit tests first, then build
rem           build.bat test D:\tools\realu
rem
rem  Needs:   JDK 17 or newer (includes jpackage), JAVA_HOME set or java on PATH
rem           Gradle 8.x on PATH (else the bundled gradlew.bat is used)
rem
rem  Files you created in the output folder (config.properties, out, ...) are kept.
rem  Only the program parts (app, runtime, realu.exe, docs) are replaced.
rem
rem  This file is ASCII-only on purpose: cmd.exe parses .bat files with the
rem  console code page, and Chinese text inside a .bat is a common cause of errors.
rem ============================================================================
setlocal EnableExtensions
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

set "RUNTESTS="
set "OUT="
:args
if "%~1"=="" goto argsdone
if /i "%~1"=="test" (set "RUNTESTS=1") else (set "OUT=%~f1")
shift
goto args
:argsdone
cd /d "%ROOT%"
if not defined OUT set "OUT=%ROOT%\dist\realu"
if "%OUT:~-1%"=="\" set "OUT=%OUT:~0,-1%"

echo.
echo ==== realU build ====
echo Source : %ROOT%
echo Output : %OUT%
echo.

rem ---- 1. Find JDK tools -------------------------------------------------------
set "JPACKAGE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
if not defined JPACKAGE for /f "delims=" %%i in ('where jpackage.exe 2^>nul') do if not defined JPACKAGE set "JPACKAGE=%%i"
if not defined JPACKAGE (
    echo [ERROR] jpackage not found. Install JDK 17 or newer, then set JAVA_HOME
    echo         to the JDK folder or add its bin folder to PATH.
    goto fail
)
echo [1/4] JDK tools: %JPACKAGE%

rem ---- 2. Gradle build -----------------------------------------------------------
set "GRADLE="
for /f "delims=" %%i in ('where gradle.bat 2^>nul') do if not defined GRADLE set "GRADLE=%%i"
if not defined GRADLE set "GRADLE=%ROOT%\gradlew.bat"
echo [2/4] Gradle: %GRADLE%
set "TASKS=clean :trainer:installDist"
if defined RUNTESTS set "TASKS=clean test :trainer:installDist"
call "%GRADLE%" --no-daemon %TASKS%
if errorlevel 1 (
    echo [ERROR] Gradle build failed, see messages above.
    goto fail
)

set "LIB=%ROOT%\trainer\build\install\realu-trainer\lib"
set "MAINJAR="
for %%f in ("%LIB%\trainer-*.jar") do set "MAINJAR=%%~nxf"
if not defined MAINJAR (
    echo [ERROR] trainer-*.jar not found in %LIB%
    goto fail
)

rem ---- 3. Package with jpackage (fresh staging folder every time) ---------------
rem  jdk.charsets is required: TDX txt exports, tdxhy.cfg and block_gn.dat are GBK.
rem  stdout.encoding is NOT forced here: Java then follows the console code page
rem  (936/GBK or 65001/UTF-8), so Chinese log lines display correctly either way.
set "STAGE=%ROOT%\build\jpackage"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
echo [3/4] jpackage %MAINJAR% ...
"%JPACKAGE%" --type app-image --name realu --input "%LIB%" ^
    --main-jar "%MAINJAR%" --main-class com.realu.trainer.MainKt ^
    --java-options "-XX:MaxRAMPercentage=65" --java-options "-Dfile.encoding=UTF-8" ^
    --add-modules java.base,jdk.charsets --win-console --dest "%STAGE%"
if errorlevel 1 (
    echo [ERROR] jpackage failed, see messages above.
    goto fail
)

rem ---- 4. Copy into the output folder (keeps your own files there) -------------
echo [4/4] Copy to %OUT%
if not exist "%OUT%" mkdir "%OUT%"
robocopy "%STAGE%\realu\app" "%OUT%\app" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto copyfail
robocopy "%STAGE%\realu\runtime" "%OUT%\runtime" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto copyfail
robocopy "%ROOT%\docs" "%OUT%\docs" /MIR /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto copyfail
copy /y "%STAGE%\realu\*.*" "%OUT%\" >nul
if errorlevel 1 goto copyfail
copy /y "%ROOT%\README.md" "%OUT%\" >nul
copy /y "%ROOT%\scripts\config.example.properties" "%OUT%\" >nul
rem  First build only: create config.properties from the template. Never overwrite yours.
if not exist "%OUT%\config.properties" (
    copy /y "%ROOT%\scripts\config.example.properties" "%OUT%\config.properties" >nul
    echo       Created %OUT%\config.properties - edit tdx.vipdoc before training.
)

echo.
"%OUT%\realu.exe" version
if errorlevel 1 (
    echo [ERROR] The packaged realu.exe did not start.
    goto fail
)
echo.
echo ==== Build OK ====
echo Run it with:
echo     cd /d "%OUT%"
echo     realu.exe version
echo     realu.exe check
echo     realu.exe train
echo     realu.exe predict out\train-xxx\model-xxx.json
echo     realu.exe demo demo-data
echo The "version" line shows the build time; it must match this build.
echo Commands without a config file use config.properties in the current folder.
endlocal
exit /b 0

:copyfail
echo [ERROR] Copy to %OUT% failed. Close any running realu.exe and try again.
:fail
endlocal
exit /b 1
