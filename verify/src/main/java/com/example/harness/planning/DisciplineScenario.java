package com.example.harness.planning;

import com.example.agent.llm.ScriptedLlm;

/**
 * 纪律层验证（OmO 研读落地，见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>幕一「Todo 拽回」：清单没勾完而整轮无进展就注入拽回提示，
 * 连续空转升级语气，到上限闭嘴转人工；一有进展计数清零。</p>
 *
 * <p>幕二「Goal 完成审计」：agent 自称干完了不算数，裁判模型对着
 * 目标审证据；审不过注入续跑提示（带上限），审过才放行收工；
 * 审计员故障按未达成处理，不放行没审过的收工。</p>
 *
 * <p>运行：{@code mvn exec:java@discipline -pl verify}（根目录）。</p>
 */
public class DisciplineScenario {

    private static int failed;

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("纪律层：Todo 拽回 + Goal 完成审计");
        System.out.println(line);
        todoAct();
        goalAct();
        System.out.println();
        System.out.println(line);
        if (failed > 0) {
            System.out.println("!! " + failed + " 项断言未过");
            System.exit(1);
        }
        System.out.println("全部断言通过：空转被拽回、收工要过审计、审计员故障不放行。");
        System.out.println(line);
        System.exit(0);
    }

    /** 幕一：TodoEnforcer。 */
    private static void todoAct() {
        header("幕一：Todo 拽回", "清单是合同，空转被拽回");
        TodoList todo = new TodoList()
                .add("读现有代码")
                .add("写实现")
                .add("补验证");
        TodoEnforcer enforcer = new TodoEnforcer(todo, 3);

        System.out.println("【第 1 轮】模型闲聊了一轮，清单一项没动：");
        String nag1 = enforcer.onTurnEnd();
        check("空转一轮 → 拽回提示", nag1 != null && nag1.contains("还剩 3 项"));
        System.out.println("  注入：" + nag1);

        System.out.println("【第 2 轮】继续空转：");
        String nag2 = enforcer.onTurnEnd();
        check("连续空转 → 语气升级", nag2 != null && nag2.contains("连续 2 轮"));

        System.out.println("【第 3 轮】终于勾掉一项：");
        todo.check("读现有代码");
        enforcer.noteProgress();
        check("有进展 → 计数清零", enforcer.consecutiveDrift() == 0);
        String afterProgress = enforcer.onTurnEnd();
        check("清零后再次空转 → 回到提醒级",
                afterProgress != null && afterProgress.contains("还剩 2 项")
                        && !afterProgress.contains("最后通牒"));

        System.out.println("【第 4~6 轮】连续空转到上限：");
        enforcer.onTurnEnd(); // drift 2
        String last = enforcer.onTurnEnd(); // drift 3 = maxNags
        check("到上限 → 最后通牒", last != null && last.contains("最后通牒"));
        String beyond = enforcer.onTurnEnd(); // drift 4 > maxNags
        check("超上限 → 闭嘴，标记任务卡死", beyond == null && enforcer.givenUp());
        System.out.println("  累计拽回 " + enforcer.totalNags() + " 次后转人工。");

        System.out.println("【勾完】剩余两项勾掉：");
        todo.check("写实现");
        todo.check("补验证");
        enforcer.noteProgress();
        check("清单勾完 → 不再拽", enforcer.onTurnEnd() == null && !enforcer.givenUp());
    }

    /** 幕二：GoalAudit。 */
    private static void goalAct() {
        header("幕二：Goal 完成审计", "收工要过审计，自称不算数");
        ScriptedLlm auditor = new ScriptedLlm()
                .says("NOT_DONE: 报表生成了但没发出去，收件人没有收到记录")
                .says("DONE: 发送回执在案，目标达成");
        GoalAudit goal = new GoalAudit("把上周的稳定性报表发到值班群",
                auditor, 3);

        System.out.println("【第一次收工】agent：我干完了（证据：报表文件已生成）");
        String cont1 = goal.onAttemptFinish("报表文件 report.pdf 已生成");
        check("审计不过 → 注入续跑提示",
                cont1 != null && cont1.contains("没发出去"));
        System.out.println("  注入：" + cont1.lines().findFirst().orElse(""));

        System.out.println("【第二次收工】agent：这次真发出去了（证据：发送回执）");
        String cont2 = goal.onAttemptFinish("报表文件已生成，发送回执 msg-8812 在案");
        check("审计通过 → 放行收工", cont2 == null && goal.achieved());
        System.out.println("  审计意见：" + goal.lastVerdict().reason());

        System.out.println();
        System.out.println("【死磕场景】审计员永不满意，续跑到上限：");
        ScriptedLlm stubborn = new ScriptedLlm()
                .says("NOT_DONE: 还差一点", "NOT_DONE: 还差一点", "NOT_DONE: 还差一点");
        GoalAudit endless = new GoalAudit("不可能完成的目标", stubborn, 2);
        String c1 = endless.onAttemptFinish("尝试 1");
        String c2 = endless.onAttemptFinish("尝试 2");
        String c3 = endless.onAttemptFinish("尝试 3");
        check("上限内续跑", c1 != null && c2 != null);
        check("上限耗尽 → 放行但记着未竟", c3 == null && !endless.achieved());
        System.out.println("  未竟说明：" + endless.lastVerdict().reason());

        System.out.println();
        System.out.println("【审计员故障】调用抛异常：");
        GoalAudit broken = new GoalAudit("任意目标", new ScriptedLlm(), 1);
        String cb = broken.onAttemptFinish("证据");
        check("审计员故障 → 按未达成处理，不放行",
                cb != null && cb.contains("审计员暂不可用") && !broken.achieved());
        check("故障轮不占续跑预算", broken.continuations() == 0
                && broken.auditorFailures() == 1);
        String cb2 = broken.onAttemptFinish("证据");
        check("故障也超上限 → 放行但记着没审过",
                cb2 == null && !broken.achieved()
                        && broken.lastVerdict().reason().contains("审计员不可用"));
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        if (!ok) {
            failed++;
        }
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
