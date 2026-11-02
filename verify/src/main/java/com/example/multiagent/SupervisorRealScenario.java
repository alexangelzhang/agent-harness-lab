package com.example.multiagent;

import com.example.harness.RunRecord;
import com.example.harness.RunStore;
import com.example.harness.probe.ProtocolProbe;
import com.example.harness.toolbase.SchemaTool;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.SupervisorPattern.Expert;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Supervisor 真实模型对照组（{@code TopologyScenario} 剧本版的 Supervisor
 * 专项真实版，2026-10-31）。
 *
 * <p>剧本版证明「分派协议 / CALL 协议机制对」；这个场景 证明「真实模型
 * 当主管，吐出来的行还认得出、派得动」。硬断言只校结构：分派行命中
 * 花名册（没有「跳过不认识的专家」）、综合非空、调用数合理，措辞不断言。</p>
 *
 * <p>每次分派都经 {@link RunJournal} 追加进 {@code runs/runs.jsonl}——
 * 与单体 loop、剧本版多 Agent 运行同一张审计底账。</p>
 *
 * <p>三个场景（真实调用慢，{@code -Dexec.args="n"} 只跑第 n 个）：</p>
 * <ol>
 *   <li><b>真实主管 · 平级分派</b>：真实模型主管输出「分派：」行，
 *       剧本专家接活，trace 逐条校花名册一致性；</li>
 *   <li><b>真实主管 · Supervisor-as-Tools</b>：专家包成 SchemaTool，
 *       真实模型主管输出「CALL 工具：问题」行，校工具协议一致性；</li>
 *   <li><b>全真实矩阵</b>：真实模型主管 + 三位 真实模型专家 + 真实模型综合，
 *       一屏看全真实分派的调用账。</li>
 * </ol>
 *
 * <p>运行：{@code mvn exec:java@supervisorreal -Dexec.args="[1-3]"}，
 * 需要 {@code LLM_API_KEY}，缺了明确报错退出。</p>
 */
public class SupervisorRealScenario {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    /** 「分派：专员 | 任务」协议行（group 1 = 专员名，对齐 SupervisorPattern 的解析）。 */
    private static final Pattern DISPATCH_LINE = Pattern.compile("分派[:：]\\s*([^|｜\\n]+?)\\s*[|｜]");

    private static int bad;

    public static void main(String[] args) {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Supervisor 真实对照组：平级分派 / 专家工具化 / 全真实矩阵");
        System.out.println("模式：真实模型（JdkHttpLlm）" + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        if (pick == 0 || pick == 1) {
            scenarioDispatch();
        }
        if (pick == 0 || pick == 2) {
            scenarioTools();
        }
        if (pick == 0 || pick == 3) {
            scenarioFullReal();
        }

        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：真实主管吐出的分派行全部命中花名册——协议不靠"
                    + "剧本台词，主管换真人也派得动。");
        } else {
            System.out.println("!! 有 " + bad + " 处结构校验未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 场景一：真实主管走「分派：」协议，剧本专家接活。 */
    private static void scenarioDispatch() {
        header("场景一：真实主管 · 平级分派", "真实模型出分派行，trace 逐条校花名册一致性");
        CountingLlm boss = new CountingLlm(new JdkHttpLlm());
        List<Expert> roster = List.of(
                new Expert("技术专家", "技术债与依赖评估",
                        new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new Expert("法务专家", "合规风险检查",
                        new ScriptedLlm().says("发现 2 项合规风险，需两周内整改")),
                new Expert("市场专家", "市场反应评估",
                        new ScriptedLlm().says("竞品下周发布同类功能，窗口期紧")));
        // 协议自检探针：主管的「分派：专员 | 任务」格式先验证，花名册名字为声明清单
        Set<String> names = roster.stream().map(Expert::name).collect(Collectors.toSet());
        ProtocolProbe.requireOrExit(boss, ProtocolProbe.rosterProtocol(names), DISPATCH_LINE, names);
        LlmClient synth = new ScriptedLlm().says("综合：技术债优先，合规跟进，抢竞品发布前上线");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SupervisorPattern.dispatch(
                "给新上线的产品做一份风险评估", roster, boss, synth, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  综合答复 → " + out.lines().findFirst().orElse(""));
        long dispatched = journal.steps().stream()
                .filter(s -> s.reason().startsWith("分派→")).count();
        boolean noSkip = journal.steps().stream().noneMatch(s -> s.reason().equals("主管"));
        System.out.println("  分派 " + dispatched + " 条，主管调用 " + boss.calls() + " 次");
        check("分派行全部命中花名册", dispatched >= 2 && noSkip);
        check("综合收拢非空", !out.isBlank());
        archive(journal, "real-supervisor-plain", "multiagent:supervisor",
                "风险评估（真实主管 · 平级分派）", out,
                boss.calls() + (int) dispatched + 1, ms);
    }

    /** 场景二：真实主管走「CALL 工具：」协议，专家是 SchemaTool。 */
    private static void scenarioTools() {
        header("场景二：真实主管 · Supervisor-as-Tools", "专家包成工具，真实模型出 CALL 行");
        CountingLlm boss = new CountingLlm(new JdkHttpLlm());
        List<Expert> roster = List.of(
                new Expert("技术专家", "技术债与依赖评估",
                        new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new Expert("法务专家", "合规风险检查",
                        new ScriptedLlm().says("发现 2 项合规风险，需两周内整改")),
                new Expert("市场专家", "市场反应评估",
                        new ScriptedLlm().says("竞品下周发布同类功能")));
        List<SchemaTool.Param> params = List.of(
                new SchemaTool.Param("问题", "string", true, "要问专家的具体问题"));
        List<SchemaTool> tools = roster.stream()
                .map(e -> SupervisorPattern.asTool(e, params))
                .toList();
        LlmClient synth = new ScriptedLlm().says("综合：技术债优先，合规跟进");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SupervisorPattern.dispatchViaTools(
                "给新上线的产品做一份风险评估", tools, boss, synth, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  综合答复 → " + out.lines().findFirst().orElse(""));
        long called = journal.steps().stream()
                .filter(s -> s.reason().startsWith("CALL ")).count();
        boolean noSkip = journal.steps().stream().noneMatch(s -> s.reason().equals("主管"));
        System.out.println("  工具调用 " + called + " 次，主管调用 " + boss.calls() + " 次");
        check("CALL 行全部命中工具花名册", called >= 1 && noSkip);
        check("工具协议综合收拢非空", !out.isBlank());
        archive(journal, "real-supervisor-tools", "multiagent:supervisor",
                "风险评估（真实主管 · 专家工具化）", out,
                boss.calls() + (int) called + 1, ms);
    }

    /** 场景三：全真实矩阵——真实模型主管 + 三位 真实模型专家 + 真实模型综合。 */
    private static void scenarioFullReal() {
        header("场景三：全真实矩阵", "主管、专家、综合全是真实模型，看调用账");
        CountingLlm boss = new CountingLlm(new JdkHttpLlm());
        CountingLlm experts = new CountingLlm(new JdkHttpLlm());
        CountingLlm synth = new CountingLlm(new JdkHttpLlm());
        List<Expert> roster = List.of(
                new Expert("技术专家", "技术债与依赖评估",
                        expertBrain(experts, "技术专家", "技术债与依赖评估")),
                new Expert("法务专家", "合规风险检查",
                        expertBrain(experts, "法务专家", "合规风险检查")),
                new Expert("市场专家", "市场与竞品反应评估",
                        expertBrain(experts, "市场专家", "市场与竞品反应评估")));
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SupervisorPattern.dispatch(
                "给新上线的产品做一份风险评估", roster, boss, synth, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  综合答复（首行）→ " + out.lines().findFirst().orElse(""));
        long dispatched = journal.steps().stream()
                .filter(s -> s.reason().startsWith("分派→")).count();
        System.out.println("  调用账：主管 " + boss.calls() + " 次，专家 "
                + experts.calls() + " 次，综合 " + synth.calls() + " 次；分派 "
                + dispatched + " 条");
        check("全真实分派全部命中花名册",
                dispatched >= 2 && journal.steps().stream()
                        .noneMatch(s -> s.reason().equals("主管")));
        check("综合收拢非空", !out.isBlank());
        archive(journal, "real-supervisor-full", "multiagent:supervisor",
                "风险评估（全真实矩阵）", out,
                boss.calls() + experts.calls() + synth.calls(), ms);
    }

    // ---- 小工具 --------------------------------------------------------------

    private static LlmClient expertBrain(LlmClient real, String name, String specialty) {
        return prompt -> real.complete("你是" + name + "（" + specialty + "）。"
                + "主管分派给你的问题：" + prompt + "\n直接给结论，两三句，不要客套。");
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }

    /** 既打印又留档的 trace。 */
    private static BiConsumer<String, String> trace(RunJournal journal) {
        return (who, what) -> {
            System.out.println("    [" + who + "] " + what.lines().findFirst().orElse(""));
            journal.trace().accept(who, what);
        };
    }

    /** 落成档案追加进 runs/runs.jsonl（同一张底账，不清空），打印回执。 */
    private static void archive(RunJournal journal, String runId, String runner,
                                String task, String finalAnswer, int llmCalls, long durationMs) {
        RunRecord record = journal.toRecord(runId, runner, task, finalAnswer, llmCalls, durationMs);
        try {
            Path file = STORE.append(record);
            System.out.println("  [落盘] " + runId + " → 终态 " + record.terminal()
                    + "，" + record.steps().size() + " 步，LLM " + llmCalls
                    + " 次 → " + file);
        } catch (java.io.IOException e) {
            bad++;
            System.out.println("  !! 档案落盘失败：" + e.getMessage());
        }
    }
}
