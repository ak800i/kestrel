@echo off
rem A stand-in for adb.exe in the tests of scripts\lib\Kestrel.ps1. It runs fake-adb.ps1 in a process of its own, as
rem adb runs, so that its error lines reach PowerShell as the error output of a native program.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-adb.ps1" %*
exit /b %ERRORLEVEL%
