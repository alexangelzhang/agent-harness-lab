package com.example.harness.memory;

import java.util.concurrent.atomic.LongAdder;

/**
 * 记忆系统指标：反馈闭环的最小可观测面（智能体AI 文章「反馈闭环」节）。
 *
 * <p>存进去不等于有用——保存了但从来没被检索到、或者总召回过期的信息，
 * 都是系统病。这里记五类计数：触发率（该查的查了几次、跳过的几次）、
 * 三路各召回多少、最终注入上下文几条。检索质量/任务效果由 eval 包
 * 在任务级覆盖，这里补的是记忆级。</p>
 */
public final class MemoryMetrics {

    /** 当前计数快照。 */
    public record Snapshot(long triggersFired, long triggersSkipped,
                           long shortTermHits, long longTermHits, long kbHits,
                           long injectedLines, long janitorExpired,
                           long janitorArchived) {
    }

    private final LongAdder triggersFired = new LongAdder();
    private final LongAdder triggersSkipped = new LongAdder();
    private final LongAdder shortTermHits = new LongAdder();
    private final LongAdder longTermHits = new LongAdder();
    private final LongAdder kbHits = new LongAdder();
    private final LongAdder injectedLines = new LongAdder();
    private final LongAdder janitorExpired = new LongAdder();
    private final LongAdder janitorArchived = new LongAdder();

    public void recordTrigger(boolean recall) {
        (recall ? triggersFired : triggersSkipped).increment();
    }

    public void recordLane(String lane, int hits) {
        switch (lane) {
            case "short" -> shortTermHits.add(hits);
            case "long" -> longTermHits.add(hits);
            case "kb" -> kbHits.add(hits);
            default -> throw new IllegalArgumentException("未知召回路：" + lane);
        }
    }

    public void recordInjected(int lines) {
        injectedLines.add(lines);
    }

    /** 记一轮后台清理的结果（RecallPipeline 自动周期触发时调用）。 */
    public void recordJanitor(int expired, int archived) {
        janitorExpired.add(expired);
        janitorArchived.add(archived);
    }

    public Snapshot snapshot() {
        return new Snapshot(triggersFired.sum(), triggersSkipped.sum(),
                shortTermHits.sum(), longTermHits.sum(), kbHits.sum(),
                injectedLines.sum(), janitorExpired.sum(), janitorArchived.sum());
    }

    @Override
    public String toString() {
        Snapshot s = snapshot();
        return "触发 " + s.triggersFired() + " / 跳过 " + s.triggersSkipped()
                + "，三路召回 短期 " + s.shortTermHits() + " / 长期 "
                + s.longTermHits() + " / 外部 " + s.kbHits()
                + "，注入上下文 " + s.injectedLines() + " 条"
                + "，后台清理 到期 " + s.janitorExpired() + " / 归档 "
                + s.janitorArchived();
    }
}
