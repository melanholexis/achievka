@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out
javac --module-path lib --add-modules javafx.controls,javafx.web,javafx.media -encoding UTF-8 -d out src\achievka\*.java
if errorlevel 1 (
  echo Сборка не удалась.
  pause
  exit /b 1
)
xcopy /E /I /Y web out\web >nul
java --module-path lib --add-modules javafx.controls,javafx.web,javafx.media -cp out achievka.Main
echo.
pause
