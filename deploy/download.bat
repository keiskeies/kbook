@echo off
chcp 65001 >nul
title KBook Download
pushd "%~dp0"
setlocal enabledelayedexpansion

echo ============================================
echo   Download KBook Build Artifacts
echo ============================================
echo.

set /p BASE_URL=<PACKAGE_URL

REM Create deploy/package/
if not exist "package" mkdir "package"

REM Check for download tool
where curl >nul 2>&1
if %errorlevel% equ 0 (
    set "DL_CMD=curl -fSL -o"
) else (
    where wget >nul 2>&1 || (
        echo ERROR: Please install curl or wget
        pause & exit /b 1
    )
    set "DL_CMD=wget -q -O"
)

REM Download app.jar
echo [1/2] Downloading app.jar...
%DL_CMD% "package\app.jar" "%BASE_URL%/app.jar"
if %errorlevel% neq 0 ( echo FAILED: app.jar download & pause & exit /b 1 )
echo OK - app.jar

REM Download frontend.zip and extract
echo [2/2] Downloading and extracting frontend.zip...
%DL_CMD% "package\frontend.zip" "%BASE_URL%/frontend.zip"
if %errorlevel% neq 0 ( echo FAILED: frontend.zip download & pause & exit /b 1 )

REM Remove old dist and extract
if exist "package\dist" rmdir /s /q "package\dist"
powershell -Command "Expand-Archive -Path 'package\frontend.zip' -DestinationPath 'package\dist' -Force"
if exist "package\frontend.zip" del "package\frontend.zip"
echo OK - dist/

echo.
echo ============================================
echo   Download complete!
echo ============================================
dir /a-d "package\"
echo.
popd
endlocal
