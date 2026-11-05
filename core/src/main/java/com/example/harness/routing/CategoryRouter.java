package com.example.harness.routing;

import com.example.agent.llm.LlmClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Category 路由 + provider fallback 链（OmO 研读落地，
 * 见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>与 {@link ModelRouter} 的分工：ModelRouter 按任务信号分档
 * （FAST/BALANCED/FRONTIER），回答「这活该花多少钱」；本类回答
 * 「这活是什么工种、按什么顺序找模型」——委派时写意图
 * （quick / deep / visual-engineering……）不写模型名，每个 category
 * 挂一条 provider 优先级链：<b>选档时</b>跳过没连接的 provider，
 * <b>运行中</b>当前档调用炸了就降级到下一档重试，活照干。
 * 链耗尽 fail-closed，报错带上整条链的尝试史，不悄悄换家族。</p>
 *
 * <p>每档还带一个 prompt 预设（{@link PromptStyle}）：Claude 家族吃
 * 机制驱动的长指令，GPT 家族吃原则驱动的短指令——换模型不换
 * prompt 策略是 OmO 点名的浪费，这里把预设钉在链上，选到哪档
 * 就用哪档的说法。</p>
 */
public final class CategoryRouter {

    /** prompt 预设：机制驱动（长而具体的操作指令）/ 原则驱动（给目标不给步骤）。 */
    public enum PromptStyle { MECHANISTIC, PRINCIPLED }

    /** 链上的一档：provider/模型 + 推理强度 + prompt 预设。 */
    public record Rung(String provider, String model, String reasoning,
                       PromptStyle style) {

        public static Rung of(String provider, String model, PromptStyle style) {
            return new Rung(provider, model, "medium", style);
        }

        /** 展示名：provider/model。 */
        public String label() {
            return provider + "/" + model;
        }
    }

    /** 一次执行的报告：最终命中的档 + 各档尝试史（可审计）。 */
    public record RunReport(Rung rung, String output, List<String> attempts) {
    }

    /** 链耗尽：每一档都试过都炸了。 */
    public static final class ChainExhaustedException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public ChainExhaustedException(String message) {
            super(message);
        }
    }

    private final Map<String, List<Rung>> categories = new LinkedHashMap<>();

    /** 注册一个 category 的 fallback 链（顺序即优先级）。 */
    public CategoryRouter register(String category, List<Rung> chain) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("category " + category + " 的链不能为空");
        }
        categories.put(category, List.copyOf(chain));
        return this;
    }

    /**
     * 选档：链上第一个 provider 已连接的档。全都连不上返回 null
     * （由调用方决定报错还是换 category——不静默跨 category 路由）。
     */
    public Rung resolve(String category, Set<String> connectedProviders) {
        List<Rung> chain = chainOf(category);
        return chain.stream()
                .filter(r -> connectedProviders.contains(r.provider()))
                .findFirst().orElse(null);
    }

    /**
     * 带 fallback 的执行：从当前已连接的最高档开始调，炸了就降级
     * 重试，每一跳记进尝试史。链耗尽抛 {@link ChainExhaustedException}。
     *
     * @param clientFor 按档取模型客户端（档位 → LlmClient）
     */
    public RunReport execute(String category, Set<String> connectedProviders,
                             String prompt, Function<Rung, LlmClient> clientFor) {
        List<Rung> chain = chainOf(category);
        List<String> attempts = new ArrayList<>();
        for (Rung rung : chain) {
            if (!connectedProviders.contains(rung.provider())) {
                attempts.add(rung.label() + "：provider 未连接，跳过");
                continue;
            }
            try {
                String out = clientFor.apply(rung).complete(prompt);
                attempts.add(rung.label() + "：成功");
                return new RunReport(rung, out, List.copyOf(attempts));
            } catch (RuntimeException e) {
                attempts.add(rung.label() + "：调用失败（" + e.getMessage() + "），降级");
            }
        }
        throw new ChainExhaustedException("category " + category
                + " 的链耗尽：" + String.join(" → ", attempts));
    }

    /** 某 category 当前应使用的 prompt 预设（跟着选中的档走）。 */
    public PromptStyle promptStyle(String category, Set<String> connectedProviders) {
        Rung r = resolve(category, connectedProviders);
        if (r == null) {
            throw new ChainExhaustedException("category " + category
                    + " 没有已连接的档可取 prompt 预设");
        }
        return r.style();
    }

    private List<Rung> chainOf(String category) {
        List<Rung> chain = categories.get(category);
        if (chain == null) {
            throw new IllegalArgumentException("未注册的 category：" + category
                    + "（已注册：" + categories.keySet() + "）");
        }
        return chain;
    }
}
