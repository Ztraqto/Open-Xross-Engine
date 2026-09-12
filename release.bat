@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call build-release.bat
if errorlevel 1 exit /b 1
call package-release.bat
if errorlevel 1 exit /b 1
echo OpenXrossEngine 1.4.1 release build complete.
exit /b 0
