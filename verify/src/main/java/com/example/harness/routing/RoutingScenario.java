package com.example.harness.routing;

import com.example.harness.memory.Memory;
import com.example.harness.memory.MemoryStore;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由与筛选演示：小决定不花大模型（2026-10-31 十二部件文章 #4 + #9）。
 *
 * <p>场景一「筛选进上下文」：记忆检索回 5 条候选，{@link CandidateFilter}
 * 只放 2 条过线的进初始上下文，滤掉谁、为什么（分数）全程可见。</p>
 *
 * <p>场景二「按任务挑模型」：删生产库走前沿档、查订单状态走快档、
 * 写迁移方案走均衡档——每条决定带命中规则名，路由错了查得出来。</p>
 *
 * <p>运行：{@code mvn exec:java@routing}。</p>
 */
public class RoutingScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("路由与筛选：候选先收窄，模型按任务挑");
        System.out.println(line);
        scenarioFilter();
        scenarioRoute();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：检索负责『可能相关』，筛选负责『值得给』；");
        System.out.println("前沿模型的注意力是稀缺资源，小决定别去麻烦它。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：40 条候选的记忆库，只有过线的进上下文。 */
    private static void scenarioFilter() {
        header("场景一：筛选进上下文", "检索 5 条，只放 2 条过线的");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "支付中台", "支付中台核心表：payments/refunds/chargebacks",
                "库表字典", List.of("payments", "表结构"));
        store.add(Memory.Scope.PROJECT, "支付中台", "退款接口幂等键 = refund_no + channel",
                "接口设计文档", List.of("退款", "幂等"));
        store.add(Memory.Scope.WORKSPACE, "平台组", "午餐补贴每周三发放",
                "行政通知", List.of("午餐", "补贴"));
        store.add(Memory.Scope.WORKSPACE, "平台组", "代码评审意见用中文写",
                "个人偏好登记", List.of("评审", "中文"));
        store.add(Memory.Scope.PROJECT, "支付中台", "对账任务每日凌晨 2 点跑批",
                "运维手册", List.of("对账", "跑批"));

        String task = "退款对账时 payments 表怎么对上渠道账单？";
        List<Memory> candidates = store.search("退款 对账 payments").stream()
                .map(MemoryStore.Hit::memory).toList();
        System.out.println("任务：" + task);
        System.out.println("检索回 " + candidates.size() + " 条候选：");
        candidates.forEach(m -> System.out.println("  - " + m.content()));

        CandidateFilter filter = new CandidateFilter();
        // Harness 从任务里提炼检索词，不是把整句扔给打分器
        CandidateFilter.Outcome<Memory> out = filter.narrow(
                "退款 对账 payments 账单", candidates, m -> m.content() + " " + m.tags(), 2, 0.2);
        System.out.println();
        System.out.println("进初始上下文的 " + out.kept().size() + " 条：");
        out.kept().forEach(m -> System.out.println("  ✓ " + m.content()));
        System.out.println("滤掉的 " + out.dropped().size() + " 条（分数低于线或超出 topK）：");
        out.dropped().forEach(s -> System.out.printf("  ✗ [%.2f] %s%n", s.score(),
                s.item().content()));
    }

    /** 场景二：同一批任务，三种档位。 */
    private static void scenarioRoute() {
        header("场景二：按任务挑模型", "高危走 frontier，查表走 fast，其余 balanced");
        LlmClient fast = new ScriptedLlm().says("订单 12345：已发货");
        LlmClient balanced = new ScriptedLlm().says("迁移方案草稿……");
        LlmClient frontier = new ScriptedLlm().says("高危操作已识别，建议先审批");
        ModelRouter router = new ModelRouter()
                .register(ModelRouter.Tier.FAST, fast)
                .register(ModelRouter.Tier.BALANCED, balanced)
                .register(ModelRouter.Tier.FRONTIER, frontier);

        List<String> tasks = new ArrayList<>(List.of(
                "帮我删除生产环境的临时索引",
                "查一下订单 12345 的物流状态",
                "写一份 MySQL 到 PostgreSQL 的迁移方案"));
        for (String task : tasks) {
            ModelRouter.Decision d = router.decide(task);
            String reply = router.pick(task).complete(task);
            System.out.println("任务：" + task);
            System.out.println("  -> 档位 " + d.tier() + "（" + d.rule() + "）");
            System.out.println("  -> 回复：" + reply);
        }
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
