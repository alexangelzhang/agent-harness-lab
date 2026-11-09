package com.example.harness.memory;

/**
 * 长期记忆写入闸门：只留无法从环境重新观测、且能活到下次维护的残差。
 * 当前金币、页面开关这类状态应在使用时重新读取，不该驻留成长期事实。
 */
public final class ResidualMemoryGate {

    /** 一次准入决定，拒绝原因直接给整理流程留痕。 */
    public record Decision(boolean accepted, String reason) {
    }

    private final long maintenanceIntervalMs;

    public ResidualMemoryGate(long maintenanceIntervalMs) {
        this.maintenanceIntervalMs = maintenanceIntervalMs;
    }

    /** 可重观测状态先拒绝；其余候选的半衰期至少要覆盖一个维护周期。 */
    public Decision evaluate(boolean reobservable, long halfLifeMs) {
        if (reobservable) {
            return new Decision(false, "可从环境重新观测的当前状态不进入长期记忆");
        }
        if (halfLifeMs < maintenanceIntervalMs) {
            return new Decision(false, "半衰期短于维护周期，保留到下次维护前就会失效");
        }
        return new Decision(true, "不可重新观测的残差，半衰期覆盖维护周期");
    }
}
