package com.example.harness.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆写入的事后评分：precision 看写下的内容后来用了多少，recall 看后来
 * 需要的内容当时写下了多少。需要集合来自任务或评测标注，不由模型自报。
 */
public final class MemoryPredictionBench {

    /** 一次任务及其后续观察，三个集合都使用稳定的记忆 id。 */
    public record Episode(Set<String> written, Set<String> neededLater,
                          Set<String> usedLater) {
        public Episode {
            written = Set.copyOf(written);
            neededLater = Set.copyOf(neededLater);
            usedLater = Set.copyOf(usedLater);
        }
    }

    /** 聚合分子分母保留在结果里，避免只给比例而无法核对口径。 */
    public record Score(double precision, double recall, long writtenCount,
                        long usedCount, long neededCount, long neededAndWrittenCount) {
    }

    public Score evaluate(List<Episode> episodes) {
        long written = 0;
        long used = 0;
        long needed = 0;
        long neededAndWritten = 0;
        for (Episode episode : episodes) {
            for (String id : episode.usedLater()) {
                if (!episode.written().contains(id)) {
                    throw new IllegalArgumentException("实际使用的记忆未写入：" + id);
                }
                if (!episode.neededLater().contains(id)) {
                    throw new IllegalArgumentException("实际使用的记忆不在后续需要集合：" + id);
                }
            }
            Set<String> covered = new HashSet<>(episode.neededLater());
            covered.retainAll(episode.written());
            written += episode.written().size();
            used += episode.usedLater().size();
            needed += episode.neededLater().size();
            neededAndWritten += covered.size();
        }
        return new Score(ratio(used, written), ratio(neededAndWritten, needed),
                written, used, needed, neededAndWritten);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }
}
