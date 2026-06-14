@echo off
chcp 65001 >nul
pushd "%~dp0"
echo Stopping KBook...
docker compose down
echo OK
pause
