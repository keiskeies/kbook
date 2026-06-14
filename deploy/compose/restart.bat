@echo off
chcp 65001 >nul
pushd "%~dp0"
echo Restarting KBook...
docker compose restart
echo OK
pause
