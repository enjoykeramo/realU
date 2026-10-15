@echo off
rem ============================================================================
rem  realU build (Windows) - uses the Gradle installed on this PC, no download.
rem  Builds dist\realu\realu.exe with a bundled Java runtime (gradle task "exe").
rem
rem  Usage:   build.bat                   output to dist\realu
rem           build.bat test              run unit tests first
rem           build.bat D:\tools\realu    output to the given folder
rem  Needs:   Gradle 8.x (gradle.bat on PATH or GRADLE_HOME set)
rem           JDK 17+ (JAVA_HOME = JDK folder, provides jpackage)
rem ============================================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "TASKS=exe"
set "OUTARG="
:args
if "%~1"=="" goto argsdone
if /i "%~1"=="test" (set "TASKS=test exe") else (set "OUTARG=-PoutDir=%~f1")
shift
goto args
:argsdone

set "GRADLE="
for /f "delims=" %%i in ('where gradle.bat 2^>nul') do if not defined GRADLE set "GRADLE=%%i"
if not defined GRADLE if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" set "GRADLE=%GRADLE_HOME%\bin\gradle.bat"
if not defined GRADLE (
    echo [ERROR] gradle.bat not found. Add Gradle's bin folder to PATH or set GRADLE_HOME.
    exit /b 1
)

echo ==== realU build ====
echo Gradle : %GRADLE%
echo Tasks  : %TASKS% %OUTARG%
call "%GRADLE%" --no-daemon %TASKS% %OUTARG%
if errorlevel 1 (
    echo [ERROR] Build failed, see messages above.
    exit /b 1
)
endlocal
exit /b 0
