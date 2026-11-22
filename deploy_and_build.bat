@echo off
title WLRemote Builder (D:\)
echo ======================================================================
echo   Launching WLRemote (Windows RDP + Built-in Linux SSH2) Builder...
echo ======================================================================
powershell.exe -NoExit -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy_and_build.ps1"
pause
