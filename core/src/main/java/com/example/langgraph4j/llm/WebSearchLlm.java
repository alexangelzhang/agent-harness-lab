package com.example.langgraph4j.llm;

import com.example.harness.config.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端层联网搜索：把网关内置 {@code $web_search} 工具下沉到 LLM 客户端内部循环，
 * 对 {@link LlmClient} 的使用方（状态图节点）完全透明 —— 图上只有一个「会自己查资料的 LLM」。
 *
 * <p>为什么不用 langchain4j 发请求：网关侧 {@code $web_search} 内置工具要求以
 * {@code {"type":"builtin_function"}} 形式注册（Moonshot 系网关的私有扩展），langchain4j 的
 * ToolSpecification 只能序列化成 {@code type:"function"}，会被网关忽略 —— 所以这里像
 * {@link JdkHttpLlm} 一样手写 HTTP + JSON，请求体完全可控。</p>
 *
 * <p>协议（curl 实测验证）：</p>
 * <ol>
 *   <li>带 builtin_function tools 请求 → 模型返回 tool_call，arguments 里带服务端生成的
 *       {@code search_id}（finish_reason=tool_calls）；</li>
 *   <li>客户端追加 assistant 消息并为每个 tool_call 补一条<b>空结果</b> tool 消息
 *       —— 搜索结果由服务端凭 search_id 注入，客户端不真正执行搜索；
 *       <b>回传时 tool_calls 的 type 必须写 "function"</b>（OpenAI 标准），照抄响应里的
 *       "builtin_function" 会被网关拒（400 tokenization failed，2026-11 curl 实测）；</li>
 *   <li>再次请求 → 模型基于注入的搜索结果生成最终回答（偶发「正在搜索，请稍候」
 *       中间态，再要一轮即可）。</li>
 * </ol>
 *
 * <p>与图级工具（{@code WebSearchTool} 作为 Search 节点）的对比：客户端层实现最省事，
 * 但搜索发生在节点黑盒内——图上看不见、不能 interruptBefore 审批、结果不进 Checkpoint；
 * 图级工具调用是显式节点，可中断、可并行调度（LLMCompiler）、可复用缓存。</p>
 */
public final class WebSearchLlm implements LlmClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    private static final String[] INTERIM_MARKERS = {"正在搜索", "搜索中", "请稍候"};

    private final String base;
    private final String apiKey;
    private final String modelName;

    public WebSearchLlm(String base, String apiKey, String modelName) {
        this.base = base;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    /** 默认配置：环境变量经 {@link AgentConfig} 统一装配（LLM_API_KEY / LLM_BASE_URL / LLM_MODEL）。
     *  注意 $web_search 是网关侧内置工具（Moonshot 系网关的私有扩展），端点不支持时本类不可用。 */
    public static WebSearchLlm fromEnv() {
        AgentConfig config = AgentConfig.load();
        return new WebSearchLlm(config.chatBase(), config.requireApiKey(), config.model());
    }

    @Override
    public String complete(String prompt) {
        try {
            // 注意：初始串不开数组尾（]），每轮追加消息，发请求时再闭合 ——
            // 否则首条消息后就闭合数组，后续追加的消息挂在数组外面（HTTP 400 的元凶）
            String messagesJson = "[{\"role\":\"user\",\"content\":\"" + escape(prompt) + "\"}";
            for (int round = 1; round <= 4; round++) {
                String body = """
                        {"model":"%s","messages":%s,"tools":[{"type":"builtin_function","function":{"name":"$web_search"}}]}
                        """.formatted(modelName, messagesJson + "]").strip();
                String resp = post(base + "/chat/completions", body);

                String finish = field(resp, "finish_reason");
                String content = field(resp, "content");
                String toolCallId = find(resp, "\"id\"\\s*:\\s*\"(tool_[^\"]+)\"");
                if ("tool_calls".equals(finish) && toolCallId != null) {
                    String arguments = find(resp, "\"arguments\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                    String searchId = searchId(unescape(arguments));
                    if (searchId == null) {
                        throw new IllegalStateException("tool_call 中无 search_id: " + arguments);
                    }
                    // assistant 原样追加 + 空结果 tool 消息；arguments 用 search_id 重建（等价回传）。
                    // 注意回传时 tool_calls 的 type 必须写 "function"（OpenAI 标准）——
                    // 响应里网关给的是 "builtin_function"，但 2026-11 起网关对请求侧回显
                    // builtin_function 一律报 tokenization failed（curl 实测 D/E 对照定位）。
                    messagesJson += ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\""
                            + toolCallId + "\",\"type\":\"function\",\"function\":{\"name\":\"$web_search\",\"arguments\":\""
                            + escape("{\"search_result\":{\"search_id\":\"" + searchId + "\"}}") + "\"}}]}";
                    messagesJson += ",{\"role\":\"tool\",\"tool_call_id\":\"" + toolCallId
                            + "\",\"name\":\"$web_search\",\"content\":\"\"}";
                    continue;
                }
                if (content != null && !content.isBlank()) {
                    if (round < 4 && isInterim(content)) {
                        messagesJson += ",{\"role\":\"assistant\",\"content\":\"" + escape(content) + "\"}";
                        continue;
                    }
                    return content;
                }
                String reasoning = field(resp, "reasoning_content");
                return reasoning == null ? "" : reasoning;
            }
            throw new IllegalStateException("$web_search 4 轮内未拿到最终回答");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String searchId(String argumentsJson) {
        return argumentsJson == null ? null : find(argumentsJson, "\"search_id\"\\s*:\\s*\"([^\"]+)\"");
    }

    private static boolean isInterim(String text) {
        for (String marker : INTERIM_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ---- 手写 JSON 小工具（JdkHttpLlm 同款风格；长文本字段手工扫描，不用星号正则——
    //      图执行器线程栈小，(...)* 匹配上万字符的 reasoning_content 会递归爆栈） ----

    /** 提取 JSON 字符串字段（值可能很长，如 content / reasoning_content）。 */
    private static String field(String json, String name) {
        String key = "\"" + name + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(key, from);
            if (k < 0) {
                return null;
            }
            int i = k + key.length();
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':'
                    || json.charAt(i) == '\n' || json.charAt(i) == '\t')) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                from = k + 1; // 同名键但非字符串值（如 "finish_reason": null 的其他字段）
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (i = i + 1; i < json.length(); ) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(i + 1);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                            i += 4;
                        }
                        default -> sb.append(n); // \" \\ \/
                    }
                    i += 2;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return null;
        }
    }

    private static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
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
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> sb.append(n); // \" \\ \/
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
