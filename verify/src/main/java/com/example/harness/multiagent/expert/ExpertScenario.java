package com.example.harness.multiagent.expert;

import java.util.List;
import java.util.Map;

/**
 * curated 只读专家剧本（OmO 研读落地，2026-11-06）。
 *
 * <p>三幕：</p>
 * <ul>
 *   <li><b>隔离与收窄</b>：主会话有机密，专家只拿到任务卡；写与
 *       再委派被拦下入账，读全通；报告记 FAST 档；</li>
 *   <li><b>名册与 Team Mode</b>：四家 curated 专家的档与策略按定义
 *       装配；只读专家当队友一律硬拒（带指路理由）；</li>
 *   <li><b>并行委派回收</b>：领班同时派 explore + librarian，两份
 *       报告回收合并——便宜的活并行烧，贵的上下文一点不沾。</li>
 * </ul>
 *
 * <p>运行：{@code mvn exec:java@expert -pl verify}（离线剧本，无需 key）。</p>
 */
public class ExpertScenario {

    /** 领班的主会话：有计划有机密——这些一个 token 都不该进专家上下文。 */
    private static final String MAIN_SESSION_SNIPPET =
            "【主会话】领班正在规划结算重构。内部预算口径：Q4 封顶 80 万，勿外传。"
                    + "下一步：派 explore 查优惠券抵扣逻辑在哪。";

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("curated 只读专家：fresh 会话 + 工具收窄 + 便宜模型");
        System.out.println(line);

        boolean ok = true;
        Map<String, String> fsFiles = Map.of(
                "src/order/Discount.java", "public class Discount { /* 满减 + 券叠加 */ }",
                "docs/coupon-usage.md", "# 券使用文档：满 200 减 30，不可叠加");
        java.util.function.Function<String, String> fs = fsFiles::get;
        ExpertRegistry registry = ExpertRegistry.curated();

        // ── 第一幕：隔离与收窄
        System.out.println("── 第一幕：隔离与收窄");
        String taskCard = "任务：在虚拟工作区里定位优惠券抵扣逻辑，回报文件位置与一句话摘要。";
        ExpertSession explore = registry.spawn("explore", taskCard, fs);
        System.out.println("  主会话节选 = " + MAIN_SESSION_SNIPPET);
        System.out.println("  专家上下文 = " + explore.contextView());
        System.out.println("  主会话机密是否泄漏进专家上下文："
                + explore.contextView().contains("预算"));
        String found = explore.useTool("read", "src/order/Discount.java");
        explore.useTool("write", "src/order/Discount.java");      // 试图改稿
        explore.useTool("spawn", "再来一个专家");                    // 试图再委派
        ok &= report("上下文隔离：专家只有任务卡，机密没跟过去",
                !explore.contextView().contains("预算")
                        && explore.contextView().contains("优惠券抵扣"));
        ok &= report("读全通且拿到内容", found != null
                && found.contains("满减"));
        ok &= report("写与再委派被拦下入账",
                explore.denials().size() == 2
                        && explore.policy().denials() == 2);
        ExpertReport report1 = explore.finish("抵扣逻辑在 src/order/Discount.java：满减与券叠加");
        ok &= report("报告记 FAST 档 + 一行摘要可审计",
                report1.tier() == ExpertSession.Tier.FAST
                        && report1.summaryLine().contains("explore")
                        && report1.summaryLine().contains("FAST"));
        System.out.println("  摘要：" + report1.summaryLine());
        System.out.println();

        // ── 第二幕：名册与 Team Mode
        System.out.println("── 第二幕：名册与 Team Mode");
        ExpertRegistry.TeamVerdict vExplore = registry.teamVerdict("explore");
        ExpertRegistry.TeamVerdict vReviewer = registry.teamVerdict("plan-reviewer");
        ExpertRegistry.TeamVerdict vGhost = registry.teamVerdict("ghost");
        System.out.println("  explore 判词：" + vExplore.reason());
        ok &= report("只读专家当队友一律硬拒，理由指路 task 委派",
                !vExplore.eligible() && vExplore.reason().contains("task 委派")
                        && !vReviewer.eligible());
        ok &= report("名册外的名字直接拒",
                !vGhost.eligible() && vGhost.reason().contains("名册"));
        ExpertSession looker = registry.spawn("vision-looker", "任务：读一张流程图。", fs);
        looker.useTool("read", "docs/coupon-usage.md");
        looker.useTool("edit", "docs/coupon-usage.md");
        ok &= report("allow-list 只留 read：连 edit 也进不来",
                looker.reads().size() == 1 && looker.denials().size() == 1);
        System.out.println();

        // ── 第三幕：并行委派回收
        System.out.println("── 第三幕：并行委派回收");
        ExpertSession code = registry.spawn("explore",
                "任务：找券逻辑在哪。", fs);
        ExpertSession docs = registry.spawn("librarian",
                "任务：找券的外部使用文档。", fs);
        code.useTool("read", "src/order/Discount.java");
        docs.useTool("read", "docs/coupon-usage.md");
        List<ExpertReport> reports = List.of(
                code.finish("代码侧：src/order/Discount.java"),
                docs.finish("文档侧：满 200 减 30，不可叠加"));
        String merged = "【领班汇总】\n" + reports.get(0).conclusion()
                + "\n" + reports.get(1).conclusion();
        System.out.println(merged);
        ok &= report("两家并行、各自只读自己该读的",
                code.reads().size() == 1 && docs.reads().size() == 1);
        ok &= report("两份报告都走 FAST 档",
                reports.stream().allMatch(r -> r.tier() == ExpertSession.Tier.FAST));
        ok &= report("结论回收进领班上下文",
                merged.contains("代码侧") && merged.contains("文档侧"));

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：专家只读、上下文隔离、便宜档并行，Team Mode 硬拒指路。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
