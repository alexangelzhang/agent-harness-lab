package com.example.harness.routing;

import com.example.langgraph4j.llm.LlmClient;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 模型路由：不是每个决定都需要前沿模型（文章 #9）。
 *
 * <p>按任务信号分三档：高风险/多步推理走前沿模型，简单查表走快模型，
 * 其余走均衡档。规则是确定性的、可解释的——路由错了能查是哪条规则命中的；
 * 生产里这一层可以换成小分类器，接口形状不变。</p>
 */
public final class ModelRouter {

    /** 模型档位。 */
    public enum Tier { FAST, BALANCED, FRONTIER }

    /** 一次路由的决定：档位 + 命中的规则名（可解释性）。 */
    public record Decision(Tier tier, String rule) {
    }

    private static final List<String> HIGH_RISK = List.of(
            "删除", "生产", "部署", "退款", "转账", "root", "密钥");
    private static final List<String> SIMPLE = List.of(
            "查询", "查一下", "多少", "列表", "状态", "是谁", "什么时候");

    private final Map<Tier, LlmClient> models = new EnumMap<>(Tier.class);

    public ModelRouter register(Tier tier, LlmClient client) {
        models.put(tier, client);
        return this;
    }

    /** 路由规则：先判高风险，再判简单查询，兜底均衡。 */
    public Decision decide(String task) {
        for (String kw : HIGH_RISK) {
            if (task.contains(kw)) {
                return new Decision(Tier.FRONTIER, "含高风险信号『" + kw + "』");
            }
        }
        boolean simple = task.length() <= 30
                && SIMPLE.stream().anyMatch(task::contains);
        if (simple) {
            return new Decision(Tier.FAST, "短句 + 查询类信号");
        }
        return new Decision(Tier.BALANCED, "默认档");
    }

    /** 挑模型：按 {@link #decide} 的档位取已注册的客户端。 */
    public LlmClient pick(String task) {
        Decision d = decide(task);
        LlmClient client = models.get(d.tier());
        if (client == null) {
            throw new IllegalStateException("档位 " + d.tier() + " 未注册模型");
        }
        return client;
    }
}
