@echo off
cd /d "%~dp0"

REM ============================================================
REM Read version
REM ============================================================
set VERSION=unknown
if exist "..\VERSION" set /p VERSION=<..\VERSION

echo ============================================
echo   KBook Update - Kubernetes
echo   Target Version: %VERSION%
echo ============================================
echo.
echo   WARNING: This updates ONLY backend + frontend.
echo   MySQL / Redis / ES / Qdrant are NOT touched.
echo.

REM ============================================================
REM 1. Prerequisites
REM ============================================================
echo [1/6] Checking prerequisites...

kubectl version --client >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: kubectl not installed
    pause
    exit /b 1
)
echo   kubectl: OK

docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker not installed
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

kubectl get namespace kbook >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: kbook namespace not found - run deploy.bat first
    pause
    exit /b 1
)
echo   namespace kbook: OK

REM ============================================================
REM 2. git pull
REM ============================================================
echo [2/6] Pulling latest code...
if "%GIT_OK%"=="1" (
    pushd "..\.."
    git pull
    if %errorlevel% neq 0 (
        echo   WARNING: git pull failed - continuing with existing code
    )
    popd
) else (
    echo   Skipped
)

REM ============================================================
REM 3. Download artifacts
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
REM 4. Build and push backend image
REM ============================================================
echo [4/6] Building backend image kbook-backend:%VERSION% ...
docker build -t kbook-backend:%VERSION% -f ..\Dockerfile ..
if %errorlevel% neq 0 (
    echo ERROR: Backend image build failed
    pause
    exit /b 1
)
echo   Image: kbook-backend:%VERSION%

echo.
echo   If using remote registry, push the image now:
echo     docker tag kbook-backend:%VERSION% your-registry/kbook-backend:%VERSION%
echo     docker push your-registry/kbook-backend:%VERSION%
echo     Then update 04-app.yaml image field.
echo.
echo   For local cluster (minikube/kind/docker-desktop), continuing...

REM ============================================================
REM 5. Update backend deployment (ROLLING UPDATE)
REM ============================================================
echo [5/6] Updating backend deployment...
kubectl set image deployment/backend backend=kbook-backend:%VERSION% -n kbook
if %errorlevel% neq 0 (
    echo ERROR: Backend image update failed
    pause
    exit /b 1
)

echo   Waiting for backend rollout...
kubectl rollout status deployment/backend -n kbook --timeout=120s
if %errorlevel% neq 0 (
    echo.
    echo   WARNING: Backend rollout timed out - check status:
    echo     kubectl get pods -n kbook
    echo     kubectl describe deployment backend -n kbook
)
echo   Backend: updated

REM ============================================================
REM 6. Update frontend static files (via kubectl cp)
REM ============================================================
echo [6/6] Updating frontend static files...

for /f "tokens=1" %%p in ('kubectl get pods -n kbook -l app=frontend -o jsonpath="{.items[0].metadata.name}" 2^>nul') do set FRONTEND_POD=%%p

if "%FRONTEND_POD%"=="" (
    echo   WARNING: No frontend pod found - skipping frontend update
    echo     Deploy with: kubectl apply -f 04-app.yaml
) else (
    echo   Pod: %FRONTEND_POD%
    kubectl exec -n kbook %FRONTEND_POD% -- sh -c "rm -rf /usr/share/nginx/html/*" 2>nul
    kubectl cp "%PKG_DIR%\dist\." "kbook/%FRONTEND_POD%:/usr/share/nginx/html/" 2>nul
    if %errorlevel% neq 0 (
        echo   WARNING: Frontend file copy had issues
        echo     Try: kubectl rollout restart deployment/frontend -n kbook
    ) else (
        echo   Frontend: updated
        kubectl exec -n kbook %FRONTEND_POD% -- nginx -s reload 2>nul
    )
)

REM ============================================================
REM Done
REM ============================================================
echo.
echo ============================================
echo   Update complete!  Version: %VERSION%
echo ============================================
echo.
echo   Check status : kubectl get pods -n kbook
echo   View logs   : kubectl logs -n kbook deploy/backend
echo   Rollback    : kubectl rollout undo deployment/backend -n kbook
echo.
pause
