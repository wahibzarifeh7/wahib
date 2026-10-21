@echo off
title Glass Finder (phone access)
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js is not installed. Get it from https://nodejs.org and run this file again.
  pause
  exit /b 1
)
echo Starting Glass Finder so phones on the same Wi-Fi can open it.
echo Anyone on this Wi-Fi network can then view and change the list.
echo If Windows asks about the firewall, choose "Private networks" and Allow.
echo.
set HOST=0.0.0.0
start "" /min cmd /c "timeout /t 2 /nobreak >nul & (start "" msedge --app=http://localhost:3737 || start "" http://localhost:3737)"
node server.mjs
pause
