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

REM 2. Check package artifacts
echo [2/3] 检查构建产物...
if not exist "..\package\app.jar" (
    echo FAIL: 未找到 ..\package\app.jar
    echo   请先运行根目录的 package.bat 打包后端
    pause && exit /b 1
)
if not exist "..\package\dist" (
    echo FAIL: 未找到 ..\package\dist\
    echo   请先运行根目录的 package.bat 打包前端
    pause && exit /b 1
)
echo OK

REM 3. Build image
echo [3/3] 构建镜像 kbook-app:%VERSION% ...
docker build -t kbook-app:%VERSION% -f ..\Dockerfile ..
if errorlevel 1 ( echo FAIL: 镜像构建失败 && pause && exit /b 1 )

echo.
echo ============================================
echo   构建成功！kbook-app:%VERSION%
echo ============================================
echo   运行：start.bat
echo.
pause


