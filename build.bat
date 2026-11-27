@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

if not exist out\classes mkdir out\classes
javac --release 17 --module-path lib --add-modules javafx.controls,javafx.web,javafx.media -cp vendor\jna.jar -encoding UTF-8 -d out\classes src\achievka\*.java
if errorlevel 1 exit /b 1

copy /y vendor\jna.jar out\jna.jar >nul
if errorlevel 1 exit /b 1
>out\manifest.mf echo Class-Path: jna.jar
jar --create --file out\achievka.jar --manifest out\manifest.mf --main-class achievka.Main -C out\classes . web course-site
exit /b %errorlevel%
