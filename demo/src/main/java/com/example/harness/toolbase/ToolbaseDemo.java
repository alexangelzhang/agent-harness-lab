package com.example.harness.toolbase;

import com.example.harness.ApprovalPolicy;
import com.example.harness.ToolGate;
import com.example.harness.interaction.PendingAction;
import com.example.harness.skills.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具底座演示：治理壳 + fail-closed 解析 + 渠道准入 + 统一装配
 * （2026-10-31，尼恩/QwenPaw 工具底座文章的缺口补齐）。
 *
 * <p>场景一「治理壳」：业务工具 refund_customer 里一行权限代码都没有；
 * 注册时声明 WRITE 权限 + NEEDS_APPROVAL 风险。专员调 → 网关 DENIED；
 * 经理调未批准 → 挂起发 PendingAction；批准后 → 放行执行。</p>
 *
 * <p>场景二「fail-closed」：模型吐出一个没注册的工具名，Phase0 直接拒、
 * 审计留痕，不猜不降级。</p>
 *
 * <p>场景三「渠道准入」：白名单直通、黑名单拒、陌生人进 pending 等管理员。</p>
 *
 * <p>场景四「统一装配」：CLI 入口和 Web 入口各调一次
 * {@link WorkspaceBootstrap#build()}，装配摘要一字不差。</p>
 *
 * <p>运行：{@code mvn exec:java@toolbase}。</p>
 */
public class ToolbaseDemo {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("工具底座：治理壳 / fail-closed / 渠道准入 / 统一装配");
        System.out.println(line);
        scenarioGovernedShell();
        scenarioFailClosed();
        scenarioChannelAcl();
        scenarioBootstrap();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：治理挂在壳上不进业务；查不到就拒，不猜；");
        System.out.println("进门先验人；装配只有一个口子，入口再多不漂移。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：治理壳——业务零权限逻辑，三层治理全在壳上。 */
    private static void scenarioGovernedShell() {
        header("场景一：治理壳", "业务工具一行权限代码都没有");
        EventBus bus = new EventBus();
        bus.on(PendingAction.class, pa -> System.out.println("  〔事件〕挂起 " + pa.id() + "："
                + pa.action() + "（" + pa.reason() + "）"));

        GovernedTool refund = new GovernedTool(
                new SchemaTool("refund_customer", "对指定客户发起退款", List.of(
                        new SchemaTool.Param("customer_id", "string", true, "客户 ID"),
                        new SchemaTool.Param("amount", "number", true, "退款金额（分）")),
                        in -> "退款完成：" + in),
                ToolGate.Level.WRITE, ApprovalPolicy.Risk.NEEDS_APPROVAL, bus);

        ToolGate specialist = new ToolGate("专员", ToolGate.Level.READ);
        ToolGate manager = new ToolGate("经理", ToolGate.Level.ADMIN);

        System.out.println("  ① 专员调用：" + refund.call(specialist, "cust_42,1800", false));
        System.out.println("  ② 经理未批先调：" + refund.call(manager, "cust_42,1800", false));
        System.out.println("  ③ 人工批准后经理再调：" + refund.call(manager, "cust_42,1800", true));
        System.out.println("  工具定义里只有业务逻辑：amount + customer_id 两个参数。");
    }

    /** 场景二：fail-closed——未注册工具 Phase0 拒绝并留痕。 */
    private static void scenarioFailClosed() {
        header("场景二：fail-closed 解析", "查不到就拒，不猜不降级");
        List<ToolResolver.Denied> denials = new ArrayList<>();
        ToolResolver resolver = new ToolResolver(denials::add);
        resolver.register(new GovernedTool(
                new SchemaTool("query_order", "按订单号查询", List.of(), in -> "订单状态：已发货"),
                ToolGate.Level.READ, ApprovalPolicy.Risk.AUTO, new EventBus()));

        System.out.println("  模型输出 Action: query_order[1001] → 解析执行 ✓");
        resolver.resolve("query_order").call(new ToolGate("bot", ToolGate.Level.READ), "1001", false);
        System.out.println("  模型输出 Action: delete_everything[--yes] → Phase0：");
        try {
            resolver.resolve("delete_everything");
        } catch (ToolResolver.ToolNotFound e) {
            System.out.println("    ✗ " + e.getMessage());
        }
        System.out.println("  审计留痕：" + denials);
    }

    /** 场景三：渠道准入——先验人，再谈工具。 */
    private static void scenarioChannelAcl() {
        header("场景三：渠道准入", "白名单直通 / 黑名单拒 / 陌生人 pending");
        ChannelAcl acl = new ChannelAcl()
                .allowChannel("内部运维群")
                .allow("客户支持群", "客服小赵")
                .deny("客户支持群", "捣乱者阿强");

        AtomicInteger seq = new AtomicInteger();
        admitAndPrint(acl, "内部运维群", "运维老王", "查一下磁盘占用", seq);
        admitAndPrint(acl, "客户支持群", "客服小赵", "帮我查订单 1001", seq);
        admitAndPrint(acl, "客户支持群", "捣乱者阿强", "执行 rm -rf /", seq);
        admitAndPrint(acl, "客户支持群", "陌生客户甲", "在吗？", seq);
        acl.approvePending("客户支持群", "陌生客户甲");
        System.out.println("  管理员审核通过「陌生客户甲」（移出 pending + 进白名单）→ 再次准入："
                + acl.admit("客户支持群", "陌生客户甲", "在吗？").verdict()
                + "，pending 队列剩 " + acl.pending().size() + " 条");
    }

    private static void admitAndPrint(ChannelAcl acl, String channel, String user,
                                      String firstMsg, AtomicInteger seq) {
        ChannelAcl.Admission a = acl.admit(channel, user, firstMsg);
        System.out.println("  ④" + seq.incrementAndGet() + " " + channel + "/" + user + " → "
                + a.verdict() + "（" + a.reason() + "）");
    }

    /** 场景四：统一装配——两个入口，同一个工厂，一字不差。 */
    private static void scenarioBootstrap() {
        header("场景四：统一装配", "入口再多，装配不漂移");
        Workspace cliEntry = WorkspaceBootstrap.build();
        Workspace webEntry = WorkspaceBootstrap.build();
        System.out.println("  CLI 入口装配：" + cliEntry.assemblyDigest());
        System.out.println("  Web 入口装配：" + webEntry.assemblyDigest());
        boolean same = cliEntry.assemblyDigest().equals(webEntry.assemblyDigest());
        System.out.println("  两入口一致：" + same);
        System.out.println("  send_message 的契约（进提示词的是参数表，不是散文）：");
        System.out.println("    " + cliEntry.tools().get(2).schemaManifest().replace("\n", "\n    "));
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
