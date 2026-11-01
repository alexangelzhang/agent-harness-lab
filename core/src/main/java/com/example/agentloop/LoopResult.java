package com.example.agentloop;

import java.util.List;

/**
 * 一次 loop 运行的结果。
 *
 * @param completed  是否达标收工（false = 轮数耗尽或环境故障超限，没达成目标）
 * @param steps      全程轨迹（环境故障也记录在案）
 * @param stopReason 收场方式：达标收工 / 轮数耗尽 / 环境故障超限——
 *                   失败怎么失败的要看这个，不能只看 completed
 */
public record LoopResult(boolean completed, List<LoopStep> steps, String stopReason) {

    /** 达标收工。 */
    public static LoopResult success(List<LoopStep> steps) {
        return new LoopResult(true, steps, "达标收工");
    }

    /** 修复轮数耗尽，没达成目标。 */
    public static LoopResult exhausted(List<LoopStep> steps) {
        return new LoopResult(false, steps, "轮数耗尽，未达标");
    }

    /** 环境故障超过上限，被迫终止（如 LLM 网关反复超时）。 */
    public static LoopResult envAborted(List<LoopStep> steps, String reason) {
        return new LoopResult(false, steps, reason);
    }

    /** 任务预算耗尽，转人工（LLM 调用 / token / 成本超上限）。 */
    public static LoopResult budgetExhausted(List<LoopStep> steps, String reason) {
        return new LoopResult(false, steps, reason);
    }

    public int turns() {
        return steps.size();
    }

    public String finalOutput() {
        return steps.isEmpty() ? "" : steps.get(steps.size() - 1).output();
    }
}
