@echo off
rem Сборка PortalLauncher.exe (нужны JDK 17+ и WiX Toolset 3 для установщика)
cd /d "%~dp0.."
call gradlew.bat test createDistributable packageExe
if errorlevel 1 exit /b 1
echo.
echo Портативная версия: build\compose\binaries\main\app\PortalLauncher\PortalLauncher.exe
echo Установщик:         build\compose\binaries\main\exe\
