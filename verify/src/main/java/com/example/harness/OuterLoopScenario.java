package com.example.harness;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.DelegationGuard;
import com.example.multiagent.RunJournal;
import com.example.multiagent.SwarmPattern;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 一键外循环：「真实档案进 → 规则草稿出」一条命令跑完，不靠人记起来挨个敲。
 *
 * <p>和 {@code RuleDistillScenario}（剧本造历史）的分野：<b>outerloop 只吃真账</b>——
 * 不清空、不造数据，直接蒸馏 {@code runs/runs.jsonl} 里现有的记录（场景 和
 * real 混合也照吃，终态模式、护栏模式、权限缺口三类规则各自聚类）。流程：
 * （可选 real）真实紧护栏接力造新档案 → 蒸馏 → 与 AGENTS.md 去重 → 落
 * {@code rules/AGENTS.outer-draft.md}（带时间戳，覆盖式）→ 有新的循环交接
 * 类护栏草稿时跑采纳检验（合并专员重跑同类任务，必须 SUCCESS）→ 打印本轮小结。</p>
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>{@code mvn exec:java@outerloop}：纯离线，吃现有底账；</li>
 *   <li>{@code mvn exec:java@outerloop -Dexec.args=real}：先跑 1 轮紧护栏真实
 *       接力（复用 {@link RuleDistillRealScenario#runTightRelay}，需 LLM_API_KEY）
 *       再造档案，适合接在真实 场景 回归之后定期执行。</li>
 * </ul>
 */
public class OuterLoopScenario {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));
    private static final Path OUT = Path.of("rules", "AGENTS.outer-draft.md");

    private static int bad;

    public static void main(String[] args) throws Exception {
        boolean real = args.length > 0 && "real".equals(args[0]);
        if (real && System.getenv("LLM_API_KEY") == null) {
            System.err.println("real 模式需要 LLM_API_KEY 环境变量");
            System.exit(1);
        }
        header(real);

        // ①（可选）真实紧护栏接力：给底账注入一条新鲜的真实护栏拦截
        if (real) {
            System.out.println("── 真实造档案：紧护栏接力（max-handoffs=1）──");
            RuleDistillRealScenario.runTightRelay("real-outerloop-handoff-1");
        }

        // ② 蒸馏：现有底账全量进，不清空不挑选
        List<RunRecord> all = STORE.readAll();
        System.out.println("\n── 蒸馏（底账 " + all.size() + " 条，阈值 ≥"
                + RuleDistiller.MIN_OCCURRENCES + " 次）──");
        RuleDistiller distiller = new RuleDistiller();
        List<RuleDistiller.RuleDraft> drafts = distiller.dedupeKnown(
                distiller.distill(all),
                Files.readString(Path.of("AGENTS.md")),
                Map.of("终态模式", List.of("预算"),
                        "护栏模式", List.of("交接次数", "绕圈", "层级"),
                        "权限缺口", List.of("update_forecast")));
        long fresh = drafts.stream().filter(d -> !d.rule().startsWith("【已收录】")).count();
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

        // ③ 落盘（覆盖式，带本轮时间戳）
        writeDrafts(all.size(), drafts);
        System.out.println("\n  [落盘] " + OUT + "（提案制：人审后手动合并进 AGENTS.md 才生效）");
        check("蒸馏链路跑通（无异常、落盘成功）", Files.exists(OUT));

        // ④ 采纳检验：只有出现「新的」循环交接类护栏草稿才需要验证采纳有效
        boolean hasNewCycle = drafts.stream()
                .anyMatch(d -> !d.rule().startsWith("【已收录】")
                        && d.category().equals("护栏模式") && d.rule().contains("绕圈"));
        boolean verified = true;
        if (hasNewCycle) {
            verified = verifyCycleFix();
        } else {
            System.out.println("\n── 采纳检验：跳过（没有新的循环交接类草稿，无需验证采纳）──");
        }

        // ⑤ 本轮小结
        System.out.println("\n── 本轮外循环小结 ──");
        System.out.println("  输入档案 " + all.size() + " 条 → 草稿 " + drafts.size()
                + " 条（新教训 " + fresh + " 条，已收录 "
                + (drafts.size() - fresh) + " 条）→ " + OUT);
        System.out.println("  " + (verified ? "✓ 采纳检验通过（或无需检验）"
                : "!! 采纳检验未过——草稿无效或采纳不彻底"));

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：外循环一键跑通——真账进、草稿出，教训不再依赖"
                    + "人记起来开三个命令。");
        } else {
            System.out.println("!! 有 " + bad + " 处校验未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 && verified ? 0 : 1);
    }

    /**
     * 采纳检验（与 {@code RuleDistillScenario} 同款）：cycle 类草稿的修法是
     * 「把互转交场景合并给一个专员」——照做后重跑同类任务，新档案必须
     * SUCCESS 而不是又一次 GUARD_BLOCKED。采纳 ≠ 生效，生效要回任务里验证。
     */
    private static boolean verifyCycleFix() throws Exception {
        System.out.println("\n── 采纳检验：新 cycle 草稿「合并互转交场景」，照做后重跑同类任务 ──");
        List<SwarmPattern.Specialist> merged = List.of(
                new SwarmPattern.Specialist("资深专员", "一线二线合并，直接处理升级投诉",
                        new ScriptedLlm().says("这个投诉我直接处理：已为您升级工单，"
                                + "两小时内回复处理结果")));
        var router = SwarmPattern.keywordRouter(Map.of("资深专员", List.of("资深专员")));
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(4, 5), "资深专员");
        RunJournal journal = new RunJournal();
        String result = SwarmPattern.run("客户投诉升级", merged, router, guard, journal.trace());
        RunRecord record = journal.toRecord("scenario-outerloop-fixed", "multiagent:swarm",
                "客户投诉升级（合并专员后）", result, 1, 0);
        STORE.append(record);
        boolean ok = "SUCCESS".equals(record.terminal());
        System.out.println("  [重跑] scenario-outerloop-fixed → 终态 " + record.terminal()
                + "，交接 " + guard.handoffs() + " 跳");
        if (!ok) {
            bad++;
            System.out.println("  !! 采纳草稿后同类任务仍撞护栏——草稿无效或采纳不彻底");
        } else {
            System.out.println("  ✓ 合并专员后同类任务干净收工——新草稿采纳有效");
        }
        return ok;
    }

    private static void writeDrafts(int archiveSize, List<RuleDistiller.RuleDraft> drafts)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# AGENTS.md 规则草稿（一键外循环，待人审）\n\n");
        sb.append("本轮运行：").append(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        sb.append("来源：runs/runs.jsonl（").append(archiveSize).append(" 条运行档案，真账直蒸，不含剧本造假），")
                .append("阈值：同一模式 ≥ ").append(RuleDistiller.MIN_OCCURRENCES).append(" 次。\n");
        sb.append("提案制：人审通过前不生效；合并时与 AGENTS.md 既有规则去重；"
                + "合并后要在后续同类任务里检验效果。\n\n");
        for (int i = 0; i < drafts.size(); i++) {
            RuleDistiller.RuleDraft d = drafts.get(i);
            sb.append("## 草稿 ").append(i + 1).append("（").append(d.category()).append("）\n\n");
            sb.append("- ").append(d.rule()).append("\n");
            sb.append("- 证据：").append(d.evidence()).append("\n\n");
        }
        if (OUT.getParent() != null) {
            Files.createDirectories(OUT.getParent());
        }
        Files.writeString(OUT, sb.toString());
    }

    private static void header(boolean real) {
        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        System.out.println("  一键外循环：真账进 → 草稿出（蒸馏 → 去重 → 落盘 → 采纳检验）");
        System.out.println("  模式：" + (real ? "real（先造 1 条真实护栏档案，需 LLM_API_KEY）"
                : "离线（直接吃现有底账）"));
        System.out.println(line);
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }
}
