@echo off
chcp 65001 >nul
setlocal
REM 没有 mysql 客户端时，用后端自带的 JDBC 驱动只读执行审计脚本。
REM
REM   set DB_URL=jdbc:mysql://<host>:3306/<db>?useUnicode=true^&characterEncoding=utf8^&useSSL=false^&serverTimezone=Asia/Shanghai
REM   set DB_USER=<只读账号>
REM   set DB_PASS=<密码>
REM   deploy\sql\run-audit.bat
REM
REM 连接信息只从环境变量读，不落文件、不进命令行历史。

set "HERE=%~dp0"
set "ROOT=%HERE%..\.."
if "%~1"=="" (set "SQLFILE=%HERE%profile-threshold-audit.sql") else (set "SQLFILE=%~1")

if "%DB_URL%"=="" ( echo 缺少 DB_URL & exit /b 2 )
if "%DB_USER%"=="" ( echo 缺少 DB_USER & exit /b 2 )
if "%DB_PASS%"=="" ( echo 缺少 DB_PASS & exit /b 2 )

set "WORK=%TEMP%\kbook-audit-%RANDOM%"
mkdir "%WORK%" || exit /b 1

echo 解析 JDBC 驱动 classpath（需要联网或本地已有依赖）…
call mvn -q -f "%ROOT%\backend\pom.xml" dependency:build-classpath -Dmdep.outputFile="%WORK%\cp.txt" -DincludeArtifactIds=mysql-connector-j
if errorlevel 1 ( echo classpath 解析失败 & rd /s /q "%WORK%" & exit /b 1 )

set /p CP=<"%WORK%\cp.txt"
javac -cp "%CP%" -d "%WORK%" "%HERE%RunSqlFile.java"
if errorlevel 1 ( echo 编译失败 & rd /s /q "%WORK%" & exit /b 1 )

java -cp "%CP%;%WORK%" RunSqlFile "%SQLFILE%"
set "RC=%ERRORLEVEL%"
rd /s /q "%WORK%"
exit /b %RC%
