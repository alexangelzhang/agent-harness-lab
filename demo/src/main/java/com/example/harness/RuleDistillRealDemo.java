package com.example.harness;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.multiagent.DelegationGuard;
import com.example.multiagent.RunJournal;
import com.example.multiagent.SwarmPattern;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 外循环真实版：蒸馏<b>真实模型运行</b>出来的规则（{@code RuleDistillDemo}
 * 剧本版的真实对照组，2026-11-01）。
 *
 * <p>剧本版造历史证明「蒸馏机制对」；这个 demo 证明「真实运行撞的护栏，
 * 档案里一样蒸得出规则」——用紧护栏（max-handoffs=1）让真实 真实模型客服
 * 接力真实触发 max-handoffs 拦截 ×2，连同底账里已有的真实运行一起进
 * 蒸馏输入，去重后落 {@code rules/AGENTS.real-draft.md}。硬断言：必须
 * 蒸出至少一条新的护栏模式草稿，否则退出码 1。</p>
 *
 * <p>运行：{@code mvn exec:java@distillreal}，需要 {@code LLM_API_KEY}。
 * 不清空底账——真实档案是攒出来的，只读 {@code real-*} 前缀的记录做蒸馏。</p>
 */
public class RuleDistillRealDemo {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    public static void main(String[] args) throws Exception {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  外循环真实版 —— 真实运行撞的护栏，蒸得出规则");
        System.out.println("=".repeat(66));

        // ── 底账现状：已有的真实运行 ──
        List<RunRecord> existing = realRuns();
        System.out.println("  底账已有真实运行 " + existing.size() + " 条："
                + terminalProfile(existing));

        // ── 真实触发：紧护栏（max-handoffs=1）下 真实模型接力必然顶到交接上限 ──
        for (int i = 1; i <= 2; i++) {
            runTightRelay("real-distill-handoff-" + i);
        }

        // ── 蒸馏：全部真实档案 → 规则草稿 → 与 AGENTS.md 去重 ──
        List<RunRecord> realRuns = realRuns();
        System.out.println("\n── 蒸馏（真实档案 " + realRuns.size() + " 条，阈值 ≥"
                + RuleDistiller.MIN_OCCURRENCES + " 次）──");
        RuleDistiller distiller = new RuleDistiller();
        List<RuleDistiller.RuleDraft> drafts = distiller.dedupeKnown(
                distiller.distill(realRuns),
                Files.readString(Path.of("AGENTS.md")),
                Map.of("终态模式", List.of("预算"),
                        "护栏模式", List.of("交接次数", "绕圈", "层级"),
                        "权限缺口", List.of("update_forecast")));
        for (int i = 0; i < drafts.size(); i++) {
            RuleDistiller.RuleDraft d = drafts.get(i);
            System.out.println("\n  草稿 " + (i + 1) + " [" + d.category() + "]"
                    + (d.rule().startsWith("【已收录】") ? "（已知，合并时跳过）" : "（新教训）"));
            System.out.println("    规则：" + d.rule());
            System.out.println("    证据：" + d.evidence());
        }

        // ── 落盘真实版草稿 + 硬断言 ──
        Path out = Path.of("rules", "AGENTS.real-draft.md");
        writeDrafts(out, realRuns.size(), drafts);
        System.out.println("\n  [落盘] " + out + "（提案制：人审后手动合并）");
        // 断言看「蒸得出」而非「新教训」——教训合并进 AGENTS.md 后会被去重
        // 标成已收录，但真实运行撞出的模式仍必须在草稿里显形
        boolean ok = drafts.stream().anyMatch(d -> d.category().equals("护栏模式")
                && d.rule().contains("交接次数反复到顶"));
        long known = drafts.stream().filter(d -> d.rule().startsWith("【已收录】")).count();
        System.out.println(ok
                ? "  断言过：真实撞出的 max-handoffs 蒸成了规则草稿（其中 " + known
                        + " 条已收录进 AGENTS.md，去重在岗）。"
                : "  !! 真实运行撞了护栏却没蒸出草稿——蒸馏链断了");
        System.exit(ok ? 0 : 1);
    }

    /** 紧护栏接力：maxHandoffs=1，第一跳之后任何再交接都被 max-handoffs 拦。
     *  public static 供 {@code OuterLoopDemo}（一键外循环）的 real 模式复用。 */
    public static void runTightRelay(String runId) throws Exception {
        CountingLlm real = new CountingLlm(new JdkHttpLlm());
        List<SwarmPattern.Specialist> roster = List.of(
                new SwarmPattern.Specialist("接入专员", "接待与分流",
                        roleBrain(real, "接入客服",
                                "你查不了物流单号；用户的诉求属于物流/快递问题，"
                                        + "回复里带上「物流」并说明转物流同事。")),
                new SwarmPattern.Specialist("物流专员", "查件与理赔",
                        roleBrain(real, "物流专员",
                                "核实后如果件丢了该走退款，回复里带上「退款」"
                                        + "并说明转退款同事。")),
                new SwarmPattern.Specialist("退款专员", "退款流程",
                        roleBrain(real, "退款专员",
                                "如果需要财务打款，回复里带上「财务」"
                                        + "并说明转财务同事。")),
                new SwarmPattern.Specialist("财务专员", "打款到账，收口",
                        roleBrain(real, "财务专员（最后一棒，收口）",
                                "打款已完成，直接告知用户钱已原路退回、留意查收即可——"
                                        + "不要提转交，回复里也不要出现「退款」「物流」"
                                        + "「快递」「包裹」这些词。")));
        var router = SwarmPattern.keywordRouter(Map.of(
                "物流专员", List.of("物流", "快递", "包裹"),
                "退款专员", List.of("退款"),
                "财务专员", List.of("财务", "打款")));
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(2, 1), "接入专员");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SwarmPattern.run("我的包裹三天没动了", roster, router, guard, journal.trace());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        RunRecord record = journal.toRecord(runId, "multiagent:swarm",
                "我的包裹三天没动了（紧护栏真实接力）", out, real.calls(), ms);
        STORE.append(record);
        System.out.println("  [真实] " + runId + " → 终态 " + record.terminal()
                + "，交接 " + guard.handoffs() + " 跳，LLM " + real.calls() + " 次"
                + (out.contains("max-handoffs") ? "（max-handoffs 拦下）" : ""));
    }

    /** 底账里 real- 前缀的记录（topologyreal/supervisorreal/distillreal 落的）。 */
    private static List<RunRecord> realRuns() throws Exception {
        return STORE.readAll().stream()
                .filter(r -> r.runId().startsWith("real-"))
                .toList();
    }

    private static String terminalProfile(List<RunRecord> runs) {
        if (runs.isEmpty()) {
            return "（还没有，全靠本次现跑）";
        }
        return runs.stream().collect(Collectors.groupingBy(RunRecord::terminal,
                        TreeMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static LlmClient roleBrain(LlmClient real, String role, String hint) {
        return prompt -> real.complete("你是客服团队里的" + role + "。" + hint
                + "\n用户/上一棒说：" + prompt + "\n直接给出你的回复正文，不要解释。");
    }

    private static void writeDrafts(Path out, int archiveSize,
                                    List<RuleDistiller.RuleDraft> drafts) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# AGENTS.md 规则草稿（外循环蒸馏·真实运行版，待人审）\n\n");
        sb.append("来源：runs/runs.jsonl 中 real- 前缀的真实运行档案（").append(archiveSize)
                .append(" 条），阈值 ≥ ").append(RuleDistiller.MIN_OCCURRENCES).append(" 次。\n");
        sb.append("提案制：人审通过前不生效；合并时与 AGENTS.md 既有规则去重。\n\n");
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
