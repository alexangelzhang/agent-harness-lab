package com.example.harness.telemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MetricsRegistry 单测：计数/计时/仪表三类的累加、快照、导出、复位。 */
class MetricsRegistryTest {

    @Test
    void counterAccumulatesAndAcceptsNegativeDelta() {
        MetricsRegistry m = new MetricsRegistry();
        m.increment("a");
        m.increment("a");
        m.increment("a", 5);
        m.increment("a", -2);
        assertEquals(5, m.counterValue("a"));
        assertEquals(0, m.counterValue("never.touched"));
    }

    @Test
    void timerTracksCountTotalMaxAndAvg() {
        MetricsRegistry m = new MetricsRegistry();
        m.recordTimer("t", 10);
        m.recordTimer("t", 30);
        m.recordTimer("t", 20);
        MetricsRegistry.TimerSnapshot t = m.snapshot().timers().stream()
                .filter(x -> x.name().equals("t")).findFirst().orElseThrow();
        assertEquals(3, t.count());
        assertEquals(60, t.totalMs());
        assertEquals(30, t.maxMs());
        assertEquals(20.0, t.avgMs(), 0.001);
    }

    @Test
    void gaugeReadsLiveValue() {
        MetricsRegistry m = new MetricsRegistry();
        AtomicLong depth = new AtomicLong(7);
        m.gauge("queue.depth", depth::get);
        depth.set(9);
        assertEquals(9L, m.snapshot().gauges().get("queue.depth"));
    }

    @Test
    void snapshotIsAnIndependentCopy() {
        MetricsRegistry m = new MetricsRegistry();
        m.increment("c");
        MetricsRegistry.Snapshot s1 = m.snapshot();
        m.increment("c");
        assertEquals(1L, s1.counters().get("c"));
        assertEquals(2L, m.counterValue("c"));
    }

    @Test
    void prometheusExportSanitizesNamesAndQuotesLabels() {
        MetricsRegistry m = new MetricsRegistry();
        m.increment("llm.calls", 3);
        m.recordTimer("llm.duration", 12);
        m.gauge("llm.inflight.current", () -> 0);
        String prom = m.exportPrometheus();
        assertTrue(prom.contains("llm_calls 3"));
        assertTrue(prom.contains("llm_duration_count 1"));
        assertTrue(prom.contains("llm_duration_sum 12000000"));
        assertTrue(prom.contains("llm_duration_max 12000000"));
        assertTrue(prom.contains("llm_inflight_current 0"));
        // 带标签的名字：{k=v} 转 {k="v"}
        m.increment("x.y{runner=agentloop}", 2);
        assertTrue(m.exportPrometheus().contains("x_y{runner=\"agentloop\"} 2"));
    }

    @Test
    void resetClearsEverything() {
        MetricsRegistry m = new MetricsRegistry();
        m.increment("c");
        m.recordTimer("t", 1);
        m.gauge("g", () -> 1);
        m.reset();
        assertTrue(m.snapshot().counters().isEmpty());
        assertTrue(m.snapshot().timers().isEmpty());
        assertTrue(m.snapshot().gauges().isEmpty());
        assertFalse(m.exportPrometheus().lines().findAny().isPresent());
    }
}
