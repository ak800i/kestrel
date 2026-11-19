@echo off
rem A stand-in for git.exe in the tests of scripts\sync-catalog.ps1. It runs fake-git.ps1 in a process of its own, as git
rem runs, so that its error lines reach PowerShell as the error output of a native program.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-git.ps1" %*
exit /b %ERRORLEVEL%
