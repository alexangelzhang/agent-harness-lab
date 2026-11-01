package com.example.agentloop;

import com.example.langgraph4j.eval.TrajectoryCollector;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.KimiLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.ReflectionGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.Map;

/**
 * 同剧本正面对比：图版（ReflectionGraph 的环）vs 原语版（AgentLoop），
 * 同一个任务、同一份剧本，比轮数、LLM 调用数、结果。
 *
 * <p>剧本（两版共用）：初稿只说提神 → 验收/评审挑毛病 → 二稿两点都到位 → 通过。
 * 两版都应是 2 轮、4 次 LLM 调用——对比的意义不在谁赢，在于用同一把尺子
 * 量两种 loop 的长相：图把循环画成节点和边，原语把循环写成行动和验收两个函数。
 */
public class LoopBenchmark {

    private static final String TASK = "为一款冷萃咖啡液写一句广告 slogan";
    private static final String DRAFT1 = "一口下去，精神百倍，加班不犯困！";
    private static final String DRAFT2 = "十二小时低温慢萃，把时间的味道融进每一滴。";
    private static final String CRITIQUE = "建议：没有体现低温萃取，也没体现花时间慢萃。评分 5/10。";
    private static final String FEEDBACK = "没有体现低温萃取，也没体现花了时间慢慢萃";
    private static final String GOAL = "广告词要同时让人看出两点：一是低温萃取，二是花了时间慢慢萃。换个说法也算。";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  loop 两版正面对比 —— 同一剧本：图版 vs 原语版");
        System.out.println("=".repeat(66));

        // 用法: LoopBenchmark [graph|loop] [k] [startIndex] —— 真实模型分片模式
        // （无参数 = 剧本模式，一次跑完两版）；真实模式每次 trial 单独输出一行，跨进程汇总
        if (args.length > 0 && (args[0].equals("graph") || args[0].equals("loop"))) {
            runReal(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            return;
        }

        // ── 图版：ReflectionGraph 的环（generator ⇄ reflector）──
        CountingLlm graphLlm = new CountingLlm(new ScriptedLlm()
                .says(DRAFT1).says(CRITIQUE).says(DRAFT2).says("语言精炼、卖点清晰。ACCEPT"));
        var graph = new ReflectionGraph(graphLlm, 3).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        long t0 = System.nanoTime();
        var raw = new TrajectoryCollector<>(graph).run(
                Map.<String, Object>of("task", TASK),
                RunnableConfig.builder().threadId("bench-graph").build());
        long graphMs = (System.nanoTime() - t0) / 1_000_000;
        int graphRounds = raw.finalState().map(s -> s.<Integer>value("round").orElse(0)).orElse(0);
        String graphVerdict = raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse("");
        String graphError = raw.errorMessage().orElse("");

        // ── 原语版：AgentLoop（actor → verifier 循环）──
        CountingLlm actorLlm = new CountingLlm(new ScriptedLlm().says(DRAFT1).says(DRAFT2));
        CountingLlm verifierLlm = new CountingLlm(new ScriptedLlm().says("NO：" + FEEDBACK).says("YES"));
        var loop = new AgentLoop(new LlmActor(actorLlm), new LlmVerifier(verifierLlm, GOAL), 3);
        long t1 = System.nanoTime();
        LoopResult loopResult = loop.run(TASK);
        long loopMs = (System.nanoTime() - t1) / 1_000_000;

        // ── 对比表 ──
        System.out.println("""
                  剧本：初稿只说提神 → 挑毛病 → 二稿到位 → 通过（两版共用）
                """);
        System.out.printf("  %-8s %-8s %-10s %-10s %s%n", "版本", "轮数", "LLM调用", "耗时", "结果");
        System.out.println("  " + "-".repeat(58));
        System.out.printf("  %-8s %-8d %-10d %-10d %s%n", "图版", graphRounds, graphLlm.calls(), graphMs,
                graphError.isEmpty() ? "终裁=" + graphVerdict : "ERROR: " + graphError);
        System.out.printf("  %-8s %-8d %-10d %-10d %s%n", "原语版", loopResult.turns(),
                actorLlm.calls() + verifierLlm.calls(), loopMs, loopResult.stopReason());

        System.out.println("""
                
                  结论：同一剧本下两版消耗相同（2 轮 / 4 次调用）——
                  图版把循环画成节点、条件边和 verdict 通道；
                  原语版把循环写成 actor / verifier 两个函数 + 反馈回流。
                  要 checkpoint / interrupt / HITL 选图版；要轻量、嵌进现有代码选原语版。
                """);
        System.exit(0);
    }

    /** 真实模型分片模式：跑一版的一个/几个 trial，每行一个结果，供跨进程汇总。 */
    private static void runReal(String version, int k, int startIndex) throws Exception {
        if (System.getenv("KIMI_API_KEY") == null) {
            System.err.println("缺少 KIMI_API_KEY 环境变量");
            System.exit(1);
        }
        System.out.println("真实模型对比 · " + version + " 版 · trial " + startIndex
                + (k > 1 ? ".." + (startIndex + k - 1) : ""));
        for (int i = 0; i < k; i++) {
            int trial = startIndex + i;
            if (version.equals("graph")) {
                CountingLlm llm = new CountingLlm(new KimiLlm());
                // 真实模式配 judge 终裁：reflector 是开放式评审，k2 极少吐字面 ACCEPT，
                // 不配 judge 会反复顶满轮数（验收协议问题，见 LANGGRAPH4J.md 对比结论）
                var graph = new ReflectionGraph(llm, 3, llm).build().compile(
                        CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
                long t0 = System.nanoTime();
                var raw = new TrajectoryCollector<>(graph).run(
                        Map.<String, Object>of("task", TASK),
                        RunnableConfig.builder().threadId("bench-graph-" + trial).build());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String verdict = raw.errorMessage().map(e -> "ERROR:" + e)
                        .orElse(raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse(""));
                int rounds = raw.finalState().map(s -> s.<Integer>value("round").orElse(0)).orElse(0);
                System.out.printf("[graph trial %d] 轮数=%d LLM调用=%d 耗时=%ds 终裁=%s%n",
                        trial, rounds, llm.calls(), ms / 1000, verdict);
            } else {
                CountingLlm actorLlm = new CountingLlm(new KimiLlm());
                CountingLlm verifierLlm = new CountingLlm(new KimiLlm());
                var loop = new AgentLoop(new LlmActor(actorLlm),
                        new LlmVerifier(verifierLlm, GOAL), 3);
                long t0 = System.nanoTime();
                LoopResult r = loop.run(TASK);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("[loop trial %d] 轮数=%d LLM调用=%d 耗时=%ds 结果=%s%n",
                        trial, r.turns(), actorLlm.calls() + verifierLlm.calls(), ms / 1000,
                        r.stopReason());
            }
        }
        System.exit(0);
    }
}
