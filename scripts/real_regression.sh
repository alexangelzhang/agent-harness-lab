#!/bin/bash
# 真实轨全量回归：18 分片（真实 LLM，需 LLM_API_KEY）。
#
# 分片清单（与 LANGGRAPH4J 时间线「N 分片全绿」口径一致）：
#   workflow real 1-6（动态工作流真实模式）  6 片
#   topologyreal 1-6（多 Agent 拓扑真实对照组） 6 片
#   supervisorreal 1-3（Supervisor 真实对照组） 3 片
#   streamreal 1-2（SSE 流式真实对照组）      2 片
#   parallel real（并行 fan-out 真实主管分派） 1 片
#
# 用法：scripts/real_regression.sh          # 全量 18 片
#       scripts/real_regression.sh workflow  # 只跑某一组
# 结果：控制台 + runs/real-regression-<时间戳>.log，末尾有 PASS/FAIL 汇总。
set -u
cd "$(dirname "$0")/.."

GROUP="${1:-all}"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG="runs/real-regression-${STAMP}.log"
mkdir -p runs
: > "$LOG"

pass=0
fail=0
failed_list=()

run_shard() {
    local id="$1" args="$2"
    echo "=== [$(date +%H:%M:%S)] $id \"$args\" ===" | tee -a "$LOG"
    if mvn -q exec:java@"$id" -pl demo -Dexec.args="$args" >> "$LOG" 2>&1; then
        echo "--- PASS $id \"$args\"" | tee -a "$LOG"
        pass=$((pass + 1))
    else
        echo "--- FAIL $id \"$args\"（exit $?，详情见上方同名片段）" | tee -a "$LOG"
        fail=$((fail + 1))
        failed_list+=("$id \"$args\"")
    fi
}

echo "构建 core + demo ..." | tee -a "$LOG"
mvn -q install -N >> "$LOG" 2>&1 && mvn -q install -pl core >> "$LOG" 2>&1 && mvn -q compile -pl demo >> "$LOG" 2>&1
if [ $? -ne 0 ]; then
    echo "!! 构建失败，回归中止（见 $LOG）"
    exit 2
fi

case "$GROUP" in
    workflow)   for i in 1 2 3 4 5 6; do run_shard workflow "real $i"; done ;;
    topology)   for i in 1 2 3 4 5 6; do run_shard topologyreal "$i"; done ;;
    supervisor) for i in 1 2 3;       do run_shard supervisorreal "$i"; done ;;
    stream)     for i in 1 2;         do run_shard streamreal "$i"; done ;;
    parallel)   run_shard parallel "real" ;;
    all)
        for i in 1 2 3 4 5 6; do run_shard workflow "real $i"; done
        for i in 1 2 3 4 5 6; do run_shard topologyreal "$i"; done
        for i in 1 2 3;       do run_shard supervisorreal "$i"; done
        for i in 1 2;         do run_shard streamreal "$i"; done
        run_shard parallel "real"
        ;;
    *) echo "未知分组：$GROUP（可选 workflow/topology/supervisor/stream/parallel/all）"; exit 2 ;;
esac

echo | tee -a "$LOG"
echo "========== 汇总：PASS $pass / FAIL $fail（共 $((pass + fail)) 片）==========" | tee -a "$LOG"
for f in "${failed_list[@]:-}"; do
    [ -n "$f" ] && echo "  FAIL: $f" | tee -a "$LOG"
done
echo "日志：$LOG"
[ "$fail" -eq 0 ]
