@echo off
chcp 65001 >nul
title KBook Download
pushd "%~dp0"
setlocal enabledelayedexpansion

echo ============================================
echo   Download KBook Build Artifacts
echo ============================================
echo.

set /p BASE_URL=<..\PACKAGE_URL
for %%I in ("%~dp0..") do set "DEPLOY_PACKAGE_DIR=%%~fI\package"

REM Create directory
if not exist "%DEPLOY_PACKAGE_DIR%" mkdir "%DEPLOY_PACKAGE_DIR%"

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
%DL_CMD% "%DEPLOY_PACKAGE_DIR%\app.jar" "%BASE_URL%/app.jar"
if %errorlevel% neq 0 ( echo FAILED: app.jar download & pause & exit /b 1 )
echo OK - app.jar

REM Download frontend.zip and extract
echo [2/2] Downloading and extracting frontend.zip...
%DL_CMD% "%DEPLOY_PACKAGE_DIR%\frontend.zip" "%BASE_URL%/frontend.zip"
if %errorlevel% neq 0 ( echo FAILED: frontend.zip download & pause & exit /b 1 )

REM Remove old dist and extract
if exist "%DEPLOY_PACKAGE_DIR%\dist" rmdir /s /q "%DEPLOY_PACKAGE_DIR%\dist"
powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('%DEPLOY_PACKAGE_DIR:\=\\%\frontend.zip', '%DEPLOY_PACKAGE_DIR:\=\\%\dist')"
if exist "%DEPLOY_PACKAGE_DIR%\frontend.zip" del "%DEPLOY_PACKAGE_DIR%\frontend.zip"
echo OK - dist/

echo.
echo ============================================
echo   Download complete!
echo ============================================
dir /a-d "%DEPLOY_PACKAGE_DIR%\"
echo.
endlocal
