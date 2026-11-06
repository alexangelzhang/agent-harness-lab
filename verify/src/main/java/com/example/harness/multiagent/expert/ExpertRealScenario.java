package com.example.harness.multiagent.expert;

import java.util.Map;

/**
 * curated 只读专家真实冒烟（{@code ExpertScenario} 的真实对照组，2026-11-06）。
 *
 * <p>剧本已把隔离与收窄断言全绿；这条把真实模型接进专家位：explore
 * 读虚拟文件系统，模型基于<b>任务卡 + 读到的内容</b>产出结论，收进
 * 报告。真实面对的问题很实际：专家位换了真模型，隔离（上下文只有
 * 任务卡）与只读（denials 为空）这两件事还成不成立。</p>
 *
 * <p>一次真实调用。运行：{@code mvn exec:java@expertreal -pl verify}，需 LLM_API_KEY。</p>
 */
public class ExpertRealScenario {

    /** 领班主会话的机密：不该出现在专家上下文里。 */
    private static final String MAIN_SECRET = "内部预算口径：Q4 封顶 80 万，勿外传";

    public static void main(String[] args) {
        com.example.harness.config.AgentConfig.load().requireApiKey();
        com.example.langgraph4j.llm.LlmClient llm = new com.example.langgraph4j.llm.JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("只读专家真实冒烟：真模型坐专家位，隔离与只读还成立吗");
        System.out.println(line);

        boolean ok = true;
        ExpertRegistry registry = ExpertRegistry.curated();
        Map<String, String> fsFiles = Map.of(
                "src/order/Discount.java",
                "public class Discount { /* 满减 + 优惠券叠加，互斥逻辑在 canStack() */ }",
                "src/order/README.md", "# 结算模块说明（与券无关）");
        java.util.function.Function<String, String> fs = fsFiles::get;

        String taskCard = "任务：在虚拟工作区里定位优惠券抵扣逻辑，回报文件位置与一句话摘要。";
        ExpertSession explore = registry.spawn("explore", taskCard, fs);
        System.out.println("── 专家上下文 = " + explore.contextView());
        System.out.println("── 领班机密在场吗：" + explore.contextView().contains(MAIN_SECRET));

        // 专家走正常路径读文件（真模型这一步由剧本驱动，接模型循环时换成模型自己发 read）
        String content = explore.useTool("read", "src/order/Discount.java");
        System.out.println("── 读到内容：" + content);

        String conclusion = llm.complete(
                "你是只读检索专家 explore。任务卡：" + explore.contextView() + "\n"
                        + "你刚读到的文件内容：" + content + "\n"
                        + "用一两句话回报结论，必须包含文件路径和关键逻辑名。"
                        + "你的工具是只读的，不要提出任何修改建议。");
        System.out.println("── 专家结论：" + abbreviate(conclusion));

        ExpertReport report = explore.finish(conclusion);
        System.out.println("── 报告摘要：" + report.summaryLine());

        ok &= report("上下文隔离：主会话机密没跟过去",
                !explore.contextView().contains(MAIN_SECRET)
                        && explore.contextView().contains("优惠券抵扣"));
        ok &= report("只读在位：全程零拦下（专家没试图写）", report.denials().isEmpty());
        ok &= report("读了该读的、留了痕", report.reads().contains("src/order/Discount.java"));
        ok &= report("结论带文件路径与关键逻辑（结构断言）",
                conclusion.contains("Discount.java") && conclusion.contains("canStack"));
        ok &= report("报告记 FAST 档", report.tier() == ExpertSession.Tier.FAST);

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：真模型坐专家位，fresh 会话与只读约束原样成立。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
