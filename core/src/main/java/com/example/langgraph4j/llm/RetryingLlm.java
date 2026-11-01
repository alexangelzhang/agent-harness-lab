package com.example.langgraph4j.llm;

import java.util.List;

/**
 * 重试包装器：委托的 {@link LlmClient} 抛出<b>可重试的瞬时故障</b>时
 * 隔一小截退避再试，试满次数才抛。
 *
 * <p>真实网关的故障要分两种：{@code HTTP 400 tokenization failed}、
 * 连接超时这类是<b>瞬时的</b>，重试一次多半就好；{@code ScriptedLlm}
 * 弹尽、prompt 非法这类是<b>确定的</b>，重试纯属浪费。所以只按消息特征
 * 重试（默认认 400 / tokenization / timeout 字样），别的异常原样抛出。
 * {@link LangChain4jLlm} 刻意关了 langchain4j 自带重试
 * （{@code maxRetries(0)}，避免重试放大长推理的等待），瞬时恢复的责任
 * 就落在这个包装层——由 wiring 决定谁包，不绑架所有实现。</p>
 */
public final class RetryingLlm implements LlmClient {

    private final LlmClient delegate;
    private final int maxAttempts;
    private final List<String> retryableMarkers;

    /** 默认：最多试 3 次（1 次原调 + 2 次重试），退避 1s、2s。 */
    public RetryingLlm(LlmClient delegate) {
        this(delegate, 3, List.of("400", "tokenization", "timed out", "timeout"));
    }

    public RetryingLlm(LlmClient delegate, int maxAttempts, List<String> retryableMarkers) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.retryableMarkers = List.copyOf(retryableMarkers);
    }

    @Override
    public String complete(String prompt) {
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.complete(prompt);
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !retryable(e)) {
                    throw e;
                }
                System.out.println("  [retry] 第 " + attempt + " 次调用遇到瞬时故障（"
                        + firstLine(e.getMessage()) + "），" + attempt + "s 后重试...");
                sleep(attempt * 1000L);
            }
        }
    }

    private boolean retryable(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        for (String marker : retryableMarkers) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
