package com.example.harness.memory;

import java.util.List;

/**
 * 记忆五维 benchmark：评测从「支持多长」转向「过去是否真的有用」
 * （Memory 综述的评测主张：capacity / fidelity / persistence /
 * interference robustness / efficiency 拆开测量）。
 *
 * <p>每个维度一个确定性剧本，离线可跑，出分即断言——记忆系统的
 * 改动（打分器、融合、清理）跑一遍 bench 就知道伤了哪维：</p>
 * <ul>
 *   <li><b>fidelity 保真</b>：写入 N 条互不干扰的事实，逐条原样回查，
 *       top-1 命中比例——存了找不回等于没存；</li>
 *   <li><b>interference 抗干扰</b>：同一主题新旧事实并存，召回必须取新——
 *       否则记得越久越差；</li>
 *   <li><b>persistence 持久</b>：写入后隔若干其他操作再召回，仍命中——
 *       影响能维持多久；</li>
 *   <li><b>efficiency 效率</b>：一批任务的平均注入条数与触发器跳过率——
 *       检索本身的开销要可计量；</li>
 *   <li><b>capacity 容量</b>：限容量的库写爆后，淘汰生效、总量有界——
 *       库不无限涨。</li>
 * </ul>
 */
public final class MemoryBench {

    private MemoryBench() {
    }

    /** fidelity：factCount 条写入，逐条按标签回查，top-1 必须命中。 */
    public static double fidelity(int factCount) {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < factCount; i++) {
            store.add(Memory.Scope.PROJECT, "bench",
                    "事实-" + i + "：服务 svc-" + i + " 的负责人是 oncall-" + i,
                    "bench", List.of("svc-" + i));
        }
        int hits = 0;
        for (int i = 0; i < factCount; i++) {
            List<MemoryStore.Hit> hitsList = store.search("svc-" + i);
            if (!hitsList.isEmpty()
                    && hitsList.get(0).memory().content().contains("svc-" + i)) {
                hits++;
            }
        }
        return (double) hits / factCount;
    }

    /** interference：新旧同主题事实并存，top-1 必须是新的那条。 */
    public static boolean interference() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "bench",
                "项目预算 100 万", "旧会议纪要", List.of("预算"));
        store.add(Memory.Scope.PROJECT, "bench",
                "项目预算 110 万", "新审批记录", List.of("预算"));
        List<MemoryStore.Hit> hits = store.search("预算");
        return !hits.isEmpty() && hits.get(0).memory().content().contains("110 万");
    }

    /** persistence：写入 → 打扰（其他检索/写入）→ 原样回查仍命中。 */
    public static boolean persistence() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "bench",
                "发布窗口每周四下午", "运维手册", List.of("发布", "窗口"));
        for (int i = 0; i < 10; i++) {
            store.add(Memory.Scope.PROJECT, "bench",
                    "噪音事实 " + i, "noise", List.of("noise-" + i));
            store.search("噪音 noise-" + i);
        }
        return !store.search("发布窗口").isEmpty();
    }

    /** efficiency：返回 {平均注入条数, 跳过率}——检索开销的底线数据。 */
    public static double[] efficiency(List<String> tasks) {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.WORKSPACE, "bench",
                "架构图统一使用 Mermaid", "评审", List.of("架构图"));
        MemoryMetrics metrics = new MemoryMetrics();
        RecallPipeline pipeline = new RecallPipeline(metrics);
        int injected = 0;
        for (String task : tasks) {
            injected += pipeline.recall(task, List.of(), store,
                    KnowledgeBase.NONE, List.of()).contextLines().size();
        }
        MemoryMetrics.Snapshot s = metrics.snapshot();
        double skipRate = (double) s.triggersSkipped()
                / (s.triggersFired() + s.triggersSkipped());
        return new double[]{(double) injected / tasks.size(), skipRate};
    }

    /** capacity：限容量库写爆后淘汰生效、总量有界、淘汰的是最久未用。 */
    public static boolean capacity(int maxEntries, int writes) {
        MemoryStore store = new MemoryStore(maxEntries);
        for (int i = 0; i < writes; i++) {
            store.add(Memory.Scope.PROJECT, "bench",
                    "容量事实 " + i, "bench", List.of("cap-" + i));
        }
        long active = store.all().stream()
                .filter(m -> m.status() == Memory.Status.ACTIVE).count();
        return store.evictedCount() >= writes - maxEntries
                && store.all().size() <= maxEntries
                && active <= maxEntries
                && store.search("cap-0").isEmpty()      // 最老的被淘汰
                && !store.search("cap-" + (writes - 1)).isEmpty(); // 最新的在
    }
}
