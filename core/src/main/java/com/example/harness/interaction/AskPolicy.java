package com.example.harness.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 提问策略：agent 想动作之前，先过一遍「该不该问人」（教程第 8 章「策略拦截」）。
 *
 * <p>三种裁决：</p>
 * <ul>
 *   <li>{@code AUTO}——动作 routine（只读、可逆、有兜底），不问，直接干；</li>
 *   <li>{@code ASK}——动作有代价或有歧义，发 {@link StructuredQuestion} 给人；</li>
 *   <li>{@code FORBID}——动作越红线，不问也不干，直接拒。</li>
 * </ul>
 *
 * <p>规则按声明顺序匹配，先中先生；不认识的动作默认 ASK（cautious，
 * 宁多问不错放——同 ApprovalPolicy 的默认值取向）。规则表像代码一样
 * 可评审、可版本化。</p>
 */
public final class AskPolicy {

    /** 裁决：AUTO 直通 / ASK 问人 / FORBID 拒。 */
    public enum Verdict {AUTO, ASK, FORBID}

    /** 一次裁决的结果：结论 + 命中规则 + 要发的题（ASK 时非空）。 */
    public record Decision(Verdict verdict, String reason, StructuredQuestion question) {
    }

    private record Rule(String name, Predicate<String> when, Verdict verdict,
                        StructuredQuestion question) {
    }

    private final List<Rule> rules = new ArrayList<>();

    /** 加一条规则：动作描述命中 {@code when} 即按 {@code verdict} 处理。 */
    public AskPolicy rule(String name, Predicate<String> when, Verdict verdict) {
        return rule(name, when, verdict, null);
    }

    /** 加一条 ASK 规则并附带要发的题。 */
    public AskPolicy rule(String name, Predicate<String> when, Verdict verdict,
                          StructuredQuestion question) {
        rules.add(new Rule(name, when, verdict, question));
        return this;
    }

    /** 按顺序匹配；默认 ASK 且不带题——调用方应补题或视作需人工补问卷。 */
    public Decision decide(String action) {
        for (Rule r : rules) {
            if (r.when().test(action)) {
                return new Decision(r.verdict(), "命中规则：" + r.name(), r.question());
            }
        }
        return new Decision(Verdict.ASK, "没有规则认识这个动作，默认问人（cautious）", null);
    }
}
