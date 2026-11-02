package com.example.langgraph4j.eval;

import com.example.harness.config.AgentConfig;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.RateLimitedLlm;
import com.example.langgraph4j.llm.RetryingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.ReflectionGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.Map;

/**
 * Ch8 轨迹评估 · 真实 LLM 版：ReflectionGraph × JdkHttpLlm 跑 k=5。
 *
 * <p>与 EvalScenario 的 ScriptedLlm 剧本不同，这里每次 trial 都是真实模型调用，
 * 步数、LLM 调用数、耗时都是真实跑出来的——看的是模型和图结构放在一起
 * 的实际表现，不是剧本排好的。
 *
 * <p>谁算通过？不靠「定稿里有没有『低温慢萃』四个字」这种死规则（换个说法
 * 的合格文案会被误杀）。顶满 maxRounds 时图里的 judge 节点按意思终裁，
 * 结果写进 verdict 通道（ACCEPT / JUDGE_PASS / JUDGE_FAIL），Pass@k 直接
 * 读图内的终裁——裁判是图结构的一部分，不是外层脚本拼出来的。
 *
 * <p>注意：会真实消耗 API 额度。每次 trial 重新建 CountingLlm 与图，
 * threadId 独立；maxRounds=3 封顶单轮成本。
 */
public class RealEvalScenario {

    public static void main(String[] args) throws Exception {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        // 用法: RealEvalScenario [k] [startIndex] —— 真实模型单次调用慢，
        // 可以分片跑（如 "1 3" = 只跑第 3 号 trial），跨进程汇总 Pass@k
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int startIndex = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        String model = AgentConfig.load().model();
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  Ch8 轨迹级评估 · 真实 LLM（" + model + "）—— ReflectionGraph"
                + (k == 5 && startIndex == 1 ? " k=5" : " 分片 k=" + k + " 起始于 trial " + startIndex));
        System.out.println("=".repeat(66));
        System.out.println("""
                ── 任务：给冷萃咖啡液写一句广告词，要让人看出低温、花时间慢慢萃 ──
                   谁算通过：图内终裁——reflector 放行（ACCEPT）或顶满轮数后 judge 按意思放行
                   步数只报告不卡门槛；每次 trial 独立 threadId
                   k 次 trial 并行开跑（墙钟省大头），并发闸 = RateLimitedLlm(4 并发, 200ms 间隔)
                """);

        // 并行评估的线程池 = 期望并发度；真正的配额闸在 LLM 层（限流在内、重试在外）
        var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(k, 4));
        long wallStart = System.nanoTime();
        var report = EvalHarness.evaluateParallel("reflection-real", k, startIndex, executor,
                () -> {
                    // RetryingLlm(RateLimitedLlm(...))：限流在内重试在外，重试风暴冲不破并发闸
                    CountingLlm llm = new CountingLlm(
                            new RetryingLlm(new RateLimitedLlm(new JdkHttpLlm(), 4, 200)));
                    try {
                        // judge 与 generator/reflector 共用同一个 CountingLlm，裁判成本计入本 trial
                        CompiledGraph<ReflectionGraph.ReflectionState> graph =
                                new ReflectionGraph(llm, 3, llm).build().compile(
                                        CompileConfig.builder()
                                                .checkpointSaver(new MemorySaver())
                                                .build());
                        return new TrialImpl(graph,
                                Map.<String, Object>of("task",
                                        "为一款冷萃咖啡液写一句广告 slogan，要让人一眼看出：低温萃取、花了时间慢慢萃出来的"),
                                llm);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                s -> s.<String>value("draft").orElse(""),
                s -> {
                    String verdict = s.<String>value("verdict").orElse("");
                    return verdict.equals("ACCEPT") || verdict.equals("JUDGE_PASS");
                });
        executor.shutdown();
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        report.print();
        long sumTrialMs = report.trajectories().stream().mapToLong(t -> t.durationMs()).sum();
        System.out.printf("  │ 墙钟 %ds（各 trial 串行耗时相加 %ds——并行把等待重叠了）%n",
                wallMs / 1000, sumTrialMs / 1000);
        scenarioRealProbes();

        // langgraph4j 内部的异步执行器是非 daemon 线程，演示结束后显式退出
        System.exit(0);
    }

    /**
     * 真实模型对抗探针：剧本 judge 说 NO 是排好的，真实模型说 NO 才算数。
     * 探针 1：跑题稿 + 老好人 reflector（剧本）→ 真实 judge 必须复核打回（JUDGE_REJECT）。
     * 探针 2：正常任务全流程真实模型 → 若 reflector 放行，真实 judge 必须复核确认
     * （ACCEPT）；judge 把合格稿打回就是「该放没放」，探针失败。
     */
    private static void scenarioRealProbes() throws Exception {
        System.out.println("""
                ── 真实模型对抗探针 ──
                   探针 1：跑题稿（剧本）+ reflector 放行（剧本）→ 真实 judge 必须打回
                   探针 2：正常任务全真实跑 → reflector 若放行，真实 judge 必须确认
                """);

        // 探针 1：真实 judge 对跑题稿说 NO
        var probeReject = new AdversarialProbe<>(
                "真实 judge 该拒跑题稿",
                new TrialImpl(
                        new ReflectionGraph(
                                new ScriptedLlm()
                                        .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                                        .says("写得有气势，我喜欢。ACCEPT"),
                                1, new JdkHttpLlm()).build().compile(
                                CompileConfig.builder().checkpointSaver(new MemorySaver()).build()),
                        Map.<String, Object>of("task",
                                "为一款冷萃咖啡液写一句广告 slogan，要让人一眼看出：低温萃取、花了时间慢慢萃出来的"),
                        null),
                raw -> raw.errorMessage().isEmpty()
                        && raw.finalState().map(s -> "JUDGE_REJECT"
                                .equals(s.<String>value("verdict").orElse(""))).orElse(false));

        // 探针 2：真实 judge 对全流程真实跑出的合格稿放行（reflector 放行时必须复核确认）
        var probeAccept = new AdversarialProbe<>(
                "真实 judge 该放合格稿",
                new TrialImpl(
                        new ReflectionGraph(new JdkHttpLlm(), 3, new JdkHttpLlm()).build().compile(
                                CompileConfig.builder().checkpointSaver(new MemorySaver()).build()),
                        Map.<String, Object>of("task",
                                "为一款冷萃咖啡液写一句广告 slogan，要让人一眼看出：低温萃取、花了时间慢慢萃出来的"),
                        null),
                raw -> raw.errorMessage().isEmpty()
                        && raw.finalState().map(s -> {
                            String v = s.<String>value("verdict").orElse("");
                            return v.equals("ACCEPT") || v.equals("JUDGE_PASS");
                        }).orElse(false));

        EvalHarness.probeReport("真实模型两向验证", java.util.List.of(probeReject, probeAccept));
    }

    private record TrialImpl(
            CompiledGraph<ReflectionGraph.ReflectionState> graph,
            Map<String, Object> inputs, CountingLlm llm)
            implements EvalHarness.Trial<ReflectionGraph.ReflectionState> {
    }
}
