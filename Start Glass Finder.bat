@echo off
title Glass Finder
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js is not installed. Get it from https://nodejs.org and run this file again.
  pause
  exit /b 1
)
echo Starting Glass Finder...
start "" /min cmd /c "timeout /t 2 /nobreak >nul & (start "" msedge --app=http://localhost:3737 || start "" http://localhost:3737)"
node server.mjs
pause
