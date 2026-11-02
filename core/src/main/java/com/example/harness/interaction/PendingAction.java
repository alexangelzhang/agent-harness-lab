package com.example.harness.interaction;

import java.util.Map;
import java.util.TreeMap;

/**
 * 待处理操作：Harness 在策略关强制挂起的结构化载荷（文章 #8）。
 *
 * <p>高危操作到达策略引擎时，Harness 不管模型打算什么都直接暂停任务、
 * 返回 pending 状态——<b>是否审批由系统决定，不由模型决定</b>。
 * 与模型的 {@code ask_human}（缺信息提问）是两个层级：一个是策略，
 * 一个是补信息。这个 record 就是「执行协议」的载体：交互界面
 * 把它渲染成批准/拒绝按钮（Slack 按钮、Teams 卡片、语音「确认」），
 * 操作和状态相同，只是呈现不同。</p>
 *
 * @param id       操作标识（resume 时回传）
 * @param action   拟执行的动作
 * @param args     动作参数（凭据以 ${SECRET:xxx} 占位，见 harness.credentials）
 * @param risk     风险级
 * @param reason   为什么被拦（哪条策略）
 * @param askedAtMs 挂起时间
 */
public record PendingAction(String id, String action, Map<String, String> args,
                            String risk, String reason, long askedAtMs) {

    /** 渲染载荷：界面agnostic的按钮描述（场景 打印 + WebDashboard 用）。 */
    public String buttonPayload() {
        StringBuilder sb = new StringBuilder("待审批 #" + id + " [" + risk + "] " + action);
        Map<String, String> sorted = new TreeMap<>(args);
        sorted.forEach((k, v) -> sb.append("\n    ").append(k).append(" = ").append(v));
        sb.append("\n    依据策略：").append(reason);
        sb.append("\n    按钮：[批准] [拒绝]");
        return sb.toString();
    }
}
