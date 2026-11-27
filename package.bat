@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

if exist dist\windows-1.4.1\Achievka (
  echo Папка dist\windows-1.4.1\Achievka уже существует. Выбери другое место для старого пакета и запусти сборку снова.
  exit /b 1
)
call build.bat
if errorlevel 1 exit /b 1
if not exist dist-input mkdir dist-input
copy /y out\achievka.jar dist-input\achievka.jar >nul
if errorlevel 1 exit /b 1
copy /y out\jna.jar dist-input\jna.jar >nul
if errorlevel 1 exit /b 1

jpackage --type app-image --name Achievka --app-version 1.4.1 --input dist-input --dest dist\windows-1.4.1 ^
  --main-jar achievka.jar --main-class achievka.Main --icon achievka.ico --module-path lib ^
  --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver,java.net.http,java.logging,jdk.crypto.ec
exit /b %errorlevel%
