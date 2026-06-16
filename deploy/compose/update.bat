@echo off
cd /d "%~dp0"

REM ============================================================
REM Read version from single source of truth
REM ============================================================
set VERSION=unknown
if exist "..\VERSION" set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Update - Docker Compose
echo   Target Version: %VERSION%
echo ============================================
echo.

REM ============================================================
REM 1. Check prerequisites
REM ============================================================
echo [1/6] Checking prerequisites...

docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker not installed
    pause
    exit /b 1
)
docker compose version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: docker compose not installed
    pause
    exit /b 1
)
echo   Docker: OK

git --version >nul 2>&1
if %errorlevel% neq 0 (
    echo   git: NOT FOUND - will skip git pull
    set GIT_OK=0
) else (
    echo   git: OK
    set GIT_OK=1
)

REM ============================================================
REM 2. git pull (soft fail if no git)
REM ============================================================
echo [2/6] Pulling latest code...
if "%GIT_OK%"=="1" (
    pushd "..\.."
    git pull
    if %errorlevel% neq 0 (
        echo.
        echo   WARNING: git pull failed - check network or resolve conflicts
        echo   Continuing with existing code...
        echo.
    )
    popd
) else (
    echo   Skipped (git not available)
)

REM ============================================================
REM 3. Download latest build artifacts
REM ============================================================
echo [3/6] Downloading latest build artifacts...
set /p BASE_URL=<..\PACKAGE_URL
set PKG_DIR=..\package

REM Backup old artifacts with timestamp
for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set TS=%%i
if exist "%PKG_DIR%\app.jar" (
    echo   Backing up old app.jar -^> app.jar.%TS%
    ren "%PKG_DIR%\app.jar" "app.jar.%TS%"
)
if exist "%PKG_DIR%\dist" (
    echo   Backing up old dist\ -^> dist.%TS%\
    ren "%PKG_DIR%\dist" "dist.%TS%"
)

if not exist "%PKG_DIR%" mkdir "%PKG_DIR%"

REM ---- Download app.jar ----
echo   Downloading app.jar...
where curl >nul 2>&1
if %errorlevel% equ 0 (
    curl -fSL --connect-timeout 30 --max-time 600 -o "%PKG_DIR%\app.jar" "%BASE_URL%/app.jar"
) else (
    wget -q --timeout=600 -O "%PKG_DIR%\app.jar" "%BASE_URL%/app.jar"
)
if %errorlevel% neq 0 (
    echo ERROR: app.jar download failed
    pause
    exit /b 1
)

REM Validate app.jar size (min 1MB)
powershell -Command "if ((Get-Item '%PKG_DIR%\app.jar').Length -lt 1048576) { exit 1 }"
if %errorlevel% neq 0 (
    echo ERROR: app.jar is too small - not a valid build!
    echo   Check: %BASE_URL%/app.jar
    pause
    exit /b 1
)
echo     app.jar: OK

REM ---- Download frontend.zip ----
echo   Downloading frontend.zip...
where curl >nul 2>&1
if %errorlevel% equ 0 (
    curl -fSL --connect-timeout 30 --max-time 600 -o "%PKG_DIR%\frontend.zip" "%BASE_URL%/frontend.zip"
) else (
    wget -q --timeout=600 -O "%PKG_DIR%\frontend.zip" "%BASE_URL%/frontend.zip"
)
if %errorlevel% neq 0 (
    echo ERROR: frontend.zip download failed
    pause
    exit /b 1
)

REM Validate frontend.zip size (min 100KB)
powershell -Command "if ((Get-Item '%PKG_DIR%\frontend.zip').Length -lt 102400) { exit 1 }"
if %errorlevel% neq 0 (
    echo ERROR: frontend.zip is too small - not a valid build!
    echo   Check: %BASE_URL%/frontend.zip
    pause
    exit /b 1
)
echo     frontend.zip: OK

REM Extract frontend.zip
if exist "%PKG_DIR%\dist" rmdir /s /q "%PKG_DIR%\dist"
powershell -Command "Expand-Archive -Path '%PKG_DIR%\frontend.zip' -DestinationPath '%PKG_DIR%\dist' -Force"
if exist "%PKG_DIR%\frontend.zip" del "%PKG_DIR%\frontend.zip"
echo     dist/: OK

REM ============================================================
REM 4. Backup current version tag (for rollback)
REM ============================================================
echo [4/6] Tagging current image as backup...
docker tag kbook-app:latest kbook-app:backup 2>nul
echo   Backup: kbook-app:backup

REM ============================================================
REM 5. Build app image ONLY (NOT mysql/redis/es/qdrant!)
REM ============================================================
echo [5/6] Building app image kbook-app:%VERSION% ...
docker compose build app
if %errorlevel% neq 0 (
    echo.
    echo ============================================
    echo   BUILD FAILED!
    echo   Rollback: docker compose up -d app
    echo ============================================
    pause
    exit /b 1
)
docker tag kbook-app:latest kbook-app:%VERSION%
echo   Image: kbook-app:%VERSION%

REM ============================================================
REM 6. Redeploy app container ONLY (--no-deps = don't touch infra)
REM ============================================================
echo [6/6] Redeploying app container (infra untouched)...
docker compose up -d --no-deps --force-recreate app
if %errorlevel% neq 0 (
    echo ERROR: Container start failed
    pause
    exit /b 1
)
echo   Container restarted

REM ============================================================
REM Cleanup
REM ============================================================
echo.
echo Cleaning up old images...
docker image prune -f >nul 2>&1

echo.
echo ============================================
echo   Update complete!  Version: %VERSION%
echo ============================================
echo.
echo   Check status : docker compose ps
echo   View logs   : docker compose logs -f app
echo   Rollback    : docker compose up -d app
echo                  (uses last working image)
echo.
pause
