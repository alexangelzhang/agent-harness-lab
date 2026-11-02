package com.example.harness.telemetry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 进程内指标注册表：计数器 / 计时器 / 仪表三类，全部线程安全。
 *
 * <p>生产语义对标 Micrometer 的最小子集，但不引依赖——agent-core 保持
 * 零外部观测框架，导出侧给两种格式：</p>
 * <ul>
 *   <li>{@link #exportPrometheus()}：Prometheus 文本展示格式（v0.0.4），
 *       落地就是 {@code .prom} 文件，Grafana / VictoriaMetrics 的
 *       {@code textfile collector} 直接抓——不建 pushgateway 也有图看；</li>
 *   <li>{@link #snapshot()}：不可变快照，报表（RunReportScenario 一类）直接读。</li>
 * </ul>
 *
 * <p>命名约定（和 RunStore 底账互补）：底账答「这一次运行发生了什么」，
 * 指标答「这一批运行整体什么样」——计数器名建议带业务前缀，如
 * {@code llm.calls} / {@code guard.blocked}，标签维度拼进名字
 * （{@code llm.calls{runner=reflection-graph}} 文本格式里写成
 * {@code llm_calls{runner="reflection-graph"}}）。</p>
 */
public final class MetricsRegistry {

    /** 计时器快照：次数 / 总耗时 / 最大单次耗时。 */
    public record TimerSnapshot(String name, long count, long totalMs, long maxMs) {

        public double avgMs() {
            return count == 0 ? 0.0 : (double) totalMs / count;
        }
    }

    /** 全量快照：counter / timer / gauge 各一份不可变拷贝。 */
    public record Snapshot(Map<String, Long> counters,
                           List<TimerSnapshot> timers,
                           Map<String, Long> gauges) {
    }

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerAccum> timers = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    /** 计数器 +1。 */
    public void increment(String name) {
        increment(name, 1);
    }

    /** 计数器 +delta（delta 为负就是回退，留给「进行中」类指标收尾用）。 */
    public void increment(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    /** 记一次耗时（毫秒）。 */
    public void recordTimer(String name, long durationMs) {
        timers.computeIfAbsent(name, k -> new TimerAccum()).add(durationMs);
    }

    /**
     * 注册一个仪表：值不是攒出来的，是随时读出来的（队列深度、在飞请求数）。
     * 同一个名字重复注册，后来的覆盖——仪表是「现在怎么看」，不是历史。
     */
    public void gauge(String name, LongSupplier value) {
        gauges.put(name, value);
    }

    public Snapshot snapshot() {
        Map<String, Long> counterCopy = new ConcurrentHashMap<>();
        counters.forEach((k, v) -> counterCopy.put(k, v.sum()));
        List<TimerSnapshot> timerCopy = timers.entrySet().stream()
                .map(e -> e.getValue().snapshot(e.getKey()))
                .sorted(java.util.Comparator.comparing(TimerSnapshot::name))
                .toList();
        Map<String, Long> gaugeCopy = new ConcurrentHashMap<>();
        gauges.forEach((k, v) -> gaugeCopy.put(k, v.getAsLong()));
        return new Snapshot(Map.copyOf(counterCopy), timerCopy, Map.copyOf(gaugeCopy));
    }

    /**
     * Prometheus 文本展示格式导出：名字里的 {@code .} 转 {@code _}，
     * {@code {k=v}} 段原样转成标签；计数器/仪表直接出值，
     * 计时器出 {@code _count/_sum/_max} 三条（max 走 gauge 语义）。
     */
    public String exportPrometheus() {
        StringBuilder sb = new StringBuilder();
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                sb.append(promName(e.getKey())).append(' ').append(e.getValue().sum()).append('\n'));
        timers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String base = promName(e.getKey());
            TimerSnapshot t = e.getValue().snapshot(e.getKey());
            sb.append(base).append("_count ").append(t.count()).append('\n');
            sb.append(base).append("_sum ").append(t.totalMs() * 1_000_000L).append('\n');
            sb.append(base).append("_max ").append(t.maxMs() * 1_000_000L).append('\n');
        });
        gauges.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                sb.append(promName(e.getKey())).append(' ').append(e.getValue().getAsLong()).append('\n'));
        return sb.toString();
    }

    /** 清空全部指标（回归测试每个场景要独立计数时用）。 */
    public void reset() {
        counters.clear();
        timers.clear();
        gauges.clear();
    }

    /** 读单个计数器当前值（没注册过就是 0）——gauge 注册拿实时值时用。 */
    public long counterValue(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0 : adder.sum();
    }

    /** "llm.calls{runner=agentloop}" → "llm_calls{runner=\"agentloop\"}" */
    private static String promName(String name) {
        int brace = name.indexOf('{');
        if (brace < 0) {
            return name.replace('.', '_');
        }
        String metric = name.substring(0, brace).replace('.', '_');
        String labels = name.substring(brace + 1, name.length() - 1)
                .replace("=", "=\"");
        return metric + '{' + labels + "\"}";
    }

    /** 计时器累加器：count/total/max 三个原子量，无锁并发。 */
    private static final class TimerAccum {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();

        void add(long durationMs) {
            count.increment();
            totalMs.add(durationMs);
            maxMs.accumulateAndGet(durationMs, Math::max);
        }

        TimerSnapshot snapshot(String name) {
            return new TimerSnapshot(name, count.sum(), totalMs.sum(), maxMs.get());
        }
    }
}
