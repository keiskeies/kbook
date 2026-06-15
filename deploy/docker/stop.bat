@echo off
chcp 65001 >nul
pushd "%~dp0"
echo Stopping KBook container...
docker stop kbook-app 2>nul
docker rm kbook-app 2>nul
echo OK
pause


