package com.example.harness.sensor;

import java.util.ArrayList;
import java.util.List;

/**
 * 架构适应度挂点（Fowler 第二类调节：architecture fitness harness）：
 * 把「性能红线 / 可观测性约定 / API 契约」这类架构级要求<b>前馈为可执行
 * 检查</b>——要求不再是文档里的愿望，是每次变更都会跑的判断。
 *
 * <p>每条 fitness 规则 = 一句人话描述 + 产出里必须出现的关键词
 * （占位实现；生产换成真跑性能测试/契约测试，接口不变）。规则缺失
 * 关键词 → BLOCK 级 finding，修正指引就是规则本身。</p>
 */
public final class FitnessSensor implements Sensor {

    /** 一条适应度要求：描述 + 合规产出的必备特征（关键词占位）。 */
    public record FitnessRule(String description, String mustContain) {
    }

    private final String ruleName;
    private final List<FitnessRule> rules;

    /** 预置一组典型的架构适应度要求（性能/可观测性/契约）。 */
    public static FitnessSensor standard() {
        return new FitnessSensor("架构适应度", List.of(
                new FitnessRule("对外接口必须声明超时（性能红线：不许无限等）",
                        "timeout"),
                new FitnessRule("对外接口必须打 requestId 日志（可观测性约定）",
                        "requestId"),
                new FitnessRule("对外接口必须做参数校验（API 契约）",
                        "validate")));
    }

    public FitnessSensor(String ruleName, List<FitnessRule> rules) {
        this.ruleName = ruleName;
        this.rules = List.copyOf(rules);
    }

    @Override
    public String name() {
        return ruleName;
    }

    @Override
    public Kind kind() {
        return Kind.COMPUTATIONAL;
    }

    @Override
    public List<Finding> check(CodeChange change) {
        List<Finding> out = new ArrayList<>();
        String content = change.content();
        for (FitnessRule rule : rules) {
            if (!content.contains(rule.mustContain())) {
                out.add(Finding.block(ruleName, change.fileName(),
                        "适应度要求未满足：" + rule.description(),
                        rule.description() + "——这条要求已经前馈成检查了，"
                                + "这次产出不满足就得补，绕不过去。"));
            }
        }
        return out;
    }
}
