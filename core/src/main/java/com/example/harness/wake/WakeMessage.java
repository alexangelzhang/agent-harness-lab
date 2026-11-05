package com.example.harness.wake;

/**
 * 一条叫醒消息：外部长任务到了终态，任务板把它拍进等待方的收件箱
 * （OmO「等待即订阅」研读落地，2026-11-05）。
 *
 * <p>等待方收到的不是「你再查一次」的许可，而是结果本身——
 * 等构建/部署不该烧 LLM 轮次去轮询，挂上订阅就去干别的，
 * 终态由基础设施推过来。</p>
 *
 * @param taskId 任务 id
 * @param taskName 任务名（给人看的）
 * @param reason 叫醒原因
 * @param detail 结果文本（COMPLETED 的产出 / FAILED 的错误 / TIMEOUT 的说明）
 */
public record WakeMessage(String taskId, String taskName, Reason reason, String detail) {

    /** 叫醒原因：三种终态各对应一种，先到先定，不重复叫醒。 */
    public enum Reason {
        /** 任务正常完成，detail 是结果。 */
        COMPLETED,
        /** 任务失败，detail 是错误——失败也叫醒，不许让等待方干等到天荒地老。 */
        FAILED,
        /** 超过挂牌时声明的期限仍RUNNING，兜底叫醒，detail 说明已等多久。 */
        TIMEOUT
    }

    /** 渲染成注入等待方会话的系统消息文本。 */
    public String toSystemMessage() {
        return "[系统通知] 任务 " + taskName + "（" + taskId + "）" + switch (reason) {
            case COMPLETED -> "已完成：" + detail;
            case FAILED -> "已失败：" + detail;
            case TIMEOUT -> "超时未完成：" + detail;
        };
    }
}
