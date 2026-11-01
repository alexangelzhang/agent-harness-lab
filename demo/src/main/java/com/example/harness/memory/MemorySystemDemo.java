package com.example.harness.memory;

import java.util.List;
import java.util.Map;

/**
 * 记忆系统演示：触发 → 三路召回 → 融合 → 写回 → 指标
 * （2026-10-31，智能体AI「七模块 16 步」文章的缺口补齐）。
 *
 * <p>场景一「触发器」：「继续上次的方案」放行检索；「1+1 等于几」短路——
 * 不该查的查了是负担。</p>
 *
 * <p>场景二「三路召回 + 融合」：预算三条历史（100 万旧版已被替代、
 * 120 万被替代、110 万当前有效）+ 知识库制度 + 短期对话——注入上下文
 * 只带当前有效预算，冲突抑制连同理由可审计。</p>
 *
 * <p>场景三「写回三操作」：ADD 新偏好 / MERGE 同主题补充 / SUPERSEDE
 * 旧状态标记被替代（历史保留可查）。</p>
 *
 * <p>场景四「指标」：触发率、三路命中、注入条数一屏收尾。</p>
 *
 * <p>运行：{@code mvn exec:java@memorysystem}。</p>
 */
public class MemorySystemDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("记忆系统：触发 / 三路召回 / 融合 / 写回 / 指标");
        System.out.println(line);
        MemoryStore store = seed();
        KnowledgeBase kb = knowledgeBase();
        MemoryMetrics metrics = new MemoryMetrics();

        scenarioTrigger(new RecallPipeline(metrics));
        RecallPipeline.RecallResult result = scenarioRecall(store, kb, metrics);
        scenarioWriteback(store);
        scenarioMetrics(metrics);

        boolean ok = !result.contextLines().isEmpty()
                && result.contextBlock().contains("110 万")
                && result.notes().stream().anyMatch(n -> n.contains("冲突抑制"))
                && !result.contextBlock().contains("100 万");
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：在正确的时间，给当前任务提供正确、有效、可信、允许使用的信息。"
                : "!! 召回闭环没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：触发器两态。 */
    private static void scenarioTrigger(RecallPipeline pipeline) {
        header("场景一：触发器", "该查的查，不该查的别添乱");
        printRecall(pipeline, "继续按上次确定的评估标准分析这个项目",
                List.of(), new MemoryStore(), KnowledgeBase.NONE, List.of());
        printRecall(pipeline, "1+1 等于几？", List.of(), new MemoryStore(),
                KnowledgeBase.NONE, List.of());
        System.out.println();
    }

    private static void printRecall(RecallPipeline pipeline, String task,
                                    List<String> shortTerm, MemoryStore store,
                                    KnowledgeBase kb, List<String> roles) {
        RecallPipeline.RecallResult r = pipeline.recall(task, shortTerm, store, kb, roles);
        System.out.println("  任务：「" + task + "」");
        r.notes().forEach(n -> System.out.println("    → " + n));
        if (!r.contextLines().isEmpty()) {
            r.contextLines().forEach(l -> System.out.println("    " + l));
        }
    }

    /** 场景二：三路召回 + 五维融合 + 冲突抑制。 */
    private static RecallPipeline.RecallResult scenarioRecall(MemoryStore store,
                                                              KnowledgeBase kb,
                                                              MemoryMetrics metrics) {
        header("场景二：三路召回 + 融合", "预算三条历史打架，只带当前有效值");
        RecallPipeline pipeline = new RecallPipeline(metrics);
        List<String> shortTerm = List.of(
                "用户：继续按上次的评估标准，把 Q3 复盘报告写完",
                "Agent：大纲已定，差预算执行和小结两节");
        RecallPipeline.RecallResult r = pipeline.recall(
                "Q3 复盘的预算执行按哪个数写？继续上次的报告",
                shortTerm, store, kb, List.of("平台组"));
        System.out.println("  解释：");
        r.notes().forEach(n -> System.out.println("    - " + n));
        System.out.println();
        System.out.println("  注入上下文：");
        r.contextLines().forEach(l -> System.out.println("    " + l));
        return r;
    }

    /** 场景三：写回三操作。 */
    private static void scenarioWriteback(MemoryStore store) {
        header("场景三：写回三操作", "新增 / 合并 / 替代，不是只有 add");
        MemoryConsolidator consolidator = new MemoryConsolidator();

        System.out.println("  ① ADD——没见过的新事实：");
        String t1 = "以后提交信息统一用 Conventional Commits 格式。";
        System.out.println("    原文：" + t1);
        consolidator.consolidate(store, Memory.Scope.USER, "andy", t1, "对话 D1")
                .decisions().forEach(d -> System.out.println("    → " + d.op() + "：" + d.why()));

        System.out.println("  ② MERGE——同主题补充（与已有「中文写」记忆共享标签）：");
        String t2 = "以后评审意见也统一用中文写。";
        System.out.println("    原文：" + t2);
        consolidator.consolidate(store, Memory.Scope.USER, "andy", t2, "对话 D2")
                .decisions().forEach(d -> System.out.println("    → " + d.op() + "："
                        + d.why()
                        + (d.target() == null ? "" : " → " + d.target().content())));

        System.out.println("  ③ SUPERSEDE——旧状态被替代：");
        String t3 = "我以后不用 Word 写周报模板了，改用 Markdown。";
        System.out.println("    原文：" + t3);
        consolidator.consolidate(store, Memory.Scope.USER, "andy", t3, "对话 D3")
                .decisions().forEach(d -> System.out.println("    → " + d.op() + "：" + d.why()));
        System.out.println("    记忆库现状（含历史状态）：");
        store.all().forEach(m -> System.out.println("      [" + m.id() + "|" + m.status()
                + "] " + m.content()));
    }

    /** 场景四：指标收尾。 */
    private static void scenarioMetrics(MemoryMetrics metrics) {
        header("场景四：指标", "存进去不算本事，被正确使用才算");
        System.out.println("  " + metrics);
        System.out.println("  读法：跳过 > 0 说明触发器拦住了白查；");
        System.out.println("        冲突抑制出现说明融合层在工作，不是靠模型自行判断信谁。");
    }

    /** 种子数据：预算三条历史 + 其他工作区/项目记忆。 */
    private static MemoryStore seed() {
        MemoryStore store = new MemoryStore();
        long day = 24L * 3600 * 1000;
        long now = System.currentTimeMillis();
        Memory v1 = store.add(Memory.Scope.PROJECT, "支付中台",
                "项目预算 100 万", "立项会议纪要", List.of("预算", "决策"));
        // v1 已被整理流程标记替代（正常路径）；v2 还挂着 ACTIVE——
        // 模拟「整理还没跑到」的现实，给融合层的冲突抑制留一个真冲突
        store.add(Memory.Scope.PROJECT, "支付中台",
                "预算调整为 120 万", "两周前会议纪要", List.of("预算", "决策"));
        store.supersede(v1.id());
        store.add(Memory.Scope.PROJECT, "支付中台",
                "最终预算 110 万", "昨天的正式审批记录", List.of("预算", "决策"));
        store.add(Memory.Scope.WORKSPACE, "平台组",
                "架构图统一使用 Mermaid", "架构评审 2026-09", List.of("mermaid", "架构图"));
        store.add(Memory.Scope.USER, "andy",
                "代码评审意见用中文写", "个人偏好登记", List.of("评审", "中文写"),
                1.0, 0, List.of());
        store.add(Memory.Scope.USER, "andy",
                "周报模板用 Word 编写", "去年的习惯", List.of("word", "周报"));
        return store;
    }

    private static KnowledgeBase knowledgeBase() {
        return new KnowledgeBase.InMemory(Map.of(
                "报销 制度", List.of(new KnowledgeBase.Fact(
                        "差旅报销需部门负责人 + 财务双签", "报销制度 v4", true)),
                "预算 审批", List.of(new KnowledgeBase.Fact(
                        "预算执行以正式审批记录为准，会议纪要仅作参考", "财务规范", true))));
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
