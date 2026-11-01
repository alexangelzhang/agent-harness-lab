package com.example.harness.telemetry;

import com.example.langgraph4j.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TelemetryLlm 单测：complete/stream 两路的计数、失败、在飞配对、字符量。 */
class TelemetryLlmTest {

    @Test
    void completeCountsCallsCharsAndDuration() {
        MetricsRegistry m = new MetricsRegistry();
        LlmClient llm = new TelemetryLlm(prompt -> "回答", m, "test");
        String out = llm.complete("问题");
        assertEquals("回答", out);
        assertEquals(1, m.counterValue("llm.calls{label=test}"));
        assertEquals(2, m.counterValue("llm.promptChars"));
        assertEquals(2, m.counterValue("llm.outputChars"));
        assertEquals(0, m.counterValue("llm.errors{label=test}"));
        assertEquals(0, m.counterValue("llm.inflight{label=test}"));
        assertEquals(1, m.snapshot().timers().stream()
                .filter(t -> t.name().equals("llm.duration{label=test}")).count());
    }

    @Test
    void exceptionCountedAndRethrownAndInflightRestored() {
        MetricsRegistry m = new MetricsRegistry();
        LlmClient llm = new TelemetryLlm(prompt -> {
            throw new IllegalStateException("boom");
        }, m, "flaky");
        assertThrows(IllegalStateException.class, () -> llm.complete("q"));
        assertEquals(1, m.counterValue("llm.errors{label=flaky}"));
        assertEquals(1, m.counterValue("llm.calls{label=flaky}"));
        assertEquals(0, m.counterValue("llm.inflight{label=flaky}"),
                "在飞数必须收尾归零，+1/-1 配对");
    }

    @Test
    void inflightPeaksDuringNestedCalls() {
        MetricsRegistry m = new MetricsRegistry();
        AtomicInteger peak = new AtomicInteger();
        LlmClient inner = new TelemetryLlm(prompt -> "ok", m, "n");
        LlmClient outer = new TelemetryLlm(prompt -> {
            peak.accumulateAndGet((int) m.counterValue("llm.inflight{label=n}"), Math::max);
            inner.complete("nested");
            return "外层答复";
        }, m, "n");
        outer.complete("start");
        assertEquals(1, peak.get(), "内层调用时外层还在飞，观测到 1");
        assertEquals(0, m.counterValue("llm.inflight{label=n}"));
        assertEquals(2, m.counterValue("llm.calls{label=n}"));
    }

    @Test
    void streamCountsOnceAndCollectsOutputChars() {
        MetricsRegistry m = new MetricsRegistry();
        List<String> consumed = new ArrayList<>();
        LlmClient llm = new TelemetryLlm(new com.example.langgraph4j.llm.ScriptedLlm()
                .says("流式答复"), m, "s");
        boolean completed = llm.stream("问题", chunk -> {
            consumed.add(chunk);
            return true;
        });
        assertTrue(completed);
        assertEquals(1, m.counterValue("llm.calls{label=s}"), "流式算一次调用");
        assertEquals(4, m.counterValue("llm.outputChars"), "字符量按委托实际吐出的块累计");
        assertFalse(consumed.isEmpty());
    }

    @Test
    void streamAbortStillCountsAndRestoresInflight() {
        MetricsRegistry m = new MetricsRegistry();
        LlmClient llm = new TelemetryLlm(new com.example.langgraph4j.llm.ScriptedLlm()
                .says("很长的一段输出内容用来演练中途中止"), m, "s");
        boolean completed = llm.stream("问题", chunk -> false);
        assertFalse(completed, "消费方返 false = 被中止");
        assertEquals(1, m.counterValue("llm.calls{label=s}"));
        assertEquals(0, m.counterValue("llm.inflight{label=s}"));
        assertTrue(m.counterValue("llm.outputChars") > 0);
    }
}
