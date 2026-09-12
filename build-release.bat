@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call init-gradle-wrapper.bat
if errorlevel 1 exit /b 1
where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: Java 17 or newer is required.
    exit /b 1
)
call gradlew.bat --no-daemon stageRelease
if errorlevel 1 exit /b 1
echo Release artifacts are in build\release.
exit /b 0
