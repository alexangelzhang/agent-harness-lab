package com.example.langgraph4j.tool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实联网搜索工具（免费、无需 API Key）：中文维基百科 opensearch + 页面摘要。
 *
 * <p>两步走：</p>
 * <ol>
 *   <li>{@code w/api.php?action=opensearch} 按关键词找最相关条目；</li>
 *   <li>{@code api/rest_v1/page/summary} 取条目摘要（描述 + 正文开头约 500 字），
 *       交给 LLM 自己从文本中提取事实。</li>
 * </ol>
 *
 * <p>刻意只返回原始摘要文本而不是「结构化答案」——Search 工具的职责是取回资料，
 * 理解与计算都留给 ReAct 循环里的后续轮次，这正是模式演示要展示的分工。</p>
 */
public final class WebSearchTool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private WebSearchTool() {
    }

    /** 供状态图工具表直接引用的 {@code Function<String, String>}。 */
    public static String search(String query) {
        try {
            String title = resolveTitle(query);
            if (title == null) {
                return "维基百科未找到与「" + query + "」相关的条目。";
            }
            String summaryJson = get("https://zh.wikipedia.org/api/rest_v1/page/summary/"
                    + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8));

            String description = jsonField(summaryJson, "description");
            String extract = jsonField(summaryJson, "extract");
            StringBuilder sb = new StringBuilder("维基百科条目「").append(title).append("」");
            // 问「成立/年份/日期」类问题时，REST 摘要往往不含答案 —— 再到全文开头定位关键句。
            // 关键事实放在最前面：下游 solver 截断证据时也能保住答案
            String fact = null;
            if (query.matches(".*(成立|创立|创建|年份|哪年|日期|什么时候).*")) {
                fact = findFactSentence(title, "创立|成立|創立|上市");
            }
            if (fact != null) {
                sb.append("【关键事实】").append(fact).append(" ");
            }
            sb.append("：");
            if (description != null) {
                sb.append(description).append("。");
            }
            if (extract != null) {
                sb.append(extract, 0, Math.min(extract.length(), 300));
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    /** 取条目全文导言（plain text），找第一句同时含关键词与四位年份的句子。
     *  按句切分后逐句匹配 —— 不用一条大正则，避免灾难性回溯。 */
    private static String findFactSentence(String title, String keywords) throws Exception {
        String json = get("https://zh.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext&format=json&titles="
                + URLEncoder.encode(title, StandardCharsets.UTF_8));
        String text = jsonField(json, "extract");
        if (text == null) {
            return null;
        }
        Pattern keyword = Pattern.compile("(?:" + keywords + ")");
        Pattern year = Pattern.compile("(19|20)\\d{2}");
        for (String sentence : text.split("[。\\n]")) {
            String s = sentence.strip();
            if (!s.isEmpty() && keyword.matcher(s).find() && year.matcher(s).find()) {
                return s + "。";
            }
        }
        return null;
    }

    /** opensearch 返回 {@code ["query",["标题"],["描述"],["url"]]}，取第二个数组的第一个标题。 */
    private static String resolveTitle(String query) throws Exception {
        // 模型给的查询常带疑问词（「Google 成立于哪一年」），opensearch 全文检索对这类
        // 长查询命中率低——逐级降级：原查询 -> 去疑问词 -> 纯字母数字词（如 Google）
        for (String candidate : queryCandidates(query)) {
            String json = get("https://zh.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1&search="
                    + URLEncoder.encode(candidate, StandardCharsets.UTF_8));
            // 响应固定为 ["query",["标题"],["描述"],["url"]]：取第二个数组的第一个字符串
            int firstComma = json.indexOf(',');
            int innerOpen = firstComma < 0 ? -1 : json.indexOf('[', firstComma);
            if (innerOpen >= 0) {
                String title = readJsonString(json, json.indexOf('"', innerOpen));
                if (title != null && !title.isEmpty()) {
                    return title;
                }
            }
        }
        return null;
    }

    /** 从 {@code quoteIndex} 处的开引号开始读一个 JSON 字符串（含转义），返回解码后的值。 */
    private static String readJsonString(String json, int quoteIndex) {
        if (quoteIndex < 0 || quoteIndex >= json.length() || json.charAt(quoteIndex) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = quoteIndex + 1; i < json.length(); ) {
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

    /** 查询降级序列：含拉丁字母专名（Google/SpaceX/langgraph4j）时专名优先 ——
     *  opensearch 对「专名 + 中文疑问词」的全文检索容易跑偏（如命中 Google Chrome）；
     *  纯中文查询保持原样优先。 */
    private static java.util.List<String> queryCandidates(String query) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        Matcher latin = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+-]*").matcher(query);
        if (latin.find() && latin.group().length() >= 4) {
            candidates.add(latin.group());
        }
        candidates.add(query);
        String stripped = query
                .replaceAll("维基百科|请问|什么|哪个|哪一年|多少年|多少|记载|成立|时间|日期|距今|已有|年$", "")
                .replaceAll("[？?，,。\\s]+", " ")
                .trim();
        if (!stripped.isEmpty() && !stripped.equals(query)) {
            candidates.add(stripped);
        }
        return candidates;
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "agent-design-patterns/1.0 (educational)")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** 手写 JSON 单字段提取（与项目零依赖风格一致）。
     *  不用正则星号匹配 —— 长文本值会让 {@code (...)*} 递归爆栈（StackOverflowError）；
     *  改为手工扫描：定位键、跳过冒号、处理转义读到闭引号。 */
    private static String jsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int pos = 0;
        while (true) {
            int k = json.indexOf(key, pos);
            if (k < 0) {
                return null;
            }
            int i = k + key.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != ':') {
                pos = k + 1; // 如 "extract" 是 "extracts" 的前缀 —— 跳过继续找
                continue;
            }
            i++;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                pos = k + 1;
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
}
