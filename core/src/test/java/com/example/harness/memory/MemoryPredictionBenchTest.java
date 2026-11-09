package com.example.harness.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPredictionBenchTest {

    @Test
    void scoresFutureNeedAndActualUse() {
        MemoryPredictionBench.Episode episode = new MemoryPredictionBench.Episode(
                Set.of("a", "b"), Set.of("a", "c"), Set.of("a"));

        MemoryPredictionBench.Score score = new MemoryPredictionBench()
                .evaluate(List.of(episode));

        assertEquals(0.5, score.precision());
        assertEquals(0.5, score.recall());
        assertEquals(2, score.writtenCount());
        assertEquals(1, score.usedCount());
        assertEquals(2, score.neededCount());
        assertEquals(1, score.neededAndWrittenCount());
    }

    @Test
    void aggregatesCountsInsteadOfAveragingEpisodeScores() {
        MemoryPredictionBench.Episode first = new MemoryPredictionBench.Episode(
                Set.of("a"), Set.of("a"), Set.of("a"));
        MemoryPredictionBench.Episode second = new MemoryPredictionBench.Episode(
                Set.of("b", "c", "d"), Set.of("b", "c", "d", "e"), Set.of("b"));

        MemoryPredictionBench.Score score = new MemoryPredictionBench()
                .evaluate(List.of(first, second));

        assertEquals(2.0 / 4.0, score.precision());
        assertEquals(4.0 / 5.0, score.recall());
    }

    @Test
    void emptyDenominatorIsNotApplicableRatherThanZero() {
        MemoryPredictionBench.Score score = new MemoryPredictionBench()
                .evaluate(List.of(new MemoryPredictionBench.Episode(
                        Set.of(), Set.of(), Set.of())));

        assertTrue(Double.isNaN(score.precision()));
        assertTrue(Double.isNaN(score.recall()));
    }

    @Test
    void rejectsUseThatWasNotWrittenAndNeeded() {
        MemoryPredictionBench bench = new MemoryPredictionBench();

        assertThrows(IllegalArgumentException.class, () -> bench.evaluate(List.of(
                new MemoryPredictionBench.Episode(Set.of("a"), Set.of("a"), Set.of("b")))));
        assertThrows(IllegalArgumentException.class, () -> bench.evaluate(List.of(
                new MemoryPredictionBench.Episode(Set.of("a", "b"), Set.of("a"), Set.of("b")))));
    }
}
