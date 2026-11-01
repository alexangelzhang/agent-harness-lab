package com.example.harness.memory;

import java.util.List;

/**
 * 记忆层演示：自动注入 + 主动检索 + 异步整理（2026-10-31 十二部件文章 #3）。
 *
 * <p>场景一「两层检索」：对话开始，Harness 自动把工作区/项目记忆放进初始上下文；
 * 任务干到一半模型主动 {@code search_memory("生产环境部署命名规范")} 补背景。
 * 自动和主动走同一个打分器。</p>
 *
 * <p>场景二「整理取代堆聊天记录」：用户说「我以后不用 PlantUML 了，改用 Mermaid」，
 * {@link MemoryConsolidator} 抽取出事实、把含「PlantUML」的旧记忆作废、去重后写入。
 * 留下的只有一条规范化事实，不是整段对话。</p>
 *
 * <p>运行：{@code mvn exec:java@memory}。</p>
 */
public class MemoryDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("记忆层：自动注入 + 主动检索 + 异步整理");
        System.out.println(line);
        scenarioRetrieve();
        scenarioConsolidate();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：对话本身不是记忆，值得留下的才进记忆库；");
        System.out.println("新事实进来，旧事实退役——记忆有生命周期，不是只进不出的堆。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：对话开始自动注入，中途主动 search_memory。 */
    private static void scenarioRetrieve() {
        header("场景一：两层检索", "开场自动注入，中途主动 search_memory");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.WORKSPACE, "平台组",
                "架构图统一使用 Mermaid，不使用 PlantUML", "架构评审 2026-09", 
                List.of("mermaid", "plantuml", "架构图"));
        store.add(Memory.Scope.WORKSPACE, "平台组",
                "生产环境部署命名遵循 <服务名>-<版本>-<序号>", "运维手册 v3", 
                List.of("部署", "命名", "生产"));
        store.add(Memory.Scope.USER, "andy",
                "代码评审意见用中文写", "个人偏好登记", 
                List.of("评审", "中文"));
        store.add(Memory.Scope.PROJECT, "支付中台",
                "支付中台数据库变更必须走 DBA 会签", "项目章程", 
                List.of("数据库", "变更", "dba"));

        // 自动注入：只拿当前项目可见的作用域（工作区 + 本项目），不灌个人偏好
        System.out.println("【自动注入】对话开始，注入当前项目相关记忆：");
        for (Memory m : store.injectable(Memory.Scope.WORKSPACE, "架构 部署 命名", 5)) {
            System.out.println("  - [" + m.scope() + "] " + m.content());
        }
        for (Memory m : store.injectable(Memory.Scope.PROJECT, "数据库 变更", 5)) {
            System.out.println("  - [" + m.scope() + "] " + m.content());
        }

        // 主动检索：模型干到一半发现缺背景
        System.out.println();
        System.out.println("【主动检索】模型中途调用 search_memory(\"部署命名规范\")：");
        for (MemoryStore.Hit hit : store.search("部署命名规范")) {
            System.out.printf("  - [%.2f] %s%n", hit.score(), hit.memory().content());
        }
    }

    /** 场景二：一轮对话收尾，整理成事实，旧偏好退役。 */
    private static void scenarioConsolidate() {
        header("场景二：异步整理", "一句话进，事实留下，旧事实退役");
        MemoryStore store = new MemoryStore();
        Memory old = store.add(Memory.Scope.WORKSPACE, "平台组",
                "架构图统一使用 PlantUML", "架构评审 2025-11", 
                List.of("plantuml", "架构图"));
        System.out.println("整理前记忆库：");
        store.all().forEach(m -> System.out.println("  - [" + m.id() + "] " + m.content()));

        String turn = "对了，我以后不用 PlantUML 写架构文档了，改用 Mermaid。";
        System.out.println();
        System.out.println("本轮对话收尾，原文：" + turn);
        MemoryConsolidator.Result r = new MemoryConsolidator().consolidate(
                store, Memory.Scope.WORKSPACE, "平台组", turn, "对话 2026-10-31");
        System.out.println("写回决定：" + r.decisions());
        System.out.println();
        System.out.println("整理后记忆库（含历史状态）：");
        store.all().forEach(m -> System.out.println("  - [" + m.id() + "|" + m.status() + "] "
                + m.content()));
        boolean oldRetired = store.all().stream().anyMatch(m -> m.id().equals(old.id())
                && m.status() == Memory.Status.SUPERSEDED);
        // 「PlantUML」还能搜到——是新事实带着退役词标签；旧记录本身不再出现
        boolean oldGone = store.search("PlantUML").stream()
                .noneMatch(h -> h.memory().id().equals(old.id()));
        System.out.println();
        System.out.println(oldRetired && oldGone
                ? "旧 PlantUML 记忆标记「已被替代」——历史可查，检索不再出它。"
                : "!! 退役语义不对");
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
