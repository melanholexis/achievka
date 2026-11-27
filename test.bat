@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

call build.bat
if errorlevel 1 exit /b 1
if not exist out\tests mkdir out\tests
javac --release 17 --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -encoding UTF-8 -cp out\classes -d out\tests tests\IntegrationCheck.java tests\UiCheck.java tests\DemoCheck.java
if errorlevel 1 exit /b 1
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -cp out\achievka.jar achievka.Check
if errorlevel 1 exit /b 1
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -cp "out\achievka.jar;out\tests" achievka.IntegrationCheck
if errorlevel 1 exit /b 1
node tests\python-exercises.cjs
if errorlevel 1 exit /b 1
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -cp "out\achievka.jar;out\tests" achievka.UiCheck
if errorlevel 1 exit /b 1
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media,jdk.httpserver -cp "out\achievka.jar;out\tests" achievka.DemoCheck
exit /b %errorlevel%
