package com.example.langgraph4j.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven Central 查询工具（免费、无需 API Key）：查 artifact 的最新发布版本。
 *
 * <p>直接读 {@code maven-metadata.xml} 的 {@code <latest>} 标签（回退到最后一个
 * {@code <version>}）。参数格式 {@code groupId:artifactId}（容忍多余空白和版本号段），
 * 与 REWOO 计划里的 {@code Maven[org.bsc.langgraph4j:langgraph4j-core]} 对应。</p>
 */
public final class MavenTool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    private MavenTool() {
    }

    /** 供状态图工具表直接引用的 {@code Function<String, String>}。 */
    public static String latestVersion(String gav) {
        try {
            String[] parts = gav.trim().split(":");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return "参数格式应为 groupId:artifactId，收到：" + gav;
            }
            String groupPath = parts[0].trim().replace('.', '/');
            String url = CENTRAL + groupPath + "/" + parts[1].trim() + "/maven-metadata.xml";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "agent-design-patterns/1.0 (educational)")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "Maven Central 未找到 " + parts[0].trim() + ":" + parts[1].trim()
                        + "（HTTP " + response.statusCode() + "）";
            }
            String body = response.body();
            String latest = xmlField(body, "latest");
            if (latest == null) {
                // <version> 有多个，取最后一个
                Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(body);
                while (m.find()) {
                    latest = m.group(1);
                }
            }
            return latest == null
                    ? "maven-metadata.xml 中没有版本信息"
                    : parts[0].trim() + ":" + parts[1].trim() + " 最新版本是 " + latest + "（Maven Central）。";
        } catch (Exception e) {
            return "查询失败（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    private static String xmlField(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
