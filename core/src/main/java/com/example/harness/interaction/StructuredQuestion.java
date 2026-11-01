package com.example.harness.interaction;

import java.util.List;

/**
 * 结构化提问：agent 需要人输入时，不问开放问题，发一道带选项的题（教程第 8 章）。
 *
 * <p>开放问答的痛点：人得自己想该答什么，agent 拿到的东西五花八门没法校验。
 * 结构化提问把「可能的回答」也设计成界面的一部分：选项带说明、可指定默认项、
 * 支持多选。渲染成什么样（CLI 列表、按钮卡片）是 {@code Interviewer} 的事，
 * 问题的结构与此无关。</p>
 *
 * @param id          题目标识（审计、剧本答题按它定位）
 * @param question    问题本身
 * @param options     选项（label 短、description 说清代价）
 * @param defaultLabel 人不答时采用的默认项 label
 * @param multiSelect 是否多选
 */
public record StructuredQuestion(String id, String question,
                                 List<Option> options, String defaultLabel,
                                 boolean multiSelect) {

    /**
     * @param label       选项短名
     * @param description 把这个选项的代价/后果说清——人做选择靠它，不靠脑补
     */
    public record Option(String label, String description) {
    }

    /** 渲染成给人看的多行文本（CLI 直接打印，Web 可换皮）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("❓ ").append(question);
        if (multiSelect) {
            sb.append("（可多选）");
        }
        sb.append('\n');
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            sb.append("   ").append(i + 1).append(". ").append(o.label());
            if (o.label().equals(defaultLabel)) {
                sb.append("（默认）");
            }
            sb.append(" — ").append(o.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 校验并取选项：剧本或 UI 层回的答案必须是选项之一。 */
    public Option resolve(String answer) {
        return options.stream()
                .filter(o -> o.label().equals(answer))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "答案 '" + answer + "' 不在选项里，拒绝猜测——结构化提问不允许自由发挥"));
    }
}
