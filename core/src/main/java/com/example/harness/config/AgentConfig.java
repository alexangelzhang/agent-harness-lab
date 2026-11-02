package com.example.harness.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * 统一配置：全部环境变量直读收敛到这一个入口，带默认值和启动校验。
 *
 * <p>环境变量（均有默认值，只有 LLM_API_KEY 是真实模式才需要）：</p>
 * <ul>
 *   <li>{@code LLM_API_KEY} —— 真实模型鉴权，缺了真实模式明确报错（{@link #requireApiKey()}）；</li>
 *   <li>{@code LLM_BASE_URL} —— OpenAI 兼容端点，默认 {@value #DEFAULT_CHAT_BASE}；</li>
 *   <li>{@code LLM_MODEL} —— 模型名，默认 {@value #DEFAULT_MODEL}；</li>
 *   <li>{@code AGENT_RUNS_DIR} —— 运行档案目录，默认 {@code runs}；</li>
 *   <li>{@code AGENT_RULES_DIR} —— 规则草稿目录，默认 {@code rules}。</li>
 * </ul>
 *
 * <p>旧名 {@code KIMI_API_KEY} / {@code KIMI_CHAT_BASE} / {@code KIMI_MODEL}
 * 仍兼容（新名为空时回退），新部署请用 {@code LLM_*}。默认端点与模型名只是
 * 作者自用的缺省，换成任意 OpenAI 兼容端点改三个变量即可，不用动代码。</p>
 *
 * <p>{@link #load()} 读真实环境；{@link #from(Map)} 供注入与测试——
 * 配置只有这一个装配点，新增环境变量不许再散落直读。</p>
 */
public record AgentConfig(String apiKey, String chatBase, String model,
                          Path runsDir, Path rulesDir) {

    public static final String DEFAULT_CHAT_BASE = "https://agent-gw.kimi.com/coding/v1";
    public static final String DEFAULT_MODEL = "kimi-k2-0905-preview";

    /** 从真实环境变量装配（生产/场景入口用这个）。 */
    public static AgentConfig load() {
        return from(System.getenv());
    }

    /** 从任意映射装配（测试/嵌入式场景注入用）。 */
    public static AgentConfig from(Map<String, String> env) {
        return new AgentConfig(
                firstNonBlank(env, "LLM_API_KEY", "KIMI_API_KEY"),
                firstNonBlank(env, "LLM_BASE_URL", "KIMI_CHAT_BASE", DEFAULT_CHAT_BASE),
                firstNonBlank(env, "LLM_MODEL", "KIMI_MODEL", DEFAULT_MODEL),
                Path.of(env.getOrDefault("AGENT_RUNS_DIR", "runs")),
                Path.of(env.getOrDefault("AGENT_RULES_DIR", "rules")));
    }

    /** 真实模式的硬门槛：缺 key 直接说清楚，不许烧到调用才发现。 */
    public String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少 LLM_API_KEY 环境变量（旧名 KIMI_API_KEY 仍兼容），"
                            + "真实模型模式无法运行（剧本模式不需要）");
        }
        return apiKey;
    }

    /** 按顺序取第一个非空值；都没有则返回 fallback（可为 null）。 */
    private static String firstNonBlank(Map<String, String> env, String primary,
                                        String legacy, String... fallback) {
        String value = env.get(primary);
        if (value == null || value.isBlank()) {
            value = env.get(legacy);
        }
        if (value == null || value.isBlank()) {
            value = fallback.length > 0 ? fallback[0] : null;
        }
        return value;
    }
}
