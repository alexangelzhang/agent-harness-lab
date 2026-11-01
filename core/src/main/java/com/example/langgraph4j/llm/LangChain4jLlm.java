package com.example.langgraph4j.llm;

import com.example.harness.config.AgentConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * 基于 langchain4j {@link ChatModel} 的 LlmClient 实现（生产级写法）。
 *
 * <p>与 {@link KimiLlm}（JDK HttpClient 手写版，零依赖）相比，这里直接复用
 * langchain4j 官方的 OpenAI 兼容客户端：序列化、重试、流式、工具调用等
 * 都由 langchain4j 维护，且与 langgraph4j-langchain4j 集成模块版本对齐（1.22.0）。</p>
 *
 * <p>两个关键适配：</p>
 * <ul>
 *   <li><b>temperature 不能设置</b>——Kimi 网关的模型只允许 temperature=1，
 *       OpenAiChatModel 的 temperature 是 Double，不调用 setter 即不会序列化进请求体；
 *       一旦带上自定义值，网关会报 {@code invalid temperature: only 1 is allowed}。</li>
 *   <li><b>reasoning 回退</b>——K2 是推理模型，纯推理时 content 可能为空，
 *       此时回退到 {@link AiMessage#thinking()}（langchain4j 1.22 暴露的推理内容）。</li>
 * </ul>
 */
public final class LangChain4jLlm implements LlmClient {

    private final ChatModel model;

    public LangChain4jLlm(ChatModel model) {
        this.model = model;
    }

    /** 默认配置：指向 Kimi agent 网关（OpenAI 兼容），环境变量与 {@link KimiLlm} 相同。 */
    public static LangChain4jLlm kimi() {
        return new LangChain4jLlm(buildKimiModel());
    }

    /** 构建指向 Kimi agent 网关的 OpenAiChatModel（供本类与 {@link WebSearchLlm} 复用）。 */
    public static OpenAiChatModel buildKimiModel() {
        AgentConfig config = AgentConfig.load();

        return OpenAiChatModel.builder()
                .baseUrl(config.chatBase())
                .apiKey(config.requireApiKey())
                .modelName(config.model())
                // 刻意不设置 temperature：网关模型只允许 1，带了会被拒绝
                // K2 是推理模型，思考链很长（重负载时单次调用可达数分钟）——
                // 超时放宽到 300s 并关闭重试，避免重试放大等待时间
                .timeout(Duration.ofSeconds(300))
                .maxRetries(0)
                .build();
    }

    @Override
    public String complete(String prompt) {
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        AiMessage ai = response.aiMessage();
        String text = ai.text();
        if (text != null && !text.isBlank()) {
            return text;
        }
        String thinking = ai.thinking();
        return thinking == null ? "" : thinking;
    }
}
