@echo off
chcp 65001 >nul
pushd "%~dp0"

REM Read version
set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Restart (Docker Compose)
echo   Version: %VERSION%
echo ============================================
echo.
echo Rebuilding and restarting app container ONLY...
echo (MySQL / Redis / ES / Qdrant untouched)
echo.

docker compose build app
if errorlevel 1 (
    echo ERROR: Build failed
    pause & exit /b 1
)

docker compose up -d --no-deps --force-recreate app
if errorlevel 1 (
    echo ERROR: Restart failed
    pause & exit /b 1
)

echo.
echo Restart complete! Version: %VERSION%
pause
