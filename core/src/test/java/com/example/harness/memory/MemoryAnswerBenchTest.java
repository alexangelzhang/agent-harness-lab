package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryAnswerBenchTest {

    private static final Set<String> ABSTENTIONS = Set.of("不知道", "无法确定");

    @Test
    void acceptsCompleteExpectedIdentifierWithoutForbiddenValues() {
        MemoryAnswerBench.Category category = new MemoryAnswerBench().classify(
                "项目砾石当前标识是 EAST-42。", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS);

        assertEquals(MemoryAnswerBench.Category.CORRECT, category);
    }

    @Test
    void forbiddenValueWinsEvenWhenExpectedValueAlsoAppears() {
        MemoryAnswerBench bench = new MemoryAnswerBench();

        assertEquals(MemoryAnswerBench.Category.CONTAMINATED, bench.classify(
                "旧标识 NORTH-17 已改为 EAST-42。", "EAST-42",
                Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.CONTAMINATED, bench.classify(
                "NORTH-17", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
    }

    @Test
    void abstentionIsSeparateFromCorrectAndWrong() {
        MemoryAnswerBench bench = new MemoryAnswerBench();

        assertEquals(MemoryAnswerBench.Category.ABSTAINED, bench.classify(
                "没有相关记忆，无法确定。", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.ABSTAINED, bench.classify(
                "我不知道。", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
    }

    @Test
    void emptyIrrelevantAndPartialIdentifiersAreWrong() {
        MemoryAnswerBench bench = new MemoryAnswerBench();

        assertEquals(MemoryAnswerBench.Category.WRONG,
                bench.classify("", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.WRONG,
                bench.classify("请查看项目文档。", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.WRONG,
                bench.classify("EAST-4", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
    }

    @Test
    void rejectsIdentifiersEmbeddedInLongerTokens() {
        MemoryAnswerBench bench = new MemoryAnswerBench();

        assertEquals(MemoryAnswerBench.Category.WRONG, bench.classify(
                "EAST-420", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.WRONG, bench.classify(
                "XEAST-42", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.WRONG, bench.classify(
                "EAST-42-EXTRA", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
        assertEquals(MemoryAnswerBench.Category.WRONG, bench.classify(
                "NORTH-170", "EAST-42", Set.of("NORTH-17"), ABSTENTIONS));
    }
}
