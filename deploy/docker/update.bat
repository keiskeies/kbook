@echo off
cd /d "%~dp0"

REM ============================================================
REM Read version from single source of truth
REM ============================================================
set VERSION=unknown
if exist "..\VERSION" set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Update - Standalone Docker
echo   Target Version: %VERSION%
echo ============================================
echo.

REM ============================================================
REM 1. Prerequisites
REM ============================================================
echo [1/7] Checking prerequisites...

REM Docker
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker not installed
    pause
    exit /b 1
)
echo   Docker: OK

REM git (optional)
git --version >nul 2>&1
if %errorlevel% neq 0 (
    echo   git: NOT FOUND - will skip git pull
    set GIT_OK=0
) else (
    echo   git: OK
    set GIT_OK=1
)

REM .env
if not exist ".env" (
    echo ERROR: .env not found in deploy\docker\
    echo   Copy from .env.example and configure
    pause
    exit /b 1
)
echo   .env: OK

REM ============================================================
REM 2. git pull (optional)
REM ============================================================
echo [2/7] Pulling latest code...
if "%GIT_OK%"=="1" (
    pushd "..\.."
    git pull
    if %errorlevel% neq 0 (
        echo   WARNING: git pull failed - continuing with existing code
    )
    popd
) else (
    echo   Skipped (git not available)
)

REM ============================================================
REM 3. Download artifacts from remote
REM ============================================================
echo [3/7] Downloading latest build artifacts...
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

REM Validate app.jar size (min 1MB = 1048576 bytes)
powershell -Command "if ((Get-Item '%PKG_DIR%\app.jar').Length -lt 1048576) { Write-Host 'TOO_SMALL' ; exit 1 }"
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

REM Validate frontend.zip size (min 100KB = 102400 bytes)
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
REM 4. Load .env configuration
REM ============================================================
echo [4/7] Loading configuration...
for /f "usebackq tokens=1,* delims==" %%a in (".env") do set "%%a=%%b" 2>nul

REM ============================================================
REM 5. Build new Docker image
REM ============================================================
echo [5/7] Building image kbook-app:%VERSION% ...
docker build -t kbook-app:%VERSION% -f ..\Dockerfile ..
if %errorlevel% neq 0 (
    echo.
    echo ============================================
    echo   BUILD FAILED!
    echo   Rollback: docker start kbook-app
    echo ============================================
    pause
    exit /b 1
)
echo   Image: kbook-app:%VERSION%

REM ============================================================
REM 6. Stop old container
REM ============================================================
echo [6/7] Stopping old container...
docker stop kbook-app 2>nul
docker rm kbook-app 2>nul
echo   Old container removed

REM ============================================================
REM 7. Start new container
REM ============================================================
echo [7/7] Starting new container kbook-app:%VERSION% ...

REM ---- Force host.docker.internal for Docker container ----
set MYSQL_HOST=host.docker.internal
set REDIS_HOST=host.docker.internal
set QDRANT_HOST=host.docker.internal

REM ---- Replace localhost with host.docker.internal in ES_URIS ----
setlocal enabledelayedexpansion
set ES_URIS=!ES_URIS:localhost=host.docker.internal!

REM ---- Defaults ----
if "%APP_PORT_HOST%"=="" set APP_PORT_HOST=80
if "%MYSQL_PORT%"=="" set MYSQL_PORT=3306
if "%MYSQL_DB%"=="" set MYSQL_DB=kbook-dev
if "%MYSQL_USER%"=="" set MYSQL_USER=root
if "%REDIS_PORT%"=="" set REDIS_PORT=6379
if "%REDIS_DB%"=="" set REDIS_DB=4
if "%QDRANT_PORT%"=="" set QDRANT_PORT=6334
if "%AI_VISION_TIMEOUT%"=="" set AI_VISION_TIMEOUT=600s
if "%TZ%"=="" set TZ=Asia/Shanghai
if "%SPRING_PROFILE%"=="" set SPRING_PROFILE=prod

REM ---- Resolve volume paths to absolute ----
for %%p in ("%BOOK_PATH_EPUB%") do set "BOOK_PATH_EPUB=%%~fp"
for %%p in ("%BOOK_PATH_PDF%") do set "BOOK_PATH_PDF=%%~fp"
for %%p in ("%BOOK_PATH_TXT%") do set "BOOK_PATH_TXT=%%~fp"
for %%p in ("%BOOK_COVER_PATH%") do set "BOOK_COVER_PATH=%%~fp"
for %%p in ("%AVATAR_DIR%") do set "AVATAR_DIR=%%~fp"
for %%p in ("%CHAT_DIR%") do set "CHAT_DIR=%%~fp"
for %%p in ("%TTS_CACHE_DIR%") do set "TTS_CACHE_DIR=%%~fp"
for %%p in ("%AI_CONFIG_HOST%") do set "AI_CONFIG_HOST=%%~fp"

docker run -d ^
    --name kbook-app ^
    --restart unless-stopped ^
    --add-host host.docker.internal:host-gateway ^
    -p !APP_PORT_HOST!:80 ^
    -e TZ=!TZ! ^
    -e SPRING_PROFILES_ACTIVE=!SPRING_PROFILE! ^
    -e KBOOK_CORS_ORIGINS=!KBOOK_CORS_ORIGINS! ^
    -e MYSQL_HOST=!MYSQL_HOST! ^
    -e MYSQL_PORT=!MYSQL_PORT! ^
    -e MYSQL_DB=!MYSQL_DB! ^
    -e MYSQL_USER=!MYSQL_USER! ^
    -e MYSQL_PASSWORD=!MYSQL_PASSWORD! ^
    -e REDIS_HOST=!REDIS_HOST! ^
    -e REDIS_PORT=!REDIS_PORT! ^
    -e REDIS_PASSWORD=!REDIS_PASSWORD! ^
    -e REDIS_DB=!REDIS_DB! ^
    -e ES_URIS=!ES_URIS! ^
    -e QDRANT_HOST=!QDRANT_HOST! ^
    -e QDRANT_PORT=!QDRANT_PORT! ^
    -e JWT_SECRET=!JWT_SECRET! ^
    -e KBOOK_ADMIN_EMAIL=!KBOOK_ADMIN_EMAIL! ^
    -e KBOOK_ADMIN_PASSWORD=!KBOOK_ADMIN_PASSWORD! ^
    -e MAIL_USERNAME=!MAIL_USERNAME! ^
    -e MAIL_PASSWORD=!MAIL_PASSWORD! ^
    -e KBOOK_AI_CONFIG_PATH=/app/config/ai-config.json ^
    -e AI_VISION_MODEL=!AI_VISION_MODEL! ^
    -e AI_VISION_TIMEOUT=!AI_VISION_TIMEOUT! ^
    -v "!BOOK_PATH_EPUB!:/data/books/epub:ro" ^
    -v "!BOOK_PATH_PDF!:/data/books/pdf:ro" ^
    -v "!BOOK_PATH_TXT!:/data/books/txt:ro" ^
    -v "!BOOK_COVER_PATH!:/data/covers" ^
    -v "!AVATAR_DIR!:/data/avatars" ^
    -v "!CHAT_DIR!:/data/chat" ^
    -v "!TTS_CACHE_DIR!:/data/tts_cache" ^
    -v "!AI_CONFIG_HOST!:/app/config/ai-config.json:ro" ^
    kbook-app:%VERSION%

if %errorlevel% neq 0 (
    echo ERROR: Container start failed
    echo Rollback: docker start kbook-app
    pause
    exit /b 1
)

endlocal

REM ============================================================
REM Done
REM ============================================================
echo.
echo Cleaning up old images...
docker image prune -f >nul 2>&1

echo.
echo ============================================
echo   Update complete!  Version: %VERSION%
echo ============================================
echo.
echo   Visit : http://localhost:%APP_PORT_HOST%
echo   Logs  : docker logs -f kbook-app
echo   Stop  : stop.bat
echo.
pause
