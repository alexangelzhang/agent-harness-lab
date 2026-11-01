package com.example.langgraph4j.llm;

import java.util.function.Predicate;

/** LLM 抽象：图里的所有节点只依赖该接口（ScriptedLlm 与 JdkHttpLlm 都实现它）。 */
public interface LlmClient {

    /** 发送一段提示词，返回模型完整输出。 */
    String complete(String prompt);

    /**
     * 流式输出版本：逐块回调，边生成边消费。
     *
     * <p>默认实现退化为 {@link #complete} 一次整块回调——不发流式请求的
     * 实现不用写代码也能用。支持流式的实现（JdkHttpLlm 走 SSE）应尽快停止
     * 拉取响应，省掉没生成的 token。</p>
     *
     * @param onChunk 消费一块输出；返回 {@code false} 表示「够了，中止」
     *                （预算熔断、关键词收口都靠它）
     * @return {@code true} 表示自然生成完；{@code false} 表示被消费方中止
     */
    default boolean stream(String prompt, Predicate<String> onChunk) {
        return onChunk.test(complete(prompt));
    }
}
