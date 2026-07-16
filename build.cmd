@echo off
rem Double-click launcher for build.ps1. Prefers PowerShell 7, falls back to 5.1.
setlocal

set "SCRIPT=%~dp0build.ps1"

where pwsh >nul 2>&1
if %ERRORLEVEL%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)

set "EXITCODE=%ERRORLEVEL%"

rem Keep the window open when double-clicked, but not when run from a shell.
echo %CMDCMDLINE% | find /i "/c" >nul && pause

exit /b %EXITCODE%
