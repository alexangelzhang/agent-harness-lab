package com.example.harness;

import com.example.agentloop.AgentLoop;
import com.example.agentloop.LlmActor;
import com.example.agentloop.LlmVerifier;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.DelegationGuard;
import com.example.multiagent.HierarchicalPattern;
import com.example.multiagent.RunJournal;
import com.example.multiagent.SwarmPattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 外循环演示：先造历史（同一权限缺口反复撞墙 3 次、预算反复耗尽 2 次、
 * 循环交接被掐 2 次、超深委派被拦 2 次），再从档案蒸馏规则草稿写入
 * rules/AGENTS.draft.md——人审后才手动合并进 AGENTS.md（提案制）。
 * 最后走第三步「检验」：照 cycle 草稿合并互转交专员，重跑同类任务，
 * 断言不再撞护栏（采纳 ≠ 生效，生效要回同类任务里验证）。
 * 对应 Meta「重复两次写进永久规则」+ Warp 改进 Skill。
 * 运行入口：{@code mvn exec:java@distill}。
 */
public class RuleDistillScenario {

    private static final String TASK = "查一下 Acme 的客户记录，并更新本季度销售预测";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  外循环 —— 从运行档案蒸馏规则草稿（人审后生效）");
        System.out.println("=".repeat(66));

        RunStore store = new RunStore(Path.of("runs", "runs.jsonl"));
        store.clear();

        // ── 造历史：专员更新预测撞墙 3 次（同一权限缺口）──
        for (int i = 1; i <= 3; i++) {
            runSpecialistDenied(store, "scenario-distill-deny-" + i);
        }
        // ── 造历史：验收永远 NO，预算耗尽 2 次 ──
        for (int i = 1; i <= 2; i++) {
            runBudgetExhausted(store, "scenario-distill-budget-" + i);
        }
        // ── 造历史：客服 A↔B 互踢皮球被循环检测掐 2 次 ──
        for (int i = 1; i <= 2; i++) {
            runSwarmCycle(store, "scenario-distill-cycle-" + i);
        }
        // ── 造历史：交付层级套娃顶到 max-depth 2 次 ──
        for (int i = 1; i <= 2; i++) {
            runHierarchicalDeep(store, "scenario-distill-deep-" + i);
        }
        // 一次正常达标运行（不应被蒸馏误伤）
        runSuccess(store, "scenario-distill-ok");

        // ── 蒸馏：档案 → 规则草稿 ──
        List<RunRecord> all = store.readAll();
        System.out.println("\n── 蒸馏（共 " + all.size() + " 条档案，阈值 ≥"
                + RuleDistiller.MIN_OCCURRENCES + " 次）──");
        List<RuleDistiller.RuleDraft> drafts = new RuleDistiller().distill(all);

        // ── 与 AGENTS.md 既有规则去重：同一教训别存两份 ──
        String agentsMd = Files.readString(Path.of("AGENTS.md"));
        drafts = new RuleDistiller().dedupeKnown(drafts, agentsMd, Map.of(
                "终态模式", List.of("预算"),
                "护栏模式", List.of("绕圈", "层级"),
                "权限缺口", List.of("update_forecast")));
        for (int i = 0; i < drafts.size(); i++) {
            RuleDistiller.RuleDraft d = drafts.get(i);
            System.out.println("\n  草稿 " + (i + 1) + " [" + d.category() + "]"
                    + (d.rule().startsWith("【已收录】") ? "（已知，合并时跳过）" : "（新教训）"));
            System.out.println("    规则：" + d.rule());
            System.out.println("    证据：" + d.evidence());
        }
        if (drafts.isEmpty()) {
            System.out.println("  （无草稿——失败模式还不够重复，这正是阈值的意义）");
        }

        // ── 落盘为 AGENTS.md 草稿，等人审 ──
        Path out = Path.of("rules", "AGENTS.draft.md");
        writeDrafts(out, all.size(), drafts);
        System.out.println("\n  [落盘] " + out + "（提案制：人审后手动合并进 AGENTS.md 才生效）");

        // ── 采纳检验：照 cycle 草稿合并互转交场景，同类任务应不再撞护栏 ──
        boolean verified = verifyCycleFix(store);

        System.out.println("""
                
                  结论：内循环的记录成了外循环的原料——单体撞墙（越权/预算）
                  和多 Agent 撞护栏（循环/超深）同账蒸馏；规则从档案里长出来，
                  采纳后回同类任务验证：合并专员前 2 次 GUARD_BLOCKED，
                  合并后同任务干净收工——采纳 → 生效 → 检验，三步走通。
                """);
        System.exit(verified ? 0 : 1);
    }

    /**
     * 采纳检验：cycle 草稿的修法是「把互转交场景合并给一个专员」——
     * 照做（专员A/B 合并成资深专员，直接处理升级投诉），重跑同一任务，
     * 新档案必须是 SUCCESS 而不是第三次 GUARD_BLOCKED。
     */
    private static boolean verifyCycleFix(RunStore store) throws Exception {
        System.out.println("\n── 采纳检验：cycle 草稿「合并互转交场景」，照做后重跑同类任务 ──");
        List<SwarmPattern.Specialist> merged = List.of(
                new SwarmPattern.Specialist("资深专员", "一线二线合并，直接处理升级投诉",
                        new ScriptedLlm().says("这个投诉我直接处理：已为您升级工单，"
                                + "两小时内回复处理结果")));
        var router = SwarmPattern.keywordRouter(Map.of("资深专员", List.of("资深专员")));
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(4, 5), "资深专员");
        RunJournal journal = new RunJournal();
        String result = SwarmPattern.run("客户投诉升级", merged, router, guard, journal.trace());
        RunRecord record = journal.toRecord("scenario-distill-cycle-fixed", "multiagent:swarm",
                "客户投诉升级（合并专员后）", result, 1, 0);
        store.append(record);
        boolean ok = "SUCCESS".equals(record.terminal());
        System.out.println("  [重跑] scenario-distill-cycle-fixed → 终态 " + record.terminal()
                + "，交接 " + guard.handoffs() + " 跳");
        System.out.println("  对照：合并前 scenario-distill-cycle-1/2 两次 GUARD_BLOCKED；"
                + "合并后同一任务 " + (ok ? "干净收工——草稿采纳有效。" : "仍被拦！！"));
        if (!ok) {
            System.out.println("  !! 采纳草稿后同类任务仍撞护栏——草稿无效或采纳不彻底");
        }
        return ok;
    }

    private static void runSpecialistDenied(RunStore store, String runId) throws Exception {
        ToolGate gate = new ToolGate("专员", ToolGate.Level.READ);
        var verifierLlm = new CountingLlm(new ScriptedLlm().says("YES"));
        var r = new AgentLoop(salesAgent(gate), new LlmVerifier(verifierLlm,
                "汇报里要有客户情况，以及预测的处理结果"), 3).run(TASK);
        store.append(RunRecord.ofLoop(runId, TASK, r, verifierLlm.calls(), 0));
        System.out.println("  [历史] " + runId + " → " + r.stopReason());
    }

    private static void runBudgetExhausted(RunStore store, String runId) throws Exception {
        var shared = new CountingLlm(new ScriptedLlm()
                .says("一口下去，精神百倍，加班不犯困！")
                .says("NO：没有体现低温萃取")
                .says("低温冷萃，锁住咖啡本味。")
                .says("NO：没有体现慢慢萃"));
        var r = new AgentLoop(new LlmActor(shared),
                new LlmVerifier(shared, "广告词要体现低温萃取和慢慢萃"), 10,
                Budget.calls(shared::calls, 4)).run("为一款冷萃咖啡液写一句广告 slogan");
        store.append(RunRecord.ofLoop(runId, "写广告 slogan", r, shared.calls(), 0));
        System.out.println("  [历史] " + runId + " → " + r.stopReason());
    }

    private static void runSuccess(RunStore store, String runId) throws Exception {
        var actorLlm = new CountingLlm(new ScriptedLlm()
                .says("十二小时低温慢萃，把时间的味道融进每一滴。"));
        var verifierLlm = new CountingLlm(new ScriptedLlm().says("YES"));
        var r = new AgentLoop(new LlmActor(actorLlm),
                new LlmVerifier(verifierLlm, "体现低温萃取和慢慢萃"), 3).run("写广告 slogan");
        store.append(RunRecord.ofLoop(runId, "写广告 slogan", r,
                actorLlm.calls() + verifierLlm.calls(), 0));
        System.out.println("  [历史] " + runId + " → " + r.stopReason());
    }

    /** 历史：Swarm 循环交接（专员A→B→A），被护栏的循环检测拦下。 */
    private static void runSwarmCycle(RunStore store, String runId) throws Exception {
        List<SwarmPattern.Specialist> duo = List.of(
                new SwarmPattern.Specialist("专员A", "一线", new ScriptedLlm()
                        .says("这个问题我处理不了，需要专员B看看")),
                new SwarmPattern.Specialist("专员B", "二线", new ScriptedLlm()
                        .says("这个我也定不了，还是得专员A来")));
        var router = SwarmPattern.keywordRouter(Map.of(
                "专员A", List.of("专员A"),
                "专员B", List.of("专员B")));
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(4, 5), "专员A");
        RunJournal journal = new RunJournal();
        String out = SwarmPattern.run("客户投诉升级", duo, router, guard, journal.trace());
        store.append(journal.toRecord(runId, "multiagent:swarm", "客户投诉升级", out, 2, 0));
        System.out.println("  [历史] " + runId + " → 护栏拦下：" + out.lines().findFirst().orElse(""));
    }

    /** 历史：Hierarchical 3 层套娃顶到 max-depth，被拦下。 */
    private static void runHierarchicalDeep(RunStore store, String runId) throws Exception {
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(2, 10), "总监");
        List<HierarchicalPattern.Level> levels = List.of(
                new HierarchicalPattern.Level("总监", t -> new ScriptedLlm()
                        .says("委派：任务A").complete(t)),
                new HierarchicalPattern.Level("组长", t -> new ScriptedLlm()
                        .says("委派：任务B").complete(t)),
                new HierarchicalPattern.Level("工程师", t -> new ScriptedLlm()
                        .says("完成：做完了").complete(t)));
        RunJournal journal = new RunJournal();
        String out = HierarchicalPattern.run("交付新功能", levels, guard, journal.trace());
        store.append(journal.toRecord(runId, "multiagent:hierarchical", "交付新功能", out, 2, 0));
        System.out.println("  [历史] " + runId + " → 护栏拦下：" + out.lines().findFirst().orElse(""));
    }

    /** 剧本销售助手：读 CRM（READ 放行）→ 更新预测（WRITE 被拦）→ 汇总。 */
    private static AgentLoop.Actor salesAgent(ToolGate gate) {
        return ctx -> {
            String crm = gate.callUnchecked("read_crm", ToolGate.Level.READ,
                    "CRM 记录：Acme 上季度续约率 80%");
            String upd = gate.callUnchecked("update_forecast", ToolGate.Level.WRITE,
                    "预测已更新");
            return crm + "；" + (upd.startsWith("DENIED")
                    ? upd + "。改提建议：请经理确认后手动更新预测。" : upd);
        };
    }

    private static void writeDrafts(Path out, int archiveSize,
                                    List<RuleDistiller.RuleDraft> drafts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# AGENTS.md 规则草稿（外循环蒸馏，待人审）\n\n");
        sb.append("来源：runs/runs.jsonl（").append(archiveSize).append(" 条运行档案），")
                .append("阈值：同一模式 ≥ ").append(RuleDistiller.MIN_OCCURRENCES).append(" 次。\n");
        sb.append("提案制：人审通过前不生效；合并后要在后续同类任务里检验效果。\n\n");
        for (int i = 0; i < drafts.size(); i++) {
            RuleDistiller.RuleDraft d = drafts.get(i);
            sb.append("## 草稿 ").append(i + 1).append("（").append(d.category()).append("）\n\n");
            sb.append("- ").append(d.rule()).append("\n");
            sb.append("- 证据：").append(d.evidence()).append("\n\n");
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, sb.toString());
    }
}
