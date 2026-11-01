package com.example.harness.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * 统一配置：全部环境变量直读收敛到这一个入口，带默认值和启动校验。
 *
 * <p>环境变量（均有默认值，只有 KIMI_API_KEY 是真实模式才需要）：</p>
 * <ul>
 *   <li>{@code KIMI_API_KEY} —— 真实模型鉴权，缺了真实模式明确报错（{@link #requireApiKey()}）；</li>
 *   <li>{@code KIMI_CHAT_BASE} —— OpenAI 兼容端点，默认 {@value #DEFAULT_CHAT_BASE}；</li>
 *   <li>{@code KIMI_MODEL} —— 模型名，默认 {@value #DEFAULT_MODEL}；</li>
 *   <li>{@code AGENT_RUNS_DIR} —— 运行档案目录，默认 {@code runs}；</li>
 *   <li>{@code AGENT_RULES_DIR} —— 规则草稿目录，默认 {@code rules}。</li>
 * </ul>
 *
 * <p>{@link #load()} 读真实环境；{@link #from(Map)} 供注入与测试——
 * 配置只有这一个装配点，新增环境变量不许再散落直读。</p>
 */
public record AgentConfig(String apiKey, String chatBase, String model,
                          Path runsDir, Path rulesDir) {

    public static final String DEFAULT_CHAT_BASE = "https://agent-gw.kimi.com/coding/v1";
    public static final String DEFAULT_MODEL = "kimi-k2-0905-preview";

    /** 从真实环境变量装配（生产/demo 入口用这个）。 */
    public static AgentConfig load() {
        return from(System.getenv());
    }

    /** 从任意映射装配（测试/嵌入式场景注入用）。 */
    public static AgentConfig from(Map<String, String> env) {
        return new AgentConfig(
                env.get("KIMI_API_KEY"),
                env.getOrDefault("KIMI_CHAT_BASE", DEFAULT_CHAT_BASE),
                env.getOrDefault("KIMI_MODEL", DEFAULT_MODEL),
                Path.of(env.getOrDefault("AGENT_RUNS_DIR", "runs")),
                Path.of(env.getOrDefault("AGENT_RULES_DIR", "rules")));
    }

    /** 真实模式的硬门槛：缺 key 直接说清楚，不许烧到调用才发现。 */
    public String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少 KIMI_API_KEY 环境变量，真实模型模式无法运行（剧本模式不需要）");
        }
        return apiKey;
    }
}
