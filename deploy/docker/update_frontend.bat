@echo off
cd /d "%~dp0"

REM ============================================================
REM KBook Frontend Hot Update - copy dist/ into running container
REM nginx serves static files directly, no container restart needed
REM ============================================================

set VERSION=unknown
if exist "..\VERSION" set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Frontend Update (Hot)
echo   Target Version: %VERSION%
echo ============================================
echo.

REM ============================================================
REM 1. Prerequisites
REM ============================================================
echo [1/5] Checking prerequisites...

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
REM 2. Prepare frontend artifacts (remote first, then local)
REM ============================================================
echo [2/5] Preparing frontend artifacts...

set PKG_DIR=..\package
set DIST_DIR=%PKG_DIR%\dist

REM ---- Check if remote build exists ----
if not exist "..\PACKAGE_URL" (
    echo ERROR: PACKAGE_URL not found in deploy\docker\
    pause
    exit /b 1
)
set /p BASE_URL=<..\PACKAGE_URL

echo   Checking for frontend.zip from %BASE_URL%...
where curl >nul 2>&1
if %errorlevel% equ 0 (
    curl -fSL --connect-timeout 30 --max-time 600 -o "%PKG_DIR%\frontend.zip" "%BASE_URL%/frontend.zip" >nul 2>&1
) else (
    wget -q --timeout=600 -O "%PKG_DIR%\frontend.zip" "%BASE_URL%/frontend.zip" >nul 2>&1
)
if %errorlevel% neq 0 (
    echo ERROR: frontend download failed from remote
    pause
    exit /b 1
)

REM ---- Extract and validate ----
if exist "%DIST_DIR%\index.html" (
    echo   Using local build: %DIST_DIR%
) else if exist "%PKG_DIR%\frontend.zip" (
    echo   Remote build found, extracting...
    if exist "%PKG_DIR%\dist" rmdir /s /q "%PKG_DIR%\dist"
    powershell -Command "Expand-Archive -Path '%PKG_DIR%\frontend.zip' -DestinationPath '%PKG_DIR%\dist' -Force" >nul 2>&1
    del "%PKG_DIR%\frontend.zip" >nul 2>&1
    if not exist "%DIST_DIR%\index.html" (
        echo ERROR: frontend extraction failed - index.html not found
        pause
        exit /b 1
    )
)

REM ---- Validate size ----
powershell -Command "if ((Get-Item '%PKG_DIR%\dist\index.html').Length -lt 65536) { Write-Host 'TOO_SMALL' ; exit 1 }"
if %errorlevel% neq 0 (
    echo ERROR: frontend is too small - not a valid build!
    pause
    exit /b 1
)
echo   dist/ ready

set TS=none
for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set TS=%%i
docker cp kbook-app:/usr/share/nginx/html "%PKG_DIR%\html.backup.%TS%" >nul 2>&1
if %errorlevel% neq 0 (
    echo   Backup skipped (container may be fresh)
) else (
    echo   Backup: %PKG_DIR%\html.backup.%TS%
)

REM ============================================================
REM 4. Clear and copy new frontend
REM ============================================================
echo [4/5] Updating frontend in container...

docker exec kbook-app sh -c "rm -rf /usr/share/nginx/html/* /usr/share/nginx/html/.[!.]*"
if %errorlevel% neq 0 (
    echo ERROR: Failed to clear nginx html dir
    pause
    exit /b 1
)

docker cp "%DIST_DIR%\." kbook-app:/usr/share/nginx/html/
if %errorlevel% neq 0 (
    echo ERROR: docker cp failed
    echo   Rollback: docker cp "%PKG_DIR%\html.backup.%TS%\." kbook-app:/usr/share/nginx/html/
    pause
    exit /b 1
)
echo   Frontend copied

REM ============================================================
REM 5. Reload nginx (static files; reload is a safety net)
REM ============================================================
echo [5/5] Reloading nginx...
docker exec kbook-app nginx -s reload >nul 2>&1
if %errorlevel% neq 0 (
    echo   nginx reload skipped
) else (
    echo   nginx reloaded
)

REM ============================================================
REM Done
REM ============================================================
echo.
echo ============================================
echo   Frontend update complete!  Version: %VERSION%
echo ============================================
echo.
echo   If UI not refreshed, hard-refresh browser (Ctrl+F5)
echo.
pause
