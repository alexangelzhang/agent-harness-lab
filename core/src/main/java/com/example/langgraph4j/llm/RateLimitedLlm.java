package com.example.langgraph4j.llm;

import java.util.concurrent.Semaphore;

/**
 * 限流包装器：给真实 LLM 调用加两道资源闸——<b>并发上限</b>（信号量，
 * 超出的调用方原地排队，这就是背压：压回去而不是压垮）和<b>最小调用
 * 间隔</b>（两次发起之间至少隔多少毫秒，防突发打满网关配额）。
 *
 * <p>与 {@link RetryingLlm} 的职责正交，推荐套法：
 * {@code RetryingLlm(RateLimitedLlm(kimi))}——限流在内、重试在外，
 * 每次重试也要重新拿通行证，重试风暴冲不破并发闸。</p>
 *
 * <p>demo 里所有 {@code new KimiLlm()} 目前是串行调用，不接它；它面向
 * 多 Agent 并行（dispatchParallel）、批量评估（k 次 trial）这类真实并发
 * 场景——并发一上量，先有限流再谈别的。</p>
 */
public final class RateLimitedLlm implements LlmClient {

    private final LlmClient delegate;
    private final Semaphore permits;
    private final long minIntervalMillis;
    private final Object intervalLock = new Object();
    private long nextAllowedStartMillis;

    /**
     * @param maxConcurrent      同时在飞的调用上限（≤0 表示不限并发）
     * @param minIntervalMillis  两次调用的最小发起间隔（≤0 表示不限间隔）
     */
    public RateLimitedLlm(LlmClient delegate, int maxConcurrent, long minIntervalMillis) {
        this.delegate = delegate;
        this.permits = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    @Override
    public String complete(String prompt) {
        if (permits == null) {
            waitInterval();
            return delegate.complete(prompt);
        }
        permits.acquireUninterruptibly();
        try {
            waitInterval();
            return delegate.complete(prompt);
        } finally {
            permits.release();
        }
    }

    /** 间隔闸：距上一次发起不足 minInterval 就睡到点，纯串行场景无开销。 */
    private void waitInterval() {
        if (minIntervalMillis <= 0) {
            return;
        }
        synchronized (intervalLock) {
            long now = System.currentTimeMillis();
            long wait = nextAllowedStartMillis - now;
            if (wait > 0) {
                sleep(wait);
                now = System.currentTimeMillis();
            }
            nextAllowedStartMillis = now + minIntervalMillis;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
