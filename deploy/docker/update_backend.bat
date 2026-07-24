@echo off
cd /d "%~dp0"

REM ============================================================
REM KBook Backend Hot Update - copy app.jar into container
REM Java is PID 1, container must be restarted after replace
REM ============================================================

set VERSION=unknown
if exist "..\VERSION" set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Backend Update (Hot)
echo   Target Version: %VERSION%
echo ============================================
echo.

REM ============================================================
REM 1. Prerequisites
REM ============================================================
echo [1/6] Checking prerequisites...

docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker not installed
    pause
    exit /b 1
)
echo   Docker: OK

REM 用 docker inspect 直接查状态，比 docker ps | findstr 更可靠（不受管道编码影响）
set CONTAINER_RUNNING=
for /f "delims=" %%i in ('docker inspect --format "{{.State.Running}}" kbook-app 2^>nul') do set CONTAINER_RUNNING=%%i
if not "%CONTAINER_RUNNING%"=="true" (
    echo ERROR: Container kbook-app is not running
    echo   Start it first: start.bat
    pause
    exit /b 1
)
echo   Container kbook-app: running

if not exist ".env" (
    echo ERROR: .env not found in deploy\docker\
    pause
    exit /b 1
)
echo   .env: OK

REM ============================================================
REM 2. Prepare app.jar (local first, then remote)
REM ============================================================
echo [2/6] Preparing backend artifact...

set PKG_DIR=..\package
set JAR_PATH=%PKG_DIR%\app.jar

if exist "%JAR_PATH%" (
    powershell -Command "if ((Get-Item '%JAR_PATH%').Length -lt 1048576) { exit 1 }"
    if errorlevel 1 (
        echo   Local app.jar too small, will re-download
        del "%JAR_PATH%"
        goto :download_jar
    )
    echo   Using local build: %JAR_PATH%
    goto :jar_ready
)

:download_jar
echo   Local app.jar not found, downloading from remote...
set /p BASE_URL=<..\PACKAGE_URL
if not exist "%PKG_DIR%" mkdir "%PKG_DIR%"

if exist "%PKG_DIR%\app.jar" (
    for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set TS_OLD=%%i
    ren "%PKG_DIR%\app.jar" "app.jar.%TS_OLD%"
)

where curl >nul 2>&1
if %errorlevel% equ 0 (
    curl -fSL --connect-timeout 30 --max-time 600 -o "%JAR_PATH%" "%BASE_URL%/app.jar"
) else (
    wget -q --timeout=600 -O "%JAR_PATH%" "%BASE_URL%/app.jar"
)
if %errorlevel% neq 0 (
    echo ERROR: app.jar download failed
    pause
    exit /b 1
)

powershell -Command "if ((Get-Item '%JAR_PATH%').Length -lt 1048576) { exit 1 }"
if %errorlevel% neq 0 (
    echo ERROR: app.jar is too small - not a valid build!
    pause
    exit /b 1
)

:jar_ready
echo   app.jar: OK

REM ============================================================
REM 3. Backup current jar from container
REM ============================================================
echo [3/6] Backing up current app.jar in container...
for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set TS=%%i
docker cp kbook-app:/app/app.jar "%PKG_DIR%\app.jar.in_container.%TS%" >nul 2>&1
if %errorlevel% neq 0 (
    echo   Backup skipped
) else (
    echo   Backup: %PKG_DIR%\app.jar.in_container.%TS%
)

REM ============================================================
REM 4. Copy new jar into container
REM ============================================================
echo [4/6] Copying new app.jar to container...
docker cp "%JAR_PATH%" kbook-app:/app/app.jar
if %errorlevel% neq 0 (
    echo ERROR: docker cp failed
    pause
    exit /b 1
)
echo   Copied

REM ============================================================
REM 5. Restart container (Java is PID 1, must restart to load new jar)
REM ============================================================
echo [5/6] Restarting container kbook-app...
docker restart kbook-app
if %errorlevel% neq 0 (
    echo ERROR: docker restart failed
    echo   Try manually: docker restart kbook-app
    pause
    exit /b 1
)
echo   Container restarted

REM ============================================================
REM 6. Wait for health (up to 60s)
REM    直接探测容器内 8181 端口 health 接口，不依赖 docker health 状态
REM    （旧镜像 healthcheck 配置可能有 IPv6/端口问题，导致容器永远 unhealthy）
REM ============================================================
echo [6/6] Waiting for backend to start (up to 60s)...
setlocal enabledelayedexpansion
set /a WAIT=0

:wait_loop
timeout /t 3 /nobreak >nul
set /a WAIT+=3

docker exec kbook-app wget -qO- http://127.0.0.1:8181/api/health >nul 2>&1
if not errorlevel 1 (
    echo   Backend healthy after !WAIT!s
    goto :healthy
)

if !WAIT! geq 60 (
    echo   WARNING: Backend not healthy after 60s
    echo   Check logs: docker logs -f kbook-app
    goto :done
)
echo   Waiting... ^(!WAIT!s^)
goto :wait_loop

:healthy
:done
endlocal

REM ============================================================
REM Done
REM ============================================================
echo.
echo ============================================
echo   Backend update complete!  Version: %VERSION%
echo ============================================
echo.
echo   Logs : docker logs -f kbook-app
echo   Stop : stop.bat
echo.
pause
