@echo off
chcp 65001 >nul
title KBook 本地开发启动
cd /d "%~dp0..\.."

echo ============================================
echo   KBook 本地开发启动
echo ============================================
echo.
echo 启动前请确认以下服务已在本地运行：
echo   - MySQL 8 （端口 3306）
echo   - Redis 7 （端口 6379）
echo   - Elasticsearch 8.13 （端口 9200）
echo   - Qdrant v1.12 （gRPC 端口 6334）
echo.

REM ---- 后端 ----
echo [1/2] 启动后端（Spring Boot）...
cd backend
if not exist "target\kbook-server-1.0.0.jar" (
    echo 未找到 JAR 包，执行 mvn package...
    call mvn package -DskipTests -q
)

start "KBook-Backend" java -Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dkbook.ai-config.path=src/main/resources/config/ai-config.json -jar target\kbook-server-1.0.0.jar
echo ✓ 后端已启动（新窗口），日志输出到控制台

cd ..

REM ---- 前端 ----
echo [2/2] 启动前端（Vite dev server）...
cd frontend
echo.
echo ============================================
echo   KBook 本地开发已启动！
echo ============================================
echo.
echo   前端地址：  http://localhost:15173
echo   后端地址：  http://localhost:8181
echo.
echo   关闭所有窗口即可停止服务
echo.

start "KBook-Frontend" cmd /c "npm run dev"
pause
