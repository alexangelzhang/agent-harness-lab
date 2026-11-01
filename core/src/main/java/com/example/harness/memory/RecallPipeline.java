package com.example.harness.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 召回管道：触发 → 三路召回 → 融合排序 → 注入上下文
 * （智能体AI 文章 16 步流程的主干）。
 *
 * <p>三路各管一件事：短期记忆接上当前进度，长期记忆恢复过去背景，
 * 外部知识提供事实依据——按任务动态选择，不是每次都全跑。
 * 融合排序是五维的（文章 STEP 08）：相关性打底，时效、置信度加权，
 * 重要性抬升，<b>冲突不硬合并</b>——新旧事实打架时按时间和权威取
 * 当前有效值，被抑制的连同理由一起写进 notes，可审计。</p>
 *
 * <p>清理周期在岗：每 {@value #SWEEP_EVERY} 次召回先跑一轮
 * {@link MemoryJanitor#sweep} 再查——到期记忆不占检索位，
 * 遗忘是后台维护动作，不靠人想起才跑（Memory 综述：遗忘是控制面
 * 第四动作）。</p>
 */
public final class RecallPipeline {

    /** 一次召回的产出：注入行 + 解释 + 指标。 */
    public record RecallResult(List<String> contextLines, List<String> notes,
                               MemoryMetrics.Snapshot stats) {

        /** 拼好的增强上下文块。 */
        public String contextBlock() {
            return String.join("\n", contextLines);
        }
    }

    /** 融合权重：相关性 × (0.4 + 0.6·置信度) × 时效系数 + 重要性抬升。 */
    private static final double IMPORTANCE_BOOST = 0.15;
    private static final List<String> IMPORTANT_TAGS = List.of(
            "决策", "规范", "必须", "红线", "已定");

    private final MemoryTrigger trigger = new MemoryTrigger();
    private final MemoryMetrics metrics;
    private final MemoryJanitor janitor;

    /** 每多少次召回触发一轮后台清理。sweep 幂等且廉价，8 是拍脑袋的
     *  稳妥值——生产可换时间窗口或挂定时器，接口不变。 */
    private static final int SWEEP_EVERY = 8;
    private int recallCount;

    public RecallPipeline(MemoryMetrics metrics) {
        this(metrics, new MemoryJanitor());
    }

    /** 注入清理器（测试/演示用短保留期时）。 */
    public RecallPipeline(MemoryMetrics metrics, MemoryJanitor janitor) {
        this.metrics = metrics;
        this.janitor = janitor;
    }

    /**
     * 一次完整召回。
     *
     * @param task            当前任务文本
     * @param shortTermTurns  短期记忆素材（最近几轮对话/任务状态）
     * @param store           长期记忆库
     * @param kb              外部知识库（不需要第三路传 {@link KnowledgeBase#NONE}）
     * @param roles           当前用户角色（记忆权限过滤用）
     */
    public RecallResult recall(String task, List<String> shortTermTurns,
                               MemoryStore store, KnowledgeBase kb,
                               List<String> roles) {
        // 后台维护：每 SWEEP_EVERY 次召回先清一轮再查——
        // 到期记忆别占着检索位，遗忘在岗而不是靠人想起才跑
        recallCount++;
        MemoryJanitor.Report sweep = recallCount % SWEEP_EVERY == 0
                ? janitor.sweep(store) : null;
        if (sweep != null) {
            metrics.recordJanitor(sweep.expired(), sweep.archived());
        }

        // STEP 03：触发器先裁决，不该查直接短路
        MemoryTrigger.Decision d = trigger.decide(task);
        metrics.recordTrigger(d.recall());
        List<String> notes = new ArrayList<>();
        if (sweep != null) {
            notes.add("后台清理（第 " + recallCount + " 次召回前自动跑）："
                    + sweep);
        }
        if (!d.recall()) {
            notes.add("触发器跳过（" + d.rule() + "）——不查历史");
            return new RecallResult(List.of(), notes, metrics.snapshot());
        }
        notes.add("触发器放行（" + d.rule() + "）");

        // STEP 05-07：三路召回
        List<String> shortLane = shortTermTurns.stream().limit(3).toList();
        List<MemoryStore.Hit> longLane = store.search(task, null, roles).stream()
                .limit(3).toList();
        List<KnowledgeBase.Fact> kbLane = kb.lookup(task).stream().limit(2).toList();
        metrics.recordLane("short", shortLane.size());
        metrics.recordLane("long", longLane.size());
        metrics.recordLane("kb", kbLane.size());
        notes.add("三路召回：短期 " + shortLane.size() + " / 长期 "
                + longLane.size() + " / 外部 " + kbLane.size());

        // STEP 08：融合排序（相关性 × 置信度 × 时效 + 重要性）+ 冲突抑制
        List<Fused> fused = new ArrayList<>();
        for (MemoryStore.Hit hit : longLane) {
            fused.add(new Fused(hit, fuseScore(hit)));
        }
        fused.sort((a, b) -> Double.compare(b.score, a.score));
        List<Fused> kept = new ArrayList<>();
        for (Fused f : fused) {
            Fused rival = kept.stream()
                    .filter(k -> sharesTag(k, f) && !sameContent(k, f))
                    .findFirst().orElse(null);
            if (rival == null) {
                kept.add(f);
                continue;
            }
            // 冲突对：谁更旧谁让位（与迭代顺序无关）
            if (strictlyOlder(f.hit.memory(), rival.hit.memory())) {
                notes.add("冲突抑制：「" + f.hit.memory().content()
                        + "」让位给更新的「" + rival.hit.memory().content() + "」"
                        + "（按时间与入库序取当前有效值，不硬合并）");
            } else {
                notes.add("冲突抑制：「" + rival.hit.memory().content()
                        + "」让位给更新的「" + f.hit.memory().content() + "」"
                        + "（按时间与入库序取当前有效值，不硬合并）");
                kept.remove(rival);
                kept.add(f);
            }
        }

        // STEP 09：组织成模型能直接用的上下文块
        List<String> lines = new ArrayList<>();
        for (String turn : shortLane) {
            lines.add("[短期] " + turn);
        }
        for (Fused f : kept) {
            Memory m = f.hit.memory();
            lines.add("[长期] " + m.content() + "（来源：" + m.source()
                    + "，置信 " + m.confidence() + "，相关度 "
                    + String.format("%.2f", f.score) + "）");
        }
        for (KnowledgeBase.Fact fact : kbLane) {
            lines.add("[知识库] " + fact.content() + "（来源：" + fact.source()
                    + (fact.authoritative() ? "，权威来源" : "") + "）");
        }
        metrics.recordInjected(lines.size());
        return new RecallResult(lines, notes, metrics.snapshot());
    }

    /** 五维融合分。 */
    private static double fuseScore(MemoryStore.Hit hit) {
        Memory m = hit.memory();
        double confidence = 0.4 + 0.6 * m.confidence();
        double ageMs = Math.max(0, System.currentTimeMillis() - m.createdAtMs());
        double freshness = Math.max(0.5, 1.0 - ageMs / 180.0 / 24 / 3600 / 1000);
        double importance = m.tags().stream().anyMatch(IMPORTANT_TAGS::contains)
                ? IMPORTANCE_BOOST : 0;
        return hit.score() * confidence * freshness + importance;
    }

    /** 冲突对判定：共享标签 + 内容不同。 */
    private static boolean sharesTag(Fused a, Fused b) {
        Set<String> shared = new LinkedHashSet<>(a.hit.memory().tags());
        shared.retainAll(b.hit.memory().tags());
        return !shared.isEmpty();
    }

    private static boolean sameContent(Fused a, Fused b) {
        return a.hit.memory().content().equals(b.hit.memory().content());
    }

    /** a 是否严格早于 b：时间戳不同看时间戳，同刻看入库序号（id 的数字后缀）。 */
    private static boolean strictlyOlder(Memory a, Memory b) {
        if (a.createdAtMs() != b.createdAtMs()) {
            return a.createdAtMs() < b.createdAtMs();
        }
        return a.id().compareTo(b.id()) < 0;
    }

    /** 一条长期记忆 + 融合分。 */
    private record Fused(MemoryStore.Hit hit, double score) {
    }
}
