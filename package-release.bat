@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call init-gradle-wrapper.bat
if errorlevel 1 exit /b 1
call gradlew.bat --no-daemon sourceReleaseZip githubSourceZip
exit /b %errorlevel%
