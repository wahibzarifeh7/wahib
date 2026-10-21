@echo off
rem Builds app\build\outputs\apk\debug\app-debug.apk with the toolchain in D:\APK\toolchain
set JAVA_HOME=D:\APK\toolchain\jdk
set GRADLE_USER_HOME=D:\APK\toolchain\gradle-home
cd /d "%~dp0"
call D:\APK\toolchain\gradle\bin\gradle.bat assembleDebug
