@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0android\build.ps1" %*
exit /b %ERRORLEVEL%
