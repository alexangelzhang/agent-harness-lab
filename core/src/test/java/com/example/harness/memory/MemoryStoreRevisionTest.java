package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreRevisionTest {

    @Test
    void legacyConstructorKeepsDefaultsAndAddsConservativeKnowledgeState() {
        Memory memory = new Memory("legacy", Memory.Scope.PROJECT, "owner", "内容",
                "source", 1, List.of("标签"), 0.8, Memory.Status.ACTIVE, 0, List.of());

        assertEquals(0.8, memory.confidence());
        assertEquals(Memory.Status.ACTIVE, memory.status());
        assertEquals(Memory.KnowledgeState.OBSERVED, memory.knowledgeState());
        assertEquals(0, memory.revision());
    }

    @Test
    void staleCorrectionCannotOverwriteFirstWritersRevision() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "出口在西侧",
                "run-1", List.of("出口"), 0.8, 0, List.of(), Memory.KnowledgeState.OBSERVED);
        long firstReaderRevision = original.revision();
        long secondReaderRevision = original.revision();

        assertTrue(store.correct(original.id(), firstReaderRevision, "出口在东侧", "run-2"));
        assertFalse(store.correct(original.id(), secondReaderRevision, "出口在南侧", "run-3"));

        Memory current = store.get(original.id());
        assertEquals("出口在东侧", current.content());
        assertEquals("run-2", current.source());
        assertEquals(Memory.KnowledgeState.VERIFIED, current.knowledgeState());
        assertEquals(1, current.revision());
    }

    @Test
    void staleMergeFailsThenRereadPreservesBothAdditions() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "合同保留双方条款",
                "run-1", List.of("合同"));
        long firstReaderRevision = original.revision();
        long secondReaderRevision = original.revision();

        assertTrue(store.merge(original.id(), firstReaderRevision, "甲方承担运输费", "run-2"));
        assertFalse(store.merge(original.id(), secondReaderRevision, "乙方承担保险费", "run-3"));

        Memory reread = store.get(original.id());
        assertTrue(store.merge(original.id(), reread.revision(), "乙方承担保险费", "run-3"));
        Memory current = store.get(original.id());
        assertTrue(current.content().contains("甲方承担运输费"));
        assertTrue(current.content().contains("乙方承担保险费"));
        assertEquals(2, current.revision());
    }

    @Test
    void versionedMergeRejectsMissingMemoryAndKeepsLegacyEntryPoint() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "基础事实", "run-1", List.of());

        assertFalse(store.merge("missing", 0, "不应写入", "run-2"));
        assertFalse(store.merge(original.id(), 1, "旧版本不应写入", "run-2"));
        assertTrue(store.merge(original.id(), "旧入口补充", "run-3"));
        assertEquals("基础事实；旧入口补充", store.get(original.id()).content());
    }

    @Test
    void staleConfirmationLeavesUnconfirmedMemoryUntouched() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "可能应该提前重试",
                "run-1", List.of("重试"), 0.4, 0, List.of(), Memory.KnowledgeState.INFERRED);

        assertTrue(store.merge(original.id(), "连续三次失败后生效", "run-2"));
        assertFalse(store.confirm(original.id(), 0));

        Memory current = store.get(original.id());
        assertEquals(Memory.Status.UNCONFIRMED, current.status());
        assertEquals("可能应该提前重试；连续三次失败后生效", current.content());
        assertEquals(1, current.revision());
    }
}
