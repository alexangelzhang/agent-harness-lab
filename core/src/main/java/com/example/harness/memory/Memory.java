package com.example.harness.memory;

import java.util.List;

/**
 * 一条记忆：值得留下的那部分对话，不是对话本身。
 *
 * <p>记忆带<b>作用域、来源、所有者、置信度、状态、有效期、权限</b>
 * （智能体AI 记忆文章的数据结构 checklist）。检索默认只出 ACTIVE 且
 * 没过期的——被替代的、过期的留在库里备查（审计/回滚），但不干扰
 * 当前任务。与 {@code harness.context} 的分工：context 管「这一窗怎么
 * 省地方」，memory 管「哪些信息值得留到以后」。</p>
 *
 * @param id            唯一标识
 * @param scope         作用域：用户偏好 / 工作区规范 / 项目事实
 * @param owner         所有者（谁说了算、谁来改）
 * @param content       整理后的事实表述（一句话，不是聊天记录）
 * @param source        来源（哪次对话、哪份文档）
 * @param createdAtMs   写入时间
 * @param tags          检索用的关键词
 * @param confidence    置信度 0~1（1.0=用户明确确认，0.5 以下=模型推断待验证）
 * @param status        生命周期状态：有效 / 待确认 / 已被替代 / 已过期——
 *                      UNCONFIRMED 是写入闸门：低置信（模型推断而非用户确认）
 *                      的新记忆先挂起，检索默认不出，确认后转 ACTIVE
 * @param validUntilMs  有效期截止（0 = 长期有效）；过了就视为过期
 * @param permissions   读取权限白名单（空 = 不限制）；非空时只有列出的角色可读
 */
public record Memory(String id, Scope scope, String owner, String content,
                     String source, long createdAtMs, List<String> tags,
                     double confidence, Status status, long validUntilMs,
                     List<String> permissions) {

    /** 记忆作用域，生命周期和可见范围各不同。 */
    public enum Scope { USER, WORKSPACE, PROJECT }

    /** 生命周期状态（智能体AI 文章：写入不是只有新增，状态会流转）。 */
    public enum Status { ACTIVE, UNCONFIRMED, SUPERSEDED, EXPIRED }

    /** 简版构造（演示默认值）：置信 0.8、有效、长期、不限权限。 */
    public static Memory of(String id, Scope scope, String owner, String content,
                            String source, long createdAtMs, List<String> tags) {
        return new Memory(id, scope, owner, content, source, createdAtMs, tags,
                0.8, Status.ACTIVE, 0, List.of());
    }

    /** 检索打分用的文本：内容 + 标签。 */
    String searchableText() {
        return content + " " + String.join(" ", tags);
    }

    /** 当前是否可注入上下文：状态有效 + 没过期 + 权限够。 */
    public boolean injectableFor(List<String> roles) {
        if (status != Status.ACTIVE) {
            return false;
        }
        if (validUntilMs > 0 && System.currentTimeMillis() > validUntilMs) {
            return false;
        }
        return permissions.isEmpty()
                || roles.stream().anyMatch(permissions::contains);
    }
}
