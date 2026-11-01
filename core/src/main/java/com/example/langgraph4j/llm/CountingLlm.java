package com.example.langgraph4j.llm;

/**
 * 计数包装器：在委托的 LlmClient 外面包一层，统计 complete() 调用次数。
 * 供轨迹评估（Ch8 指标：LLM 调用数 / Token 成本的代理量）使用。
 */
public class CountingLlm implements LlmClient {

    private final LlmClient delegate;
    private int calls;

    public CountingLlm(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String complete(String prompt) {
        calls++;
        return delegate.complete(prompt);
    }

    public int calls() {
        return calls;
    }
}
