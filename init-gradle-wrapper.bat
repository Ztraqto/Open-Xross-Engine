@echo off
setlocal EnableExtensions
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-gradle-wrapper.ps1"
exit /b %errorlevel%
