@echo off
chcp 65001 >nul
title KBook Docker Start
pushd "%~dp0"

echo ============================================
echo   KBook Docker Start
echo ============================================
echo.

REM 1. Docker check
echo [1/5] Checking Docker...
docker --version >nul 2>&1 || ( echo FAIL: Please install Docker ^& pause ^& exit /b 1 )
docker compose version >nul 2>&1 || ( echo FAIL: Please install docker compose ^& pause ^& exit /b 1 )
echo OK

REM 2. .env
echo [2/5] Checking .env...
if not exist ".env" (
    if exist "..\.env.example" copy ..\.env.example .env >nul
    echo.
    echo WARNING: .env created from .env.example, please edit it first!
    echo   Required: JWT_SECRET / MYSQL_PASSWORD / REDIS_PASSWORD / KBOOK_ADMIN_PASSWORD
    pause ^& exit /b 1
)
echo OK

REM 3. package (download from remote)
echo [3/5] Downloading package/...
if not exist "..\package\app.jar" (
    echo Downloading from https://book.keiskei.top/package/...
    call download.bat
)
echo OK

REM 4. Data dirs
echo [4/5] Creating data directories...
for %%d in (
    ..\data\books\epub ..\data\books\pdf ..\data\books\txt
    ..\data\covers ..\data\avatars ..\data\chat ..\data\tts_cache
    ..\data\mysql ..\data\redis ..\data\es ..\data\qdrant
) do if not exist "%%d" mkdir "%%d"
echo OK

REM 5. Start
echo [5/5] Starting services...
docker compose up -d --build
if errorlevel 1 ( echo FAIL: Startup failed ^& pause ^& exit /b 1 )

echo.
echo ============================================
echo   KBook is running!
echo ============================================
echo   Visit: http://localhost
echo   Admin: admin@kbook.com
echo.
pause
