package com.example.harness.memory;

import com.example.harness.RunRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情境记忆（Definitive Guide Ch10 三大记忆类型的第三块）：工作记忆
 * （短期上下文）和语义记忆（长期知识）都有了，缺的是<b>情境记忆</b>——
 * 历史交互轨迹里成败的教训。没有它，每次会话都是一张白纸，同一个坑
 * 摔两次。
 *
 * <p>{@link #learn} 从运行档案里蒸馏教训进记忆库：</p>
 * <ul>
 *   <li>失败的运行 → 主导失败原因翻成「下次先处理这个」的教训
 *       （置信 0.85，经验证过的轨迹比模型推断可信，直接 ACTIVE）；</li>
 *   <li>幂等：教训已在库里就跳过，重放档案不会重复写。</li>
 * </ul>
 *
 * <p>教训进库后走正常召回路径（RecallPipeline / search），下次同类
 * 任务开场注入——档案从「躺在底账里等审计」变成「会被人想起的经验」。</p>
 */
public final class EpisodicMemory {

    /** 一次蒸馏的报告：新增了几条、各是什么。 */
    public record Report(int added, List<String> lessons) {
    }

    private static final double LESSON_CONFIDENCE = 0.85;

    /** 从一批运行档案里蒸馏教训进记忆库（幂等，可反复跑）。 */
    public Report learn(MemoryStore store, List<RunRecord> runs) {
        int added = 0;
        List<String> lessons = new ArrayList<>();
        for (RunRecord r : runs) {
            if (isSuccess(r.terminal())) {
                continue;
            }
            String reason = dominantRetryReason(r);
            if (reason == null) {
                continue;
            }
            String lesson = "任务「" + abbreviate(r.task()) + "」上次失败卡在这："
                    + abbreviate(reason) + "。下次接到同类任务，先把这个前置条件处理掉。";
            if (!store.search(lesson).isEmpty()) {
                continue; // 已有同主题教训（检索命中即视为重复）
            }
            store.add(Memory.Scope.PROJECT, "episodic", lesson,
                    "运行档案 " + r.runId() + " 蒸馏", List.of("教训", "episodic"),
                    LESSON_CONFIDENCE, 0, List.of());
            added++;
            lessons.add(lesson);
        }
        return new Report(added, List.copyOf(lessons));
    }

    /** 失败运行里出现次数最多的重试理由；没有重试步返回 null。 */
    private static String dominantRetryReason(RunRecord r) {
        Map<String, Integer> retries = new LinkedHashMap<>();
        r.steps().stream()
                .filter(s -> "RETRY".equals(s.verdictKind()) && !s.reason().isBlank())
                .forEach(s -> retries.merge(s.reason().strip(), 1, Integer::sum));
        return retries.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static boolean isSuccess(String terminal) {
        return "SUCCESS".equals(terminal) || "ACCEPT".equals(terminal)
                || "APPROVED_DONE".equals(terminal) || "AUTO_DONE".equals(terminal);
    }

    private static String abbreviate(String s) {
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }
}
