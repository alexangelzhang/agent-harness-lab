package com.example.harness;

import com.example.agentloop.LoopResult;
import com.example.agentloop.LoopStep;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * 一次运行的统一档案：原语版 loop 和状态图版落成同一张记录——
 * 生产环境的审计底账。出了事能回答「这次运行干了什么、怎么收场的」；
 * 攒多了能回答「哪类终态最多、平均烧多少调用、耗时趋势」。
 *
 * <p>终态（terminal）显式记录，不靠事后猜：原语版映射成
 * SUCCESS / EXHAUSTED / ENV_ABORTED / BUDGET_EXHAUSTED 四种；
 * 图版直接记 verdict 通道值（ACCEPT / JUDGE_PASS / JUDGE_FAIL /
 * JUDGE_REJECT / ENV_ABORTED / BUDGET_EXHAUSTED），报错记 ERROR，
 * 顶满轮数无终裁记 MAX_ROUNDS。</p>
 *
 * @param runId      运行编号（调用方生成，审计和回放都靠它定位）
 * @param runner     循环载体：agentloop / reflection-graph
 * @param task       任务
 * @param terminal   终态（见上）
 * @param steps      逐步轨迹：loop 版是「成果+评估」，图版是节点路径
 * @param llmCalls   LLM 调用数（成本代理量）
 * @param durationMs 耗时
 * @param timestamp  落盘时间（epoch millis）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunRecord(
        String runId,
        String runner,
        String task,
        String terminal,
        List<Step> steps,
        int llmCalls,
        long durationMs,
        long timestamp) {

    /** 一步轨迹：loop 版 turn=轮次、output=成果、verdictKind=PASS/RETRY/ENV_FAIL；
     *  图版 turn=序号、output=节点名、verdictKind=NODE。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(int turn, String output, String verdictKind, String reason, long durationMs) {
    }

    /** 原语版：LoopResult → 档案。stopReason 前缀区分环境故障和预算耗尽。 */
    public static RunRecord ofLoop(String runId, String task, LoopResult result,
                                   int llmCalls, long durationMs) {
        String terminal = result.completed() ? "SUCCESS"
                : result.stopReason().startsWith("环境故障") ? "ENV_ABORTED"
                : result.stopReason().startsWith("预算耗尽") ? "BUDGET_EXHAUSTED"
                : "EXHAUSTED";
        List<Step> steps = result.steps().stream().map(RunRecord::stepOf).toList();
        return new RunRecord(runId, "agentloop", task, terminal, steps,
                llmCalls, durationMs, System.currentTimeMillis());
    }

    private static Step stepOf(LoopStep s) {
        return new Step(s.turn(), s.output(), s.verdict().kind().name(),
                s.verdict().reason(), s.durationMs());
    }

    /** 图版：节点路径 + 终态 → 档案。terminal 取 verdict 通道值，报错优先记 ERROR。 */
    public static RunRecord ofGraph(String runId, String task, List<String> nodePath,
                                    String verdict, Optional<String> error,
                                    int llmCalls, long durationMs) {
        String terminal = error.filter(e -> !e.isBlank()).map(e -> "ERROR")
                .orElse(verdict == null || verdict.isBlank() ? "MAX_ROUNDS" : verdict);
        List<Step> steps = new java.util.ArrayList<>(nodePath.size());
        for (int i = 0; i < nodePath.size(); i++) {
            steps.add(new Step(i + 1, nodePath.get(i), "NODE", "", 0));
        }
        return new RunRecord(runId, "reflection-graph", task, terminal, List.copyOf(steps),
                llmCalls, durationMs, System.currentTimeMillis());
    }
}
