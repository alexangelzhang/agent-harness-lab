package com.example.harness.memory;

import java.util.List;

/**
 * 离线整理入口：创建残差笔记、依据原始记录纠错或转正、构建 Wiki 表示、
 * 修复断链。它不做通用自然语言矛盾识别，只执行可核验的受控更新。
 */
public final class MemoryDreamer {

    /** 整理决定；只有成功写入时才返回新的记忆版本。 */
    public record Decision(boolean accepted, String action, String reason, Memory memory) {
    }

    private final MemoryStore store;
    private final ResidualMemoryGate gate;
    private final MemoryTranscript transcript;
    private final MemoryWiki wiki;

    public MemoryDreamer(MemoryStore store, ResidualMemoryGate gate,
                         MemoryTranscript transcript, MemoryWiki wiki) {
        this.store = store;
        this.gate = gate;
        this.transcript = transcript;
        this.wiki = wiki;
    }

    /** 创建长期笔记，先过残差与半衰期闸门。 */
    public Decision createNote(Memory.Scope scope, String owner, String content,
                               String source, List<String> tags, double confidence,
                               Memory.KnowledgeState knowledgeState,
                               boolean reobservable, long halfLifeMs) {
        ResidualMemoryGate.Decision admission = gate.evaluate(reobservable, halfLifeMs);
        if (!admission.accepted()) {
            return new Decision(false, "创建笔记", admission.reason(), null);
        }
        Memory memory = store.add(scope, owner, content, source, tags, confidence,
                0, List.of(), knowledgeState);
        return new Decision(true, "创建笔记", admission.reason(), memory);
    }

    /** 纠错必须同时有原始反证和当前版本，任一缺失都不改原文。 */
    public Decision correct(String id, long expectedRevision,
                            String correctedContent, String evidence) {
        if (!transcript.containsEvidence(evidence)) {
            return new Decision(false, "纠错", "原始 transcript 中不存在这段反证", null);
        }
        if (!store.correct(id, expectedRevision, correctedContent, evidence)) {
            return new Decision(false, "纠错", "记忆不存在或版本已经变化", null);
        }
        return new Decision(true, "纠错", "反证存在且版本匹配", store.get(id));
    }

    /** 低置信记忆上提同样需要证据与当前版本。 */
    public Decision promote(String id, long expectedRevision, String evidence) {
        if (!transcript.containsEvidence(evidence)) {
            return new Decision(false, "上提", "原始 transcript 中不存在确认证据", null);
        }
        if (!store.confirm(id, expectedRevision)) {
            return new Decision(false, "上提", "记忆不存在或版本已经变化", null);
        }
        Memory current = store.get(id);
        if (current.status() != Memory.Status.ACTIVE) {
            return new Decision(false, "上提", "记忆不是待确认状态", null);
        }
        return new Decision(true, "上提", "确认证据存在且版本匹配", current);
    }

    /** 构建可导航表示，Wiki 自己检查来源和体积。 */
    public MemoryWiki.Decision buildRepresentation(String id, String content,
                                                   List<String> sourceMemoryIds,
                                                   List<String> links) {
        return wiki.createPage(id, content, sourceMemoryIds, links);
    }

    /** 修复表示层断链，不修改原始证据。 */
    public MemoryWiki.Decision repairLink(String fromPageId, String missingTarget,
                                          String redirectTarget) {
        return wiki.repairLink(fromPageId, missingTarget, redirectTarget);
    }
}
