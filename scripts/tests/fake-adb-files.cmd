@echo off
rem A stand-in for adb.exe in the tests of scripts\lib\Models.ps1. It runs fake-adb-files.ps1 in a process of its own,
rem as adb runs, so that its error lines reach PowerShell as the error output of a native program.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-adb-files.ps1" %*
exit /b %ERRORLEVEL%
