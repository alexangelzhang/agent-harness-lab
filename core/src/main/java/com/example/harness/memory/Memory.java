package com.example.harness.memory;

import java.util.List;

/**
 * 一条记忆：值得留下的那部分对话，不是对话本身。
 *
 * <p>记忆带作用域、来源、所有者、置信度、生命周期、有效期和权限。
 * 检索默认只出 ACTIVE 且没过期的记录；被替代的、过期的留在库里备查，
 * 但不干扰当前任务。知识状态另外记录这条内容是观察、推断、已验证事实，
 * 还是已经失效的知识；版本用于阻止共享整理时旧值覆盖新值。</p>
 *
 * @param id             唯一标识
 * @param scope          作用域：用户偏好 / 工作区规范 / 项目事实
 * @param owner          所有者（谁说了算、谁来改）
 * @param content        整理后的事实表述（一句话，不是聊天记录）
 * @param source         来源（哪次对话、哪份文档）
 * @param createdAtMs    写入时间
 * @param tags           检索用的关键词
 * @param confidence     置信度 0~1（1.0=用户明确确认，0.5 以下=模型推断待验证）
 * @param status         生命周期状态：有效 / 待确认 / 已被替代 / 已过期
 * @param validUntilMs   有效期截止（0 = 长期有效）；过了就视为过期
 * @param permissions    读取权限白名单（空 = 不限制）；非空时只有列出的角色可读
 * @param knowledgeState 知识状态：观察 / 推断 / 已验证 / 已失效
 * @param revision       修订版本，从 0 开始，每次实际变更递增
 */
public record Memory(String id, Scope scope, String owner, String content,
                     String source, long createdAtMs, List<String> tags,
                     double confidence, Status status, long validUntilMs,
                     List<String> permissions, KnowledgeState knowledgeState,
                     long revision) {

    /** 记忆作用域，生命周期和可见范围各不同。 */
    public enum Scope { USER, WORKSPACE, PROJECT }

    /** 生命周期状态：写入不是只有新增，状态会流转。 */
    public enum Status { ACTIVE, UNCONFIRMED, SUPERSEDED, EXPIRED }

    /** 知识状态回答“凭什么相信”，生命周期状态回答“现在还能不能用”。 */
    public enum KnowledgeState { OBSERVED, INFERRED, VERIFIED, STALE }

    /** 保留原公开构造：新字段不影响既有调用方。 */
    public Memory(String id, Scope scope, String owner, String content,
                  String source, long createdAtMs, List<String> tags,
                  double confidence, Status status, long validUntilMs,
                  List<String> permissions) {
        this(id, scope, owner, content, source, createdAtMs, tags, confidence,
                status, validUntilMs, permissions,
                legacyKnowledgeState(confidence, status), 0);
    }

    private static KnowledgeState legacyKnowledgeState(double confidence, Status status) {
        if (status == Status.SUPERSEDED || status == Status.EXPIRED) {
            return KnowledgeState.STALE;
        }
        return confidence < 0.6 || status == Status.UNCONFIRMED
                ? KnowledgeState.INFERRED : KnowledgeState.OBSERVED;
    }

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
