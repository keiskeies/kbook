#!/usr/bin/env bash
# 没有 mysql 客户端时，用后端自带的 JDBC 驱动只读执行审计脚本。
#
#   DB_URL='jdbc:mysql://<host>:3306/<db>?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai' \
#   DB_USER='<只读账号>' DB_PASS='<密码>' \
#   deploy/sql/run-audit.sh [deploy/sql/profile-threshold-audit.sql]
#
# 连接信息只从环境变量读，不落文件、不进命令行历史。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
sqlfile="${1:-$here/profile-threshold-audit.sql}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

: "${DB_URL:?缺少 DB_URL}"
: "${DB_USER:?缺少 DB_USER}"
: "${DB_PASS:?缺少 DB_PASS}"

echo "解析 JDBC 驱动 classpath（需要联网或本地已有依赖）…"
mvn -q -f "$root/backend/pom.xml" dependency:build-classpath \
  -Dmdep.outputFile="$work/cp.txt" -DincludeArtifactIds=mysql-connector-j

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=";" ;;
  *) sep=":" ;;
esac
# Windows（Git Bash）下 javac/java 只认 Windows 路径；Linux 原样返回
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
cp="$(cat "$work/cp.txt")"
javac -cp "$cp" -d "$(winpath "$work")" "$(winpath "$here")\RunSqlFile.java"
java -cp "$cp$sep$(winpath "$work")" RunSqlFile "$(winpath "$sqlfile")"
