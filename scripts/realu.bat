@echo off
REM realU trainer launcher for Windows.
REM This file is intentionally ASCII-only: cmd.exe parses .bat files using the
REM console code page, so Chinese text inside a .bat is a common source of garbled output.
REM
REM Usage:  realu.bat train
REM         realu.bat check
REM         realu.bat backtest out\train-xxx\model-xxx.json [fromYYYYMMDD] [toYYYYMMDD]
REM         realu.bat predict  out\train-xxx\model-xxx.json [YYYYMMDD]
REM         realu.bat demo     demo-data

setlocal
cd /d "%~dp0"

REM Switch console to UTF-8 so Chinese log lines display correctly.
chcp 65001 >nul

REM Heap size: default 65% of physical RAM. Override with: set REALU_HEAP=-Xmx8g
if "%REALU_HEAP%"=="" set REALU_HEAP=-XX:MaxRAMPercentage=65
set JAVA_OPTS=%REALU_HEAP% -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found. Please install JDK/JRE 17 or newer and add it to PATH.
  echo         e.g. https://adoptium.net/
  pause
  exit /b 1
)

set CMD=%~1
if "%CMD%"=="" set CMD=train

if /i "%CMD%"=="demo" (
  java %JAVA_OPTS% -jar "%~dp0realu-trainer.jar" demo %2
  goto end
)

if not exist "%~dp0config.properties" (
  echo [ERROR] config.properties not found. Copy config.example.properties to config.properties and edit it.
  pause
  exit /b 1
)

java %JAVA_OPTS% -jar "%~dp0realu-trainer.jar" %CMD% "%~dp0config.properties" %2 %3 %4
set RC=%ERRORLEVEL%
if "%RC%"=="10" echo [NOTE] Model was exported but did NOT reach the target precision. See report.md.
if "%RC%"=="3" echo [NOTE] Out of memory. Try: set REALU_HEAP=-Xmx12g  or lower sample.maxRows in config.

:end
pause
endlocal
