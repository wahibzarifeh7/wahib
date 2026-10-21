// Builds "Glass Finder Setup.exe": a self-contained Windows installer.
//
// It bundles a copy of the Node.js runtime, so the target PC does not need Node.js installed.
// Built entirely from tools already on Windows (PowerShell's Compress-Archive/Expand-Archive,
// robocopy, reg.exe, and iexpress.exe) — no third-party installer software.
//
// Run with: node installer/build.mjs
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'glassfinder-build-'));
const stage = path.join(work, 'stage');
const APP_NAME = 'Glass Finder';
const OUTPUT = path.join(root, 'Glass Finder Setup.exe');

function findNode() {
  const fromEnv = process.env.GLASS_NODE_EXE;
  if (fromEnv && fs.existsSync(fromEnv)) return fromEnv;
  const where = execFileSync('where', ['node'], { encoding: 'utf8' }).trim().split(/\r?\n/)[0];
  if (!where || !fs.existsSync(where)) throw new Error('Could not find node.exe on PATH');
  return where;
}

function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dst, entry.name);
    if (entry.isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

console.log('Staging files in', stage);
fs.mkdirSync(stage, { recursive: true });

// --- runtime ---
const nodeExe = findNode();
console.log('Bundling Node.js runtime from', nodeExe);
fs.mkdirSync(path.join(stage, 'runtime'), { recursive: true });
fs.copyFileSync(nodeExe, path.join(stage, 'runtime', 'node.exe'));

// --- app code ---
for (const f of ['server.mjs', 'README.md', 'README.ar.md']) fs.copyFileSync(path.join(root, f), path.join(stage, f));
copyDir(path.join(root, 'lib'), path.join(stage, 'lib'));
copyDir(path.join(root, 'public'), path.join(stage, 'public'));

// --- starter data (only used to seed a first install; an existing install's data is never overwritten) ---
fs.mkdirSync(path.join(stage, 'data', 'images'), { recursive: true });
fs.mkdirSync(path.join(stage, 'data', 'backups'), { recursive: true });
const dbSrc = path.join(root, 'data', 'db.json');
fs.writeFileSync(path.join(stage, 'data', 'db.json'), fs.existsSync(dbSrc) ? fs.readFileSync(dbSrc) : '{"version":1,"models":[],"boxes":{},"misses":{}}');
const imgSrc = path.join(root, 'data', 'images');
if (fs.existsSync(imgSrc)) for (const f of fs.readdirSync(imgSrc)) fs.copyFileSync(path.join(imgSrc, f), path.join(stage, 'data', 'images', f));

// --- icon (pre-built from the shop's logo artwork via installer/assets/make-icon.py) ---
fs.copyFileSync(path.join(root, 'installer', 'assets', 'icon.ico'), path.join(stage, 'icon.ico'));

// --- launcher used after install (runs the bundled node.exe; system Node.js is never involved) ---
fs.writeFileSync(path.join(stage, 'Glass Finder.cmd'), `@echo off
title ${APP_NAME}
cd /d "%~dp0"
start "" /min cmd /c "timeout /t 2 /nobreak >nul & (start "" msedge --app=http://localhost:3737 || start "" http://localhost:3737)"
"%~dp0runtime\\node.exe" "%~dp0server.mjs"
pause
`.replace(/\n/g, '\r\n'));

// --- phone-access launcher: same app, reachable from any phone on the same Wi-Fi (no install needed
// on the phone). Uses the bundled runtime exactly like the normal launcher; only HOST differs. ---
fs.writeFileSync(path.join(stage, `${APP_NAME} (phone access).cmd`), `@echo off
title ${APP_NAME} (phone access)
cd /d "%~dp0"
echo Starting ${APP_NAME} so phones on the same Wi-Fi can open it.
echo Anyone on this Wi-Fi network can then view and change the list.
echo If Windows asks about the firewall, choose "Private networks" and Allow.
echo.
set HOST=0.0.0.0
start "" /min cmd /c "timeout /t 2 /nobreak >nul & (start "" msedge --app=http://localhost:3737 || start "" http://localhost:3737)"
"%~dp0runtime\\node.exe" "%~dp0server.mjs"
pause
`.replace(/\n/g, '\r\n'));

// --- uninstaller: removes the app and shortcuts, always keeps the data folder ---
fs.writeFileSync(path.join(stage, 'uninstall.cmd'), `@echo off
setlocal
set "DEST=%~dp0"
set "DEST=%DEST:~0,-1%"

taskkill /FI "WINDOWTITLE eq ${APP_NAME}*" /T /F >nul 2>nul

del /Q "%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\${APP_NAME}.lnk" 2>nul
del /Q "%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\${APP_NAME} (phone access).lnk" 2>nul
del /Q "%USERPROFILE%\\Desktop\\${APP_NAME}.lnk" 2>nul
reg delete "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /f >nul 2>nul

rmdir /S /Q "%DEST%\\runtime" 2>nul
rmdir /S /Q "%DEST%\\lib" 2>nul
rmdir /S /Q "%DEST%\\public" 2>nul
del /Q "%DEST%\\server.mjs" "%DEST%\\README.md" "%DEST%\\README.ar.md" "%DEST%\\icon.ico" "%DEST%\\Glass Finder.cmd" "%DEST%\\${APP_NAME} (phone access).cmd" 2>nul

echo.
echo ${APP_NAME} has been removed.
echo Your phone list and photos were kept at:
echo   %DEST%\\data
echo Delete that folder yourself if you no longer need it.
echo.
pause
(goto) 2>nul & del "%~f0"
`.replace(/\n/g, '\r\n'));

// --- installer: merges the new payload onto any existing install, seeding data only when absent ---
fs.writeFileSync(path.join(stage, 'install.cmd'), `@echo off
setlocal
set "HERE=%~dp0"
set "DEST=%LOCALAPPDATA%\\Programs\\${APP_NAME}"
set "NEW=%HERE%payload_unpacked"

echo Installing ${APP_NAME}...
if not exist "%DEST%" mkdir "%DEST%" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%HERE%payload.zip' -DestinationPath '%NEW%' -Force" || goto :fail

robocopy "%NEW%\\runtime" "%DEST%\\runtime" /E /MIR /NFL /NDL /NJH /NJS >nul
robocopy "%NEW%\\lib" "%DEST%\\lib" /E /MIR /NFL /NDL /NJH /NJS >nul
robocopy "%NEW%\\public" "%DEST%\\public" /E /MIR /NFL /NDL /NJH /NJS >nul
copy /Y "%NEW%\\server.mjs" "%DEST%\\server.mjs" >nul
copy /Y "%NEW%\\README.md" "%DEST%\\README.md" >nul
copy /Y "%NEW%\\README.ar.md" "%DEST%\\README.ar.md" >nul
copy /Y "%NEW%\\icon.ico" "%DEST%\\icon.ico" >nul
copy /Y "%NEW%\\Glass Finder.cmd" "%DEST%\\Glass Finder.cmd" >nul
copy /Y "%NEW%\\${APP_NAME} (phone access).cmd" "%DEST%\\${APP_NAME} (phone access).cmd" >nul
copy /Y "%NEW%\\uninstall.cmd" "%DEST%\\uninstall.cmd" >nul

if not exist "%DEST%\\data" (
  echo First install: adding the current phone list.
  robocopy "%NEW%\\data" "%DEST%\\data" /E /NFL /NDL /NJH /NJS >nul
) else (
  echo Existing phone list found - leaving it exactly as it is.
)
rmdir /S /Q "%NEW%" 2>nul

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=(New-Object -COM WScript.Shell).CreateShortcut('%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\${APP_NAME}.lnk');" ^
  "$s.TargetPath='%DEST%\\Glass Finder.cmd'; $s.WorkingDirectory='%DEST%'; $s.IconLocation='%DEST%\\icon.ico'; $s.Save()"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=(New-Object -COM WScript.Shell).CreateShortcut('%USERPROFILE%\\Desktop\\${APP_NAME}.lnk');" ^
  "$s.TargetPath='%DEST%\\Glass Finder.cmd'; $s.WorkingDirectory='%DEST%'; $s.IconLocation='%DEST%\\icon.ico'; $s.Save()"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=(New-Object -COM WScript.Shell).CreateShortcut('%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\${APP_NAME} (phone access).lnk');" ^
  "$s.TargetPath='%DEST%\\${APP_NAME} (phone access).cmd'; $s.WorkingDirectory='%DEST%'; $s.IconLocation='%DEST%\\icon.ico'; $s.Save()"

reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v DisplayName /t REG_SZ /d "${APP_NAME}" /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v UninstallString /t REG_SZ /d "\\"%DEST%\\uninstall.cmd\\"" /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v DisplayIcon /t REG_SZ /d "%DEST%\\icon.ico" /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v Publisher /t REG_SZ /d "Glass Finder" /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v InstallLocation /t REG_SZ /d "%DEST%" /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v NoModify /t REG_DWORD /d 1 /f >nul
reg add "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GlassFinder" /v NoRepair /t REG_DWORD /d 1 /f >nul

echo.
echo Done. Starting ${APP_NAME}...
start "" "%DEST%\\Glass Finder.cmd"
exit /b 0

:fail
echo.
echo Something went wrong while installing. Nothing was launched.
pause
exit /b 1
`.replace(/\n/g, '\r\n'));

// --- zip the payload (keeps folder structure; IExpress itself extracts flat) ---
console.log('Zipping payload (this bundles ~90MB, may take a minute)...');
const payloadDir = path.join(work, 'payload');
fs.mkdirSync(payloadDir);
for (const name of fs.readdirSync(stage)) fs.renameSync(path.join(stage, name), path.join(payloadDir, name));
const zipPath = path.join(work, 'payload.zip');
execFileSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
  `Compress-Archive -Path '${payloadDir}\\*' -DestinationPath '${zipPath}' -CompressionLevel Optimal`],
  { stdio: 'inherit' });

// --- what IExpress actually packages: the zip + the installer script that unpacks it ---
const pkgDir = path.join(work, 'package');
fs.mkdirSync(pkgDir);
fs.copyFileSync(zipPath, path.join(pkgDir, 'payload.zip'));
fs.copyFileSync(path.join(payloadDir, 'install.cmd'), path.join(pkgDir, 'install.cmd'));

const sed = `[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=${OUTPUT}
FriendlyName=${APP_NAME} Setup
AppLaunched=install.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="payload.zip"
FILE1="install.cmd"
[SourceFiles]
SourceFiles0=${pkgDir}
[SourceFiles0]
%FILE0%=
%FILE1%=
`.replace(/\n/g, '\r\n');
const sedPath = path.join(work, 'glassfinder.sed');
fs.writeFileSync(sedPath, sed);

console.log('Building installer with IExpress...');
if (fs.existsSync(OUTPUT)) fs.unlinkSync(OUTPUT);
execFileSync('iexpress', ['/N', '/Q', sedPath], { stdio: 'inherit' });

if (!fs.existsSync(OUTPUT)) throw new Error('IExpress did not produce ' + OUTPUT);
const mb = (fs.statSync(OUTPUT).size / 1024 / 1024).toFixed(1);
console.log(`\nBuilt ${OUTPUT} (${mb} MB)`);
fs.rmSync(work, { recursive: true, force: true });
