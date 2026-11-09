package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryDreamerTest {

    @Test
    void correctionRequiresEvidencePresentInTranscript() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "出口在西侧",
                "run-1", List.of("出口"));
        MemoryDreamer dreamer = dreamer(store, "用户复查：出口在东侧");

        MemoryDreamer.Decision missing = dreamer.correct(original.id(), 0,
                "出口在南侧", "出口在南侧");
        assertFalse(missing.accepted());
        assertEquals("出口在西侧", store.get(original.id()).content());
        assertEquals(0, store.get(original.id()).revision());

        MemoryDreamer.Decision corrected = dreamer.correct(original.id(), 0,
                "出口在东侧", "出口在东侧");
        assertTrue(corrected.accepted());
        assertEquals("出口在东侧", store.get(original.id()).content());
        assertEquals(Memory.KnowledgeState.VERIFIED,
                store.get(original.id()).knowledgeState());
        assertEquals(1, store.get(original.id()).revision());
    }

    @Test
    void promotionRequiresEvidenceAndCurrentRevision() {
        MemoryStore store = new MemoryStore();
        Memory original = store.add(Memory.Scope.PROJECT, "owner", "失败后应调整节奏",
                "run-1", List.of("重试"), 0.4, 0, List.of(),
                Memory.KnowledgeState.INFERRED);
        MemoryDreamer withoutEvidence = dreamer(store, "无关记录");

        assertFalse(withoutEvidence.promote(original.id(), 0, "连续三次失败后调整节奏").accepted());
        assertEquals(Memory.Status.UNCONFIRMED, store.get(original.id()).status());

        MemoryDreamer dreamer = dreamer(store, "连续三次失败后调整节奏，问题消失");
        assertFalse(dreamer.promote(original.id(), 1, "连续三次失败后调整节奏").accepted());
        assertEquals(Memory.Status.UNCONFIRMED, store.get(original.id()).status());

        assertTrue(dreamer.promote(original.id(), 0, "连续三次失败后调整节奏").accepted());
        assertEquals(Memory.Status.ACTIVE, store.get(original.id()).status());
        assertEquals(Memory.KnowledgeState.VERIFIED, store.get(original.id()).knowledgeState());
        assertEquals(1, store.get(original.id()).revision());
    }

    @Test
    void noteCreationUsesResidualGate() {
        MemoryStore store = new MemoryStore();
        MemoryDreamer dreamer = dreamer(store, "原始记录");

        MemoryDreamer.Decision rejected = dreamer.createNote(Memory.Scope.PROJECT,
                "owner", "当前金币 30", "run-1", List.of("金币"), 0.8,
                Memory.KnowledgeState.OBSERVED, true, 3600_000);
        assertFalse(rejected.accepted());
        assertNull(rejected.memory());
        assertTrue(store.all().isEmpty());

        MemoryDreamer.Decision accepted = dreamer.createNote(Memory.Scope.PROJECT,
                "owner", "连续三次失败后调整节奏", "run-1", List.of("重试"), 0.9,
                Memory.KnowledgeState.VERIFIED, false, 3600_000);
        assertTrue(accepted.accepted());
        assertEquals(1, store.all().size());
    }

    private static MemoryDreamer dreamer(MemoryStore store, String transcriptText) {
        MemoryTranscript transcript = new MemoryTranscript(List.of(transcriptText));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 1.0);
        return new MemoryDreamer(store, new ResidualMemoryGate(60_000), transcript, wiki);
    }
}
