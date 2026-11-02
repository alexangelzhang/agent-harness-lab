package com.example.harness.skills;

import com.example.agent.tool.Tool;

import java.util.List;

/**
 * 技能与事件总线演示（教程第 10 章）。
 *
 * <p>场景「按需加载」：agent 启动时系统提示词里只有基础能力；
 * 剧本用户请求命中技能信号 → 注册表加载「订单查询」技能，
 * 工具表随任务长出来；模型随后调用技能里的工具查物流，
 * 每次调用广播 {@code ToolCalled} 事件。</p>
 *
 * <p>场景「订阅者互不相识」：事件总线上挂三个订阅者——
 * 审计日志（什么都记）、指标计数（只数 ToolCalled）、
 * 渲染（技能加载时打印提示词清单变更）。agent 主循环
 * 对它们一无所知，只 publish。</p>
 *
 * <p>场景二「任务单元检索」（2026-10-31 十二部件文章 #1）：
 * capability 归组工具目录，模型用 search_operations 按任务发现
 * operation，load_operation 只把命中的工具定义加载进上下文。</p>
 *
 * <p>运行：{@code mvn exec:java@skills}。</p>
 */
public class SkillsScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("技能与事件总线：按需加载能力包，订阅者各干各的");
        System.out.println(line);

        EventBus bus = new EventBus();
        // 订阅者 1：审计日志（所有事件都记）
        bus.on(Object.class, e -> System.out.println("  〔审计〕" + e));
        // 订阅者 2：指标（只数工具调用）
        var toolCounter = new int[]{0};
        bus.on(SkillRegistry.ToolCalled.class, e -> toolCounter[0]++);
        // 订阅者 3：渲染（技能加载时打印工具表变化）
        bus.on(SkillRegistry.SkillLoaded.class,
                e -> System.out.printf("  〔渲染〕系统提示词新增技能「%s」，携带 %d 个工具%n",
                        e.skillName(), e.toolCount()));

        SkillRegistry registry = new SkillRegistry(bus)
                .register(new Skill("订单查询", "订单",
                        List.of(
                                new Tool("query_order", "按订单号查状态，输入订单号",
                                        input -> "订单" + input + "：已发货，顺丰 SF123"),
                                new Tool("track_logistics", "按订单号查物流轨迹",
                                        input -> "订单" + input + "：杭州仓 → 上海转运 → 派送中")),
                        "先 query_order 拿状态，用户追问轨迹再 track_logistics。"))
                .register(new Skill("周报撰写", "周报",
                        List.of(new Tool("list_commits", "列出本周提交", input -> "feat: 沙箱后端; fix: 缓存命中")),
                        "先 list_commits 取材，再按 成果/风险/计划 三段成文。"));

        // ── 按需加载：agent 启动不带技能，请求命中才长出来 ──
        header("场景一：按需加载", "启动时只有基础能力，命中信号才加载技能");
        System.out.println("系统提示词（初始）：你是助手，可回答一般问题。\n");
        bus.publish(new TurnStarted("帮我查一下订单 1001 的物流"));

        Skill skill = registry.load("帮我查一下订单 1001 的物流");
        System.out.println("\n加载后的技能清单（进系统提示词）：");
        System.out.println(indent(registry.loadedManifest()));

        // ── 工具调用走事件总线 ──
        System.out.println("模型调用工具：");
        for (Tool t : skill.tools()) {
            String output = t.observe("1001");
            bus.publish(new SkillRegistry.ToolCalled(t.name(), "1001", output));
            System.out.println("  " + t.name() + "(1001) → " + output);
        }

        // ── 收尾：发布计数与订阅者战果 ──
        System.out.println("\n事件发布计数：" + bus.publishedCounts());
        System.out.println("指标订阅者数到 ToolCalled × " + toolCounter[0]);
        System.out.println("——agent 主循环里没有一行提到审计/指标/渲染，它们只靠订阅。");

        scenarioOperationIndex();
        System.exit(0);
    }

    /** 场景二：任务单元检索——按任务发现能力，只加载命中的工具定义（文章 #1）。 */
    private static void scenarioOperationIndex() {
        header("场景二：任务单元检索", "search_operations 按需发现，不面对整本工具目录");
        OperationIndex index = new OperationIndex()
                .register(new Capability("aws", "云服务查询与操作", List.of(
                        new Tool("list_services", "列出正在使用的阿里云服务", in -> "ECS ×12, RDS ×3, OSS ×2"),
                        new Tool("monthly_cost", "按团队分类计算本月云费用", in -> "平台组 4.2 万，业务组 11.8 万"))))
                .register(new Capability("jira", "任务系统", List.of(
                        new Tool("create_issue", "创建任务单", in -> "ISSUE-1024 已创建"),
                        new Tool("search_issues", "按关键词搜任务单", in -> "命中 3 条"))))
                .register(new Capability("billing", "账单与发票", List.of(
                        new Tool("list_invoices", "按客户列出账单，可按逾期状态过滤", in -> "cust_42: 2 张逾期"),
                        new Tool("refund", "对指定账单发起退款", in -> "退款已受理"))));

        System.out.println("模型问 list_capabilities：");
        System.out.println(indent(index.listCapabilities()));
        System.out.println();

        String task = "把 cust_42 的逾期发票退掉";
        System.out.println("任务：" + task);
        List<OperationIndex.Operation> ops = index.searchOperations(task);
        System.out.println("search_operations 命中（降序）：");
        ops.forEach(o -> System.out.printf("  - [%.2f] %s.%s：%s%n",
                o.score(), o.capability(), o.tool().name(), o.tool().description()));

        OperationIndex.Operation top = ops.get(0);
        System.out.println();
        System.out.println("load_operation 只加载这一条定义进上下文：");
        index.loadOperation(top.capability(), top.tool().name()).ifPresent(t ->
                System.out.println("  " + t.name() + "：" + t.description()));
        System.out.println("  其余 " + (ops.size() - 1) + " 条候选不进上下文——目录是按需发现的，不是一次给全的。");
    }

    /** 一轮对话开始（事件总线负载，演示超类型订阅用）。 */
    private record TurnStarted(String userRequest) {
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("-".repeat(66));
        System.out.println("【" + title + "】" + subtitle);
    }

    private static String indent(String s) {
        return "   " + s.replace("\n", "\n   ");
    }
}
