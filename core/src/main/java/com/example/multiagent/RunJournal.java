package com.example.multiagent;

import com.example.harness.RunRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 多 Agent 运行的审计留档：把分派/交接的 trace 收进 {@link RunRecord.Step}
 * 列表，落成与单体 loop / 状态图 / 动态工作流<b>同一张底账</b>的记录——
 * runner 区分拓扑（multiagent:supervisor 等），终态显式区分
 * SUCCESS 与 GUARD_BLOCKED（护栏拦截是正常终态，不是异常）。
 *
 * <p>用法：把 {@link #trace()} 当作各 Pattern.run 的 trace 回调传入，
 * 跑完后 {@link #toRecord} 交给 RunStore 落盘。谁留档由 wiring 决定，
 * Pattern 本身不感知存储——跟单体 loop 的档案同一套纪律。</p>
 */
public final class RunJournal {

    private final List<RunRecord.Step> steps = new ArrayList<>();

    /** trace 回调：who 记进 reason（分派对象/交接棒/护栏），what 记进 output。 */
    public BiConsumer<String, String> trace() {
        return (who, what) -> steps.add(new RunRecord.Step(
                steps.size() + 1, what, "AGENT", who, 0));
    }

    /** 已收集的步骤快照（防御性拷贝，改返回列表不影响内部状态）。 */
    public List<RunRecord.Step> steps() {
        return List.copyOf(steps);
    }

    /**
     * 落成档案记录。finalAnswer 含「护栏拦截」时终态记 GUARD_BLOCKED
     * ——被护栏拦下是设计内的收场，审计要把它和跑完区分开。
     */
    public RunRecord toRecord(String runId, String runner, String task,
                              String finalAnswer, int llmCalls, long durationMs) {
        String terminal = finalAnswer != null && finalAnswer.contains("护栏拦截")
                ? "GUARD_BLOCKED"
                : "SUCCESS";
        return new RunRecord(runId, runner, task, terminal, steps(),
                llmCalls, durationMs, System.currentTimeMillis());
    }
}
