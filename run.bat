@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

call build.bat
if errorlevel 1 (
  echo Сборка не удалась. Убедись, что JDK 17 для Windows x64 есть в PATH.
  pause
  exit /b 1
)
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -jar out\achievka.jar
set "APP_RESULT=%errorlevel%"
if not "%APP_RESULT%"=="0" pause
exit /b %APP_RESULT%
