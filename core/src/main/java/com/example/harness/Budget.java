package com.example.harness;

/**
 * 任务预算：生产级 Agent 的油表。只数轮数（maxTurns）不知道烧了多少钱——
 * 预算给一次运行装上硬性消耗上限：LLM 调用次数、token 数或估算成本，
 * 由调用方用一个共享 {@link Meter} 计量（通常是 CountingLlm::calls
 * 或从 LLM 响应里读 usage 的计量器）。
 *
 * <p>预算耗尽的处理约定（见 {@code AgentLoop} / {@code ReflectionGraph}）：
 * 不是悄悄跑完，而是<b>显式收场并标明「预算耗尽，转人工」</b>——
 * 和达标收工、轮数耗尽、环境故障超限并列成第四种终态。
 * 按轮结算：一轮内的调用可能微量超出上限，停止发生在下一个检查点。</p>
 */
public final class Budget {

    /** 用量计量：返回本次运行已消耗的数量。单位由 wiring 决定（次 / token / 分）。 */
    public interface Meter {
        long used();
    }

    private final Meter meter;
    private final long cap;
    private final String unit;

    private Budget(Meter meter, long cap, String unit) {
        if (cap < 1) {
            throw new IllegalArgumentException("预算上限至少为 1");
        }
        this.meter = meter;
        this.cap = cap;
        this.unit = unit;
    }

    /** 按 LLM 调用次数限预算。 */
    public static Budget calls(Meter meter, long maxCalls) {
        return new Budget(meter, maxCalls, "LLM 调用 " + maxCalls + " 次");
    }

    /** 按 token 数限预算。 */
    public static Budget tokens(Meter meter, long maxTokens) {
        return new Budget(meter, maxTokens, "token " + maxTokens);
    }

    /** 自定义单位（如估算成本「分」）。 */
    public static Budget custom(Meter meter, long cap, String unit) {
        return new Budget(meter, cap, unit);
    }

    /** 预算是否已耗尽（达到或超过上限）。 */
    public boolean exhausted() {
        return meter.used() >= cap;
    }

    /** 预算的人类可读描述，用于终态原因。 */
    public String describe() {
        return unit;
    }
}
