package com.example.harness.memory;

import com.example.harness.RunRecord;

import java.util.List;

/**
 * 情境记忆演示（Definitive Guide Ch10 缺口补齐）：两次失败档案蒸馏出
 * 一条教训进记忆库，下次同类任务召回时教训跟着进场——同一个坑不摔
 * 第二次。顺带验证幂等：同批档案再蒸馏一次，不会写重复教训。
 *
 * <p>运行：{@code mvn exec:java@episodic}（构造档案，离线）。</p>
 */
public class EpisodicDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("情境记忆：失败档案 → 教训 → 下次任务召回");
        System.out.println(line);

        List<RunRecord> archive = List.of(
                new RunRecord("run-f-1", "agentloop", "评审邀请邮件", "EXHAUSTED",
                        List.of(new RunRecord.Step(1, "v1", "RETRY",
                                        "没写评审时间", 1000),
                                new RunRecord.Step(2, "v2", "RETRY",
                                        "没写评审时间", 1000)),
                        4, 4000, 0),
                new RunRecord("run-f-2", "agentloop", "评审邀请邮件", "EXHAUSTED",
                        List.of(new RunRecord.Step(1, "v1", "RETRY",
                                        "没写评审时间", 1000),
                                new RunRecord.Step(2, "v2", "RETRY",
                                        "没写评审时间", 1000)),
                        4, 4000, 0));

        MemoryStore store = new MemoryStore();
        EpisodicMemory episodic = new EpisodicMemory();
        System.out.println("\n── 第一次蒸馏（两条失败档案）──");
        EpisodicMemory.Report r1 = episodic.learn(store, archive);
        r1.lessons().forEach(l -> System.out.println("  入库：「" + l + "」"));
        System.out.println("  新增 " + r1.added() + " 条");

        System.out.println("\n── 幂等检查：同批档案再蒸馏一次 ──");
        EpisodicMemory.Report r2 = episodic.learn(store, archive);
        System.out.println("  再新增 " + r2.added() + " 条（应为 0）");

        System.out.println("\n── 下次同类任务：召回时教训跟着进场 ──");
        RecallPipeline.RecallResult recall = new RecallPipeline(new MemoryMetrics())
                .recall("继续写评审邀请邮件", List.of(), store,
                        KnowledgeBase.NONE, List.of());
        recall.contextLines().forEach(l -> System.out.println("  注入：" + l));
        recall.notes().forEach(n -> System.out.println("  解释：" + n));

        boolean ok = r1.added() == 1 && r2.added() == 0
                && recall.contextBlock().contains("没写评审时间");
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：档案从「躺在底账等审计」变成「开场就被想起的经验」。"
                : "!! 情境记忆没走通");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }
}
