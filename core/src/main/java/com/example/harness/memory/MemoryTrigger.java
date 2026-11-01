package com.example.harness.memory;

import java.util.List;

/**
 * 记忆触发器：决定该查不该查，不过度检索（智能体AI 文章模块 #2）。
 *
 * <p>「继续上次的分析」「按我之前的偏好」——召回信号；「1+1 等于几」
 * 「把 hello 翻译成法语」——只依赖当前输入，检索是纯浪费（延迟 + token +
 * 还可能带进无关历史）。触发器在两个方向上都克制：该查不查是失职，
 * 不该查查了是负担。规则按序匹配，生产可换小分类器，接口不变。</p>
 */
public final class MemoryTrigger {

    /** 触发裁决。 */
    public record Decision(boolean recall, String rule) {
    }

    private static final List<String> RECALL_SIGNALS = List.of(
            "继续", "上次", "之前", "先前", "按我", "照之前", "之前确定",
            "原方案", "老方案", "进行到哪");
    private static final List<String> SELF_CONTAINED_SIGNALS = List.of(
            "等于几", "是多少", "算一下", "翻译", "近义词", "写一句");

    /** 按序匹配：先召回信号，再自足信号，最后按任务长度兜底。 */
    public Decision decide(String task) {
        for (String s : RECALL_SIGNALS) {
            if (task.contains(s)) {
                return new Decision(true, "召回信号『" + s + "』");
            }
        }
        boolean selfContained = task.length() <= 30
                && SELF_CONTAINED_SIGNALS.stream().anyMatch(task::contains);
        if (selfContained) {
            return new Decision(false, "自足信号，只依赖当前输入");
        }
        return task.length() <= 15
                ? new Decision(false, "短句且无任何召回信号，默认跳过")
                : new Decision(true, "任务类默认召回（低频信号，低代价）");
    }
}
