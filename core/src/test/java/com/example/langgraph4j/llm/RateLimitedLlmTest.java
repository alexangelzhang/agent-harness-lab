package com.example.langgraph4j.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.time.Duration.ofSeconds;

/** RateLimitedLlm 单测：并发闸峰值、排队不丢请求、间隔闸、异常时信号量不泄漏。 */
class RateLimitedLlmTest {

    @Test
    void concurrencyCapHoldsPeakAtLimit() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        LlmClient brain = prompt -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            sleep(30);
            inFlight.decrementAndGet();
            return "答：" + prompt;
        };
        LlmClient gated = new RateLimitedLlm(brain, 2, 0);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                String task = "问题" + i;
                Callable<String> call = () -> gated.complete(task);
                futures.add(pool.submit(call));
            }
            List<String> answers = new ArrayList<>();
            for (Future<String> f : futures) {
                answers.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(6, answers.size());
            assertTrue(answers.stream().allMatch(a -> a.startsWith("答：")), "排队不丢请求，6 个全有答复");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, peak.get(), "6 个并发调用方，同时在飞峰值必须恰为上限 2");
    }

    @Test
    void unlimitedModePassesThroughWithoutQueueing() {
        LlmClient gated = new RateLimitedLlm(prompt -> "ok", 0, 0);
        assertEquals("ok", gated.complete("a"));
        assertEquals("ok", gated.complete("b"));
    }

    @Test
    void minIntervalSpacesOutSequentialCalls() {
        LlmClient gated = new RateLimitedLlm(new ScriptedLlm().says("1", "2", "3", "4", "5"), 0, 60);
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            gated.complete("q" + i);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs >= 240, "5 次调用 4 个间隔，每次 ≥60ms，总计应 ≥240ms（实测 " + elapsedMs + "ms）");
    }

    @Test
    void delegateExceptionReleasesPermit() {
        AtomicInteger attempts = new AtomicInteger();
        LlmClient flaky = prompt -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("第一次必炸");
            }
            return "第二次好了";
        };
        LlmClient gated = new RateLimitedLlm(flaky, 1, 0);
        assertThrows(IllegalStateException.class, () -> gated.complete("a"));
        // 信号量若在异常路径泄漏，这次调用会永远排队
        assertTimeoutPreemptively(ofSeconds(5), () -> assertEquals("第二次好了", gated.complete("b")));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
