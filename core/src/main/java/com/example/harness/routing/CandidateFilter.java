package com.example.harness.routing;

import com.example.harness.memory.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 候选筛选器：检索回来的东西先收窄，再决定给不交给主模型（文章 #4）。
 *
 * <p>记忆检索回 40 条、工具搜索回 25 个，不代表都该进上下文——
 * 检索回答「哪些可能相关」，筛选回答「哪些真正值得付费使用」。
 * 这一步是<b>分类/排序/筛选问题，不是生成问题</b>：用确定性的打分
 * 就够了，不必为每个小决定烧一次前沿模型（文章里的 Jev 式决策模型
 * 是同一路数的重型版，这里用规则演示同一个骨架）。</p>
 */
public final class CandidateFilter {

    /** 一个候选的得分。 */
    public record Scored<T>(T item, double score) {
    }

    /** 筛选结果：留下的 + 被滤掉的（带分数，可解释为什么没进上下文）。 */
    public record Outcome<T>(List<T> kept, List<Scored<T>> dropped) {
    }

    /**
     * 按 query 与候选文本的重叠打分，保留 topK 且不低于 minScore 的。
     *
     * @param minScore 0~1 之间的过线分，低于它的整体滤掉（不浪费主模型注意力）
     */
    public <T> Outcome<T> narrow(String query, List<T> candidates,
                                 Function<T, String> text, int topK, double minScore) {
        Set<String> terms = MemoryStore.terms(query);
        List<Scored<T>> ranked = new ArrayList<>();
        for (T c : candidates) {
            double score = overlap(terms, text.apply(c).toLowerCase(java.util.Locale.ROOT));
            ranked.add(new Scored<>(c, score));
        }
        ranked.sort(Comparator.comparingDouble(Scored<T>::score).reversed());
        List<T> kept = new ArrayList<>();
        List<Scored<T>> dropped = new ArrayList<>();
        int i = 0;
        for (Scored<T> s : ranked) {
            if (i < topK && s.score() >= minScore) {
                kept.add(s.item());
            } else {
                dropped.add(s);
            }
            i++;
        }
        return new Outcome<>(kept, dropped);
    }

    /** 分片在文本中的命中比例（标签/标题类短文本天然比长文本分高）。 */
    private static double overlap(Set<String> terms, String text) {
        if (terms.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }
}
