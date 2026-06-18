@echo off
chcp 65001 >nul
title KBook Docker Start
pushd "%~dp0"
setlocal enabledelayedexpansion

set ENV_FILE=.env

REM Read version
set /p VERSION=<..\VERSION

echo ============================================
echo   KBook 单容器启动
echo   Version: !VERSION!
echo ============================================
echo.

REM =====================================================
REM 1. Docker check
REM =====================================================
echo [1/6] 检查 Docker...
docker --version >nul 2>&1 || ( echo FAIL: 请安装 Docker && pause && exit /b 1 )
echo OK

REM =====================================================
REM 2. .env
REM =====================================================
echo [2/6] 检查 %ENV_FILE%...
if not exist "%ENV_FILE%" (
    if exist ".env.example" (
        copy .env.example .env >nul
        echo.
        echo WARNING: .env 已从 .env.example 创建，请编辑后重新运行！
        echo   必改：JWT_SECRET / MYSQL_PASSWORD / REDIS_PASSWORD / KBOOK_ADMIN_PASSWORD
        echo.
        pause && exit /b 1
    ) else (
        echo FAIL: 未找到 .env.example && pause && exit /b 1
    )
)
echo OK

REM =====================================================
REM 3. Check package (auto-download if missing)
REM =====================================================
echo [3/6] 检查构建产物...
if not exist "..\package\app.jar" (
    echo 本地未找到 app.jar，从远程下载...
    call ..\download.bat
    if errorlevel 1 ( pause && exit /b 1 )
)
if not exist "..\package\dist" (
    echo 本地未找到 dist/，从远程下载...
    call ..\download.bat
    if errorlevel 1 ( pause && exit /b 1 )
)
echo OK

REM =====================================================
REM 4. Data dirs
REM =====================================================
echo [4/6] 创建数据目录...
for %%d in (
    ..\data\books\epub ..\data\books\pdf ..\data\books\txt
    ..\data\covers ..\data\avatars ..\data\chat ..\data\tts_cache
) do if not exist "%%d" mkdir "%%d"
echo OK

REM =====================================================
REM 5. Build
REM =====================================================
echo [5/6] 构建镜像 kbook-app:!VERSION! ...
docker build -t kbook-app:!VERSION! -f ..\Dockerfile ..
if errorlevel 1 ( echo FAIL: 镜像构建失败 && pause && exit /b 1 )
echo OK

REM =====================================================
REM 6. Run
REM =====================================================
echo [6/6] 启动容器...

REM 清理旧容器
docker rm -f kbook-app 2>nul

REM ---- 读取 .env ----
for /f "usebackq tokens=1,* delims==" %%a in ("!ENV_FILE!") do set "%%a=%%b" 2>nul

REM ---- Docker 容器必须用 host.docker.internal，替换 .env 中可能出现的 localhost ----
set MYSQL_HOST=host.docker.internal
set REDIS_HOST=host.docker.internal
set QDRANT_HOST=host.docker.internal
REM ES_URIS 是完整 URL，只替换 host 部分，保留端口
set ES_URIS=!ES_URIS:localhost=host.docker.internal!

REM ---- 默认值（.env 未定义时） ----
if "!APP_PORT_HOST!"=="" set APP_PORT_HOST=80
if "!MYSQL_PORT!"=="" set MYSQL_PORT=3306
if "!MYSQL_DB!"=="" set MYSQL_DB=kbook-dev
if "!MYSQL_USER!"=="" set MYSQL_USER=root
if "!REDIS_PORT!"=="" set REDIS_PORT=6379
if "!REDIS_DB!"=="" set REDIS_DB=4
if "!QDRANT_PORT!"=="" set QDRANT_PORT=6334
if "!AI_VISION_TIMEOUT!"=="" set AI_VISION_TIMEOUT=600s
if "!TZ!"=="" set TZ=Asia/Shanghai
if "!SPRING_PROFILE!"=="" set SPRING_PROFILE=prod

REM ---- Volume 路径转绝对路径 ----
for %%p in ("!BOOK_PATH_EPUB!") do set "BOOK_PATH_EPUB=%%~fp"
for %%p in ("!BOOK_PATH_PDF!") do set "BOOK_PATH_PDF=%%~fp"
for %%p in ("!BOOK_PATH_TXT!") do set "BOOK_PATH_TXT=%%~fp"
for %%p in ("!BOOK_COVER_PATH!") do set "BOOK_COVER_PATH=%%~fp"
for %%p in ("!AVATAR_DIR!") do set "AVATAR_DIR=%%~fp"
for %%p in ("!CHAT_DIR!") do set "CHAT_DIR=%%~fp"
for %%p in ("!TTS_CACHE_DIR!") do set "TTS_CACHE_DIR=%%~fp"
for %%p in ("!AI_CONFIG_HOST!") do set "AI_CONFIG_HOST=%%~fp"

REM ---- 启动 ----
docker run -d ^
    --name kbook-app ^
    --restart unless-stopped ^
    --add-host host.docker.internal:host-gateway ^
    -p !APP_PORT_HOST!:80 ^
    -e TZ=!TZ! ^
    -e SPRING_PROFILES_ACTIVE=!SPRING_PROFILE! ^
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
    kbook-app:!VERSION!

if errorlevel 1 ( echo FAIL: 容器启动失败 && pause && exit /b 1 )

echo.
echo ============================================
echo   KBook 已启动！
echo ============================================
echo   访问：http://localhost:!APP_PORT_HOST!
echo.
echo   停止：stop.bat  ^|  日志：docker logs -f kbook-app
echo.
pause

