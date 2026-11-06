package com.example.harness.multiagent.team;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 一支队：共享信箱 + 共享任务清单 + 一圈护栏（OmO Team Mode 研读落地，
 * 2026-11-06）。
 *
 * <p>OmO 的 team-mode 默认关、开了也要守一整车限额；它的硬约束
 * 有两条对我们最要紧——<b>准入在 parse 时拒不在运行时炸</b>（只读
 * 专家硬拒，理由指路 task 委派）、<b>队员不许再建队</b>（no nested
 * teams，靠 tool-gating 拦）。本类是单进程最小内核：</p>
 * <ul>
 *   <li>建队即校验：成员数 ≤ maxMembers、每人过 eligibility
 *       （接 {@code ExpertRegistry} 的判词，只读专家进不了队）；</li>
 *   <li>{@link #memberCreateTeam} 只拦不执行，记进台账——队员喊
 *       「再拉一支队」是绕圈前兆；</li>
 *   <li>{@link #status()} 一页汇总：成员、任务三态、信箱流量、
 *       预算余量、被拦的建队企图。</li>
 * </ul>
 */
public final class Team {

    /** 一页状态汇总（team_status 的返回体）。 */
    public record Status(long pending, long claimed, long done,
                         int mailboxTotal, int mailboxBudgetLeft,
                         int deniedNestedCreates) {
    }

    private final String name;
    private final String lead;
    private final Set<String> members;
    private final TeamMailbox mailbox;
    private final SharedTaskList tasks = new SharedTaskList();
    private int deniedNestedCreates;
    private boolean shutdownRequested;
    private boolean shutdownApproved;

    /**
     * 建队（parse 时全部校验，不过就抛——不是运行时再炸）。
     *
     * @param eligibility 成员准入判官：返回空 = 合格，非空 = 拒因
     * @throws IllegalArgumentException 超员或有人不合格
     */
    public Team(String name, List<String> members, int maxMembers,
                int maxPayloadBytes, int maxMessagesPerRun,
                Function<String, Optional<String>> eligibility) {
        if (members.isEmpty() || members.size() > maxMembers) {
            throw new IllegalArgumentException("成员数 " + members.size()
                    + " 越界（1~" + maxMembers + "）");
        }
        for (String m : members) {
            Optional<String> rejection = eligibility.apply(m);
            if (rejection.isPresent()) {
                throw new IllegalArgumentException("建队被拒：" + rejection.get());
            }
        }
        this.name = name;
        this.lead = members.get(0);
        this.members = Set.copyOf(members);
        this.mailbox = new TeamMailbox(this.members, maxPayloadBytes, maxMessagesPerRun);
    }

    public String name() {
        return name;
    }

    public String lead() {
        return lead;
    }

    public Set<String> members() {
        return members;
    }

    public TeamMailbox mailbox() {
        return mailbox;
    }

    public SharedTaskList tasks() {
        return tasks;
    }

    /**
     * 队员请求再建一支队——no nested teams：只拦不执行，记账。
     *
     * @return 永远 false（这扇门不存在）
     */
    public boolean memberCreateTeam(String member, String newTeamName) {
        deniedNestedCreates++;
        return false;
    }

    /** 队员请示收工；领班没批之前活还得接着干。 */
    public void requestShutdown(String member) {
        if (!members.contains(member)) {
            throw new IllegalArgumentException("队里没有这个成员：" + member);
        }
        shutdownRequested = true;
    }

    /** 只有领班能批收工。 */
    public boolean approveShutdown(String approver) {
        if (!approver.equals(lead)) {
            return false;
        }
        if (!shutdownRequested) {
            throw new IllegalStateException("没人请示收工，批什么？");
        }
        shutdownApproved = true;
        return true;
    }

    public boolean shutdownApproved() {
        return shutdownApproved;
    }

    /** 全队一页状态。 */
    public Status status() {
        long pending = 0;
        long claimed = 0;
        long done = 0;
        for (SharedTaskList.Task t : tasks.list()) {
            switch (t.status()) {
                case PENDING -> pending++;
                case CLAIMED -> claimed++;
                case DONE -> done++;
                default -> throw new IllegalStateException("未知状态：" + t.status());
            }
        }
        return new Status(pending, claimed, done,
                mailbox.totalSent(),
                mailboxBudgetLeft(),
                deniedNestedCreates);
    }

    private int mailboxBudgetLeft() {
        // TeamMailbox 的预算是构造期钉死的，这里用总量反推：
        // status 只看「还剩多少」，由 mailbox 自己报。
        return mailbox.budgetLeft();
    }
}
