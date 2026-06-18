@echo off
chcp 65001 >nul
title KBook Docker Build
pushd "%~dp0"

REM Read version from single source of truth
set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Docker 镜像构建
echo   Version: %VERSION%
echo ============================================
echo.

REM 1. Docker check
echo [1/3] 检查 Docker...
docker --version >nul 2>&1 || ( echo FAIL: 请先安装 Docker && pause && exit /b 1 )
echo OK

REM 2. Check package (auto-download if missing)
echo [2/3] 检查构建产物...
if not exist "..\package\app.jar" goto :need_download
if not exist "..\package\dist" goto :need_download
goto :package_ok

:need_download
echo 本地未找到构建产物，从远程下载...
call ..\download.bat
if errorlevel 1 ( pause && exit /b 1 )

:package_ok
echo OK

REM 3. Build image
echo [3/3] 构建镜像 kbook-app:%VERSION% ...
for %%I in ("%~dp0..") do docker build -t "kbook-app:%VERSION%" -f "%%~fI\Dockerfile" "%%~fI"
if errorlevel 1 ( echo FAIL: 镜像构建失败 && pause && exit /b 1 )

echo.
echo ============================================
echo   构建成功！kbook-app:%VERSION%
echo ============================================
echo   运行：start.bat
echo.
pause


