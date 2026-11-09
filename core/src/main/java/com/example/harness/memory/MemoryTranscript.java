package com.example.harness.memory;

import java.util.List;

/** 原始对话记录：整理结果可以压缩，证据层不能被 Wiki 或摘要替代。 */
public final class MemoryTranscript {

    private final List<String> entries;

    public MemoryTranscript(List<String> entries) {
        this.entries = List.copyOf(entries);
    }

    /** 证据必须原样出现在某条原始记录里，空白字符串不算证据。 */
    public boolean containsEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return false;
        }
        return entries.stream().anyMatch(entry -> entry.contains(evidence));
    }

    public List<String> entries() {
        return entries;
    }
}
