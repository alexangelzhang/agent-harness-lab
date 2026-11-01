package com.example.harness;

import java.util.Map;

/**
 * 审批策略：动作按风险分级，决定执行前要不要等人工。
 *
 * <p>只有「哪些动作要停下来等人批」这层是策略——它和 checkpoint/interrupt
 * 机制解耦：策略做路由决策（AUTO 直通 / NEEDS_APPROVAL 进挂起节点 /
 * FORBIDDEN 直接终态），执行载体（图的条件边、loop 里的前置检查）各自接线。
 * 策略表应当像代码一样被评审和版本化，而不是散在提示词里指望模型自觉。</p>
 *
 * <p>与 {@link ToolGate} 的分工：策略回答「这个动作要不要人批」，
 * 网关回答「这个人够不够格」——审批通过后的执行仍可以过网关（防越权双保险），
 * 审批本身则是对单次动作的一事一议授权。</p>
 */
public final class ApprovalPolicy {

    /** 动作风险分级：AUTO 直接执行；NEEDS_APPROVAL 挂起等人工；FORBIDDEN 谁都不批，直接拒。 */
    public enum Risk { AUTO, NEEDS_APPROVAL, FORBIDDEN }

    private final Map<String, Risk> actions;
    private final Risk defaultRisk;

    private ApprovalPolicy(Map<String, Risk> actions, Risk defaultRisk) {
        this.actions = Map.copyOf(actions);
        this.defaultRisk = defaultRisk;
    }

    /** 声明式建策略：动作名 → 风险级，外加未声明动作的默认级。 */
    public static ApprovalPolicy of(Map<String, Risk> actions, Risk defaultRisk) {
        return new ApprovalPolicy(actions, defaultRisk);
    }

    /** 保守默认：不认识的律动作一律要审批——生产环境宁挂起不错放。 */
    public static ApprovalPolicy cautious(Map<String, Risk> known) {
        return new ApprovalPolicy(known, Risk.NEEDS_APPROVAL);
    }

    public Risk of(String action) {
        return actions.getOrDefault(action, defaultRisk);
    }

    public boolean needsApproval(String action) {
        return of(action) == Risk.NEEDS_APPROVAL;
    }

    public boolean forbidden(String action) {
        return of(action) == Risk.FORBIDDEN;
    }
}
