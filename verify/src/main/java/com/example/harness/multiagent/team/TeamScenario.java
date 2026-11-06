package com.example.harness.multiagent.team;

import com.example.harness.multiagent.expert.ExpertRegistry;
import java.util.List;
import java.util.Optional;

/**
 * Team Mode 横向通信剧本（OmO 研读落地，2026-11-06）。
 *
 * <p>三幕：</p>
 * <ul>
 *   <li><b>共享黑板</b>：点对点 + 广播 + 认领原子（两人抢一块活只
 *       一人得手）+ 谁认领谁完成；</li>
 *   <li><b>护栏</b>：只读专家建队 parse 时硬拒、队员再建队被拦入账、
 *       payload cap 拒发、消息预算耗尽拒发（防陪跑）；</li>
 *   <li><b>生命周期</b>：请示收工 → 领班批准、非领班批无效；
 *       {@code team_status} 一页汇总成员/任务/信箱/预算/被拦台账。</li>
 * </ul>
 *
 * <p>运行：{@code mvn exec:java@team -pl verify}（离线剧本，无需 key）。</p>
 */
public class TeamScenario {

    /** 建队参数：小预算小 payload，护栏很快见底、断言跑得动。 */
    private static final int MAX_MEMBERS = 4;
    private static final int MAX_PAYLOAD = 64;
    private static final int MAX_MESSAGES = 5;

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Team Mode：共享信箱 + 共享任务清单 + 一圈护栏");
        System.out.println(line);

        boolean ok = true;
        ExpertRegistry experts = ExpertRegistry.curated();
        java.util.function.Function<String, Optional<String>> eligibility =
                name -> experts.isReadOnlyExpert(name)
                        ? Optional.of(name + " 是只读专家，进队只会看不能写——"
                                + "要它干活走 task 委派")
                        : Optional.empty();

        // ── 第一幕：共享黑板
        System.out.println("── 第一幕：共享黑板");
        Team team = new Team("结算重构",
                List.of("planner", "builder"), MAX_MEMBERS,
                MAX_PAYLOAD, MAX_MESSAGES, eligibility);
        team.mailbox().send("planner", "builder", "先把券抵扣那块活领了");
        team.mailbox().send("builder", "*", "抵扣逻辑在 Discount.java，开干");
        List<TeamMailbox.Message> plannerInbox = team.mailbox().poll("planner");
        List<TeamMailbox.Message> builderInbox = team.mailbox().poll("builder");
        System.out.println("  planner 收到 " + plannerInbox.size() + " 封，builder 收到 "
                + builderInbox.size() + " 封");
        ok &= report("点对点投递到位", builderInbox.size() == 1);
        ok &= report("广播 * 全队收一份（发件人除外）",
                plannerInbox.size() == 1
                        && plannerInbox.get(0).body().contains("Discount.java"));
        long task = team.tasks().create("迁移券抵扣逻辑");
        ok &= report("builder 认领得手", team.tasks().claim(task, "builder"));
        ok &= report("planner 抢同一块活被原子拒绝", !team.tasks().claim(task, "planner"));
        ok &= report("谁认领谁完成：planner 替勾被拒",
                !team.tasks().complete(task, "planner"));
        ok &= report("builder 自己勾掉", team.tasks().complete(task, "builder"));
        System.out.println();

        // ── 第二幕：护栏
        System.out.println("── 第二幕：护栏");
        try {
            new Team("不该存在", List.of("planner", "explore"),
                    MAX_MEMBERS, MAX_PAYLOAD, MAX_MESSAGES, eligibility);
            ok &= report("只读专家混进 TeamSpec 被 parse 拒", false);
        } catch (IllegalArgumentException e) {
            System.out.println("  拒因：" + e.getMessage());
            ok &= report("只读专家混进 TeamSpec 被 parse 拒（理由指路）",
                    e.getMessage().contains("只读专家")
                            && e.getMessage().contains("task 委派"));
        }
        try {
            new Team("超员", List.of("a", "b", "c", "d", "e"),
                    MAX_MEMBERS, MAX_PAYLOAD, MAX_MESSAGES,
                    n -> Optional.empty());
            ok &= report("成员数超 maxMembers 被建队拒", false);
        } catch (IllegalArgumentException e) {
            ok &= report("成员数超 maxMembers 被建队拒", e.getMessage().contains("越界"));
        }
        ok &= report("队员再建队被拦入账（no nested teams）",
                !team.memberCreateTeam("builder", "子队")
                        && team.status().deniedNestedCreates() == 1);
        try {
            team.mailbox().send("builder", "planner", "长".repeat(MAX_PAYLOAD + 1));
            ok &= report("payload 超 cap 拒发", false);
        } catch (IllegalArgumentException e) {
            ok &= report("payload 超 cap 拒发", e.getMessage().contains("上限"));
        }
        // 已发 2 封，预算是 5：再发 3 封顶格，第 4 封该被拒
        for (int i = 0; i < 3; i++) {
            team.mailbox().send("planner", "builder", "进度同步 " + i);
        }
        try {
            team.mailbox().send("planner", "builder", "还在吗");
            ok &= report("消息预算耗尽拒发（防陪跑）", false);
        } catch (IllegalStateException e) {
            ok &= report("消息预算耗尽拒发（防陪跑）", e.getMessage().contains("预算"));
        }
        System.out.println();

        // ── 第三幕：生命周期与一页状态
        System.out.println("── 第三幕：生命周期与一页状态");
        ok &= report("非领班批收工无效",
                !team.approveShutdown("builder"));
        team.requestShutdown("builder");
        ok &= report("领班批准收工", team.approveShutdown("planner")
                && team.shutdownApproved());
        Team.Status status = team.status();
        System.out.println("  team_status：任务 " + status.pending() + " 挂 / "
                + status.claimed() + " 领 / " + status.done()
                + " 完，信箱 " + status.mailboxTotal() + " 封（余量 "
                + status.mailboxBudgetLeft() + "），被拦建队 "
                + status.deniedNestedCreates() + " 次");
        ok &= report("一页状态数字对得上", status.done() == 1
                && status.pending() == 0 && status.claimed() == 0
                && status.mailboxTotal() == 5 && status.mailboxBudgetLeft() == 0
                && status.deniedNestedCreates() == 1);

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：黑板通了、护栏响了、收工批了、一页状态对得上——差距清单清零。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
