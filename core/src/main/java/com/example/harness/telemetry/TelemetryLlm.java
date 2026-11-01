package com.example.harness.telemetry;

import com.example.langgraph4j.llm.LlmClient;

import java.util.function.Predicate;

/**
 * LLM 调用观测装饰器：包在任意 {@link LlmClient} 外面，自动攒四类指标——
 *
 * <ul>
 *   <li>{@code llm.calls{label=...}} / {@code llm.errors{label=...}}：调用与失败计数
 *       （失败 = 委托抛异常；RetryingLlm 在外层时这里记的是重试全部失败后的那次）；</li>
 *   <li>{@code llm.duration{label=...}}：耗时计时器（p 值看 _max/_avg，够用）；</li>
 *   <li>{@code llm.promptChars} / {@code llm.outputChars}：字符量——没有真实
 *       token 用量时的成本代理量（和 CountingLlm 的调用数互补：一个管几次，一个管多大）。</li>
 * </ul>
 *
 * <p>label 由调用方给（如 {@code "reflection-graph"} / {@code "trial-3"}），
 * 维度拼进指标名——注册表是无依赖的最小实现，名字即维度。
 * 线程安全：指标攒进 {@link MetricsRegistry}，本类本身无状态，并发 invoke 随便包。</p>
 */
public class TelemetryLlm implements LlmClient {

    private final LlmClient delegate;
    private final MetricsRegistry metrics;
    private final String label;

    public TelemetryLlm(LlmClient delegate, MetricsRegistry metrics, String label) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.label = label;
    }

    @Override
    public String complete(String prompt) {
        metrics.increment("llm.calls{label=" + label + "}");
        metrics.increment("llm.promptChars", prompt.length());
        metrics.increment("llm.inflight{label=" + label + "}");
        long t0 = System.nanoTime();
        try {
            String output = delegate.complete(prompt);
            metrics.increment("llm.outputChars", output.length());
            return output;
        } catch (RuntimeException e) {
            metrics.increment("llm.errors{label=" + label + "}");
            throw e;
        } finally {
            metrics.increment("llm.inflight{label=" + label + "}", -1);
            metrics.recordTimer("llm.duration{label=" + label + "}",
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    @Override
    public boolean stream(String prompt, Predicate<String> onChunk) {
        // 流式走同一路计数：调用/失败/promptChars 都在，逐块中止只是输出面的事
        metrics.increment("llm.calls{label=" + label + "}");
        metrics.increment("llm.promptChars", prompt.length());
        metrics.increment("llm.inflight{label=" + label + "}");
        long t0 = System.nanoTime();
        StringBuilder collected = new StringBuilder();
        try {
            boolean completed = delegate.stream(prompt, chunk -> {
                collected.append(chunk);
                return onChunk.test(chunk);
            });
            metrics.increment("llm.outputChars", collected.length());
            return completed;
        } catch (RuntimeException e) {
            metrics.increment("llm.errors{label=" + label + "}");
            throw e;
        } finally {
            metrics.increment("llm.inflight{label=" + label + "}", -1);
            metrics.recordTimer("llm.duration{label=" + label + "}",
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }
}
