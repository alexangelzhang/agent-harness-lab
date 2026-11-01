package com.example.agent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容接口的真实 LLM 客户端。
 * 通过环境变量 OPENAI_API_KEY / OPENAI_BASE_URL（默认 https://api.openai.com/v1）
 * / OPENAI_MODEL（默认 gpt-4o-mini）配置。
 * 使用 JDK 自带 HttpClient + 手工 JSON 拼接，避免引入第三方依赖。
 */
public class OpenAiCompatibleLlm implements LlmClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public OpenAiCompatibleLlm() {
        this(System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"),
                System.getenv("OPENAI_API_KEY"),
                System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));
    }

    public OpenAiCompatibleLlm(String baseUrl, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少 OPENAI_API_KEY 环境变量");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        String body = """
                {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0}
                """.formatted(model, quote(prompt));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            String resp = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            // 极简 JSON 解析：定位最后一个 "content":"..." 字段
            int idx = resp.lastIndexOf("\"content\":");
            if (idx < 0) {
                throw new IllegalStateException("LLM 响应异常：" + resp);
            }
            int start = resp.indexOf('"', resp.indexOf(':', idx) + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = start + 1; i < resp.length(); i++) {
                char c = resp.charAt(i);
                if (c == '\\') {
                    sb.append(resp.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("调用 LLM 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 LLM 被中断", e);
        }
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
