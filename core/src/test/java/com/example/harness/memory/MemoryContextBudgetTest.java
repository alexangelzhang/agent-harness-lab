package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextBudgetTest {

    @Test
    void indexContainsPointersAndStateButNotMemoryBody() {
        Memory memory = new Memory("mem-7", Memory.Scope.PROJECT, "owner", "秘密正文",
                "run-9", 1, List.of("出口", "规范"), 0.9, Memory.Status.ACTIVE, 0,
                List.of(), Memory.KnowledgeState.VERIFIED, 3);

        String line = new MemoryContextBudget(10, 1024).indexLine(memory);

        assertTrue(line.contains("mem-7"));
        assertTrue(line.contains("出口"));
        assertTrue(line.contains("run-9"));
        assertTrue(line.contains("VERIFIED"));
        assertTrue(line.contains("3"));
        assertFalse(line.contains("秘密正文"));
    }

    @Test
    void lineBudgetOmitsWholeOverflowLines() {
        MemoryContextBudget.Result result = new MemoryContextBudget(2, 1024)
                .apply(List.of("第一行", "第二行", "第三行"));

        assertEquals(List.of("第一行", "第二行"), result.lines());
        assertEquals(1, result.omitted());
    }

    @Test
    void utf8BudgetDoesNotSplitChineseCharacters() {
        MemoryContextBudget.Result result = new MemoryContextBudget(3, 7)
                .apply(List.of("中文", "a"));

        assertEquals(List.of("中文"), result.lines());
        assertEquals(1, result.omitted());
        assertEquals("中文".getBytes(StandardCharsets.UTF_8).length, result.utf8Bytes());
    }

    @Test
    void singleOversizedLineProducesNoPartialCharacter() {
        MemoryContextBudget.Result result = new MemoryContextBudget(3, 2)
                .apply(List.of("中"));

        assertTrue(result.lines().isEmpty());
        assertEquals(1, result.omitted());
        assertEquals(0, result.utf8Bytes());
    }

    @Test
    void recallUsesIndexOnlyWhenBudgetIsExplicitlyEnabled() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "owner", "出口在东侧", "run-1", List.of("出口"));
        RecallPipeline indexed = new RecallPipeline(new MemoryMetrics(), new MemoryJanitor(),
                new MemoryContextBudget(10, 1024));

        RecallPipeline.RecallResult result = indexed.recall("继续检查出口", List.of(),
                store, KnowledgeBase.NONE, List.of());

        assertEquals(1, result.contextLines().size());
        assertFalse(result.contextBlock().contains("出口在东侧"));
        assertFalse(result.contextBlock().contains("VERIFIED"));
        assertTrue(result.contextBlock().contains("OBSERVED"));
        assertTrue(result.contextBlock().contains("run-1"));
    }

    @Test
    void defaultRecallKeepsLegacyFullTextLine() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "owner", "出口在东侧", "run-1", List.of("出口"));
        RecallPipeline pipeline = new RecallPipeline(new MemoryMetrics());

        RecallPipeline.RecallResult result = pipeline.recall("继续检查出口", List.of(),
                store, KnowledgeBase.NONE, List.of());

        assertTrue(result.contextBlock().contains("[长期] 出口在东侧（来源：run-1，置信"));
    }
}
