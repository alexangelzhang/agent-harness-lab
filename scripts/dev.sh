#!/bin/sh
# 一条命令跑 demo：先把 parent pom 和 core 库装进本地仓库，再从 demo 模块 exec。
# 用法: scripts/dev.sh <exec-id> [exec.args 参数，原样传给 -Dexec.args]
# 示例: scripts/dev.sh loopbench
#       scripts/dev.sh workflow "real 3"
set -e
cd "$(dirname "$0")/.."
mvn -q install -N
mvn -q install -pl core
mvn -q compile -pl demo
if [ $# -ge 2 ]; then
    mvn -q exec:java@"$1" -pl demo -Dexec.args="$2"
else
    mvn -q exec:java@"$1" -pl demo
fi
