package com.example.langgraph4j.llm;

import com.example.harness.config.AgentConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容接口的真实 LLM 客户端：JDK 自带 HttpClient + 极简 JSON 字段提取，
 * 不引入任何第三方依赖（零依赖参考实现，生产写法见 {@link LangChain4jLlm}）。
 *
 * <p>配置（环境变量，经 {@link AgentConfig} 装配）：</p>
 * <ul>
 *   <li>{@code LLM_API_KEY} —— 必填（旧名 {@code KIMI_API_KEY} 仍兼容）；</li>
 *   <li>{@code LLM_BASE_URL} —— 可选，任意 OpenAI 兼容端点；</li>
 *   <li>{@code LLM_MODEL} —— 可选，模型名。</li>
 * </ul>
 * 推理模型的 content 可能为空（思考在 reasoning_content 里），两者都提取、优先 content。
 */
public class JdkHttpLlm implements LlmClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;
    private final String endpoint;
    private final String model;

    public JdkHttpLlm() {
        this(AgentConfig.load());
    }

    private JdkHttpLlm(AgentConfig config) {
        this(config.chatBase(), config.requireApiKey(), config.model());
    }

    public JdkHttpLlm(String base, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少 LLM_API_KEY 环境变量");
        }
        this.apiKey = apiKey;
        this.endpoint = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions";
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],"max_tokens":4096}
                """.formatted(model, quote(prompt));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            String resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String content = extractJsonString(resp, "\"content\":");
            if (content == null || content.isBlank()) {
                content = extractJsonString(resp, "\"reasoning_content\":");
            }
            if (content == null) {
                throw new IllegalStateException("LLM 响应异常：" + resp.substring(0, Math.min(300, resp.length())));
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("调用 LLM 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 LLM 被中断", e);
        }
    }

    /**
     * SSE 流式输出：边读边回调，消费方返回 {@code false} 时直接关掉响应流，
     * 没生成的 token 就不计费。
     *
     * <p>与 {@link #complete} 完全独立的代码路径：那边是整段 JSON 提取，
     * 这边是逐行 {@code data:} 解析，互不影响——改一处不碰另一处的
     * 转义敏感性。delta 优先取 content，空则取 reasoning_content，
     * 与 complete 的口径一致。</p>
     */
    @Override
    public boolean stream(String prompt, java.util.function.Predicate<String> onChunk) {
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],"max_tokens":4096,"stream":true}
                """.formatted(model, quote(prompt));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<InputStream> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(payload)) {
                        return true;
                    }
                    String delta = extractJsonString(payload, "\"content\":");
                    if (delta == null || delta.isEmpty()) {
                        delta = extractJsonString(payload, "\"reasoning_content\":");
                    }
                    if (delta == null || delta.isEmpty()) {
                        continue;
                    }
                    if (!onChunk.test(delta)) {
                        return false; // 消费方中止：try-with-resources 关掉响应流
                    }
                }
                return true;
            }
        } catch (IOException e) {
            throw new IllegalStateException("流式调用 LLM 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式调用 LLM 被中断", e);
        }
    }

    /** 从 JSON 中提取第一个 key 后面的字符串值（处理转义）。key 需带引号与冒号，如 "\"content\":"。 */
    private static String extractJsonString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = json.indexOf('"', idx + key.length());
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        // 反斜杠 u 四位十六进制转义（模型输出常见「大于号」的转义形式）：按码点解码
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
