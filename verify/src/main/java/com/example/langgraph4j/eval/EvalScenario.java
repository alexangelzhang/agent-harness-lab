package com.example.langgraph4j.eval;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.PlanSolveGraph;
import com.example.langgraph4j.patterns.ReflectionGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Ch8 轨迹评估演示：用 ScriptedLlm 的两套剧本（顺利 / 坎坷）给同一任务跑 k 次，
 * 展示 Pass@k、步数、LLM 调用数、轨迹冗余度与 recursionLimit 护栏命中。
 *
 * <p>场景 A（ReflectionGraph）：奇数轮「顺利」（第 2 轮 ACCEPT），偶数轮「坎坷」
 * （永远不接受，顶到 maxRounds）——预期 Pass@5 = 3/5。
 * <p>场景 B（PlanSolveGraph + recursionLimit=5）：计划有 5 步，图跑完需要 7 个
 * superstep，必撞护栏——演示 ERROR 轨迹与「Maximum number of iterations」识别。
 *
 * <p>对应书中观点：传统 NLP 指标（BLEU/ROUGE）评不了 Agent，要看任务完成率 +
 * 步数惩罚 + 轨迹冗余度；护栏命中本身就是一种「步数失控」的硬证据。
 */
public class EvalScenario {

    /** Trial 的简单实现：图 + 输入 + 计数 LLM。 */
    private record SimpleTrial<S extends AgentState>(
            CompiledGraph<S> graph, Map<String, Object> inputs, CountingLlm llm)
            implements EvalHarness.Trial<S> {
    }

    private static CompileConfig memorySaverConfig() {
        return CompileConfig.builder()
                .checkpointSaver(new MemorySaver()).build();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  Ch8 轨迹级评估 —— Pass@k / 步数 / 冗余度 / 护栏命中");
        System.out.println("=".repeat(66));

        scenarioReflection();
        scenarioRecursionTrap();
        scenarioProbes();

        // langgraph4j 内部的异步执行器是非 daemon 线程，演示结束后显式退出
        System.exit(0);
    }

    /**
     * 场景 C：对抗探针（AWS「对抗双胞胎」）——每个探针是一次必失败的运行，
     * 断言失败方式对得上。该拒没拒 = 探针失败 = 门禁失灵。
     */
    private static void scenarioProbes() throws Exception {
        System.out.println("""
                ── 场景 C：对抗探针 —— 该拒的必须拒 ──
                   探针 1：五步计划 + 步数护栏 4 → 必须撞 Maximum number of iterations
                   探针 2：跑题稿 + 老好人 reflector(ACCEPT) + 剧本 judge(NO) → 必须 JUDGE_REJECT
                   探针 3：planner 输出无法解析的文本 → 必须报「无法从 LLM 输出解析计划」
                """);

        // 探针 1：步数护栏
        var probeRecursion = new AdversarialProbe<>(
                "步数护栏该拦就拦",
                new SimpleTrial<>(
                        new PlanSolveGraph(new ScriptedLlm()
                                .says("""
                                        1. 查询今日黄金价格
                                        2. 查询白银价格
                                        3. 查询铂金价格
                                        4. 对比三种贵金属走势
                                        5. 汇总回答用户""")
                                .says("612 元/克").says("7.2 元/克").says("228 元/克")
                                .says("黄金稳中有升").says("报价来自模拟数据").says("汇总完成。"),
                                Map.of()).build().compile(
                                CompileConfig.builder()
                                        .checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver())
                                        .recursionLimit(4).build()),
                        Map.<String, Object>of("question", "今天贵金属价格"),
                        null),
                raw -> raw.recursionLimitHit());

        // 探针 2：judge 复核打回（reflector 老好人放行跑题稿，judge 必须打回）
        var scripted = new ScriptedLlm()
                .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                .says("写得不错，有气势。ACCEPT")
                .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                .says("确实好。ACCEPT");
        var judgeScripted = new ScriptedLlm().says("NO").says("NO");
        var probeJudge = new AdversarialProbe<>(
                "judge 复核该打回就跑回",
                new SimpleTrial<>(
                        new ReflectionGraph(scripted, 2, judgeScripted).build().compile(
                                CompileConfig.builder()
                                        .checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver())
                                        .build()),
                        Map.<String, Object>of("task", "为冷萃咖啡液写广告词，要体现低温慢萃"),
                        null),
                raw -> raw.errorMessage().isEmpty()
                        && raw.finalState().map(s -> "JUDGE_REJECT"
                                .equals(s.<String>value("verdict").orElse(""))).orElse(false));

        // 探针 3：非法计划必须报错，不能蒙混过关
        var probeParse = new AdversarialProbe<>(
                "非法计划该报错就报错",
                new SimpleTrial<>(
                        new PlanSolveGraph(new ScriptedLlm()
                                .says("我觉得吧，这个问题挺有意思的，咱们可以从长计议。"),
                                Map.of()).build().compile(
                                CompileConfig.builder()
                                        .checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver())
                                        .build()),
                        Map.<String, Object>of("question", "今天贵金属价格"),
                        null),
                raw -> raw.errorMessage().map(e -> e.contains("无法从 LLM 输出解析计划")).orElse(false));

        EvalHarness.probeReport("剧本版三个必失败场景", java.util.List.of(probeRecursion, probeJudge, probeParse));
    }

    /** 场景 A：Basic Reflection，顺利 / 坎坷交替剧本，Pass@5 预期 60%。 */
    private static void scenarioReflection() {
        System.out.println("""
                ── 场景 A：Basic Reflection —— 同一任务两种 LLM 剧本各跑 k 次 ──
                   顺利剧本：第 2 轮 reflector 输出 ACCEPT → 提前定稿
                   坎坷剧本：reflector 永远拒绝 → 顶到 maxRounds=3 才返回
                   通过判定：定稿含「低温慢萃」卖点 且 未顶满 maxRounds
                """);
        AtomicInteger trialIndex = new AtomicInteger();
        Function<ReflectionGraph.ReflectionState, String> answerOf =
                s -> s.<String>value("draft").orElse("");
        var report = EvalHarness.evaluate("reflection-pass-at-k", 5,
                () -> {
                    boolean smooth = trialIndex.incrementAndGet() % 2 == 1;
                    CountingLlm llm = new CountingLlm(smooth ? smoothScript() : bumpyScript());
                    try {
                        CompiledGraph<ReflectionGraph.ReflectionState> graph =
                                new ReflectionGraph(llm, 3).build().compile(memorySaverConfig());
                        return new SimpleTrial<>(graph,
                                Map.<String, Object>of("task", "为一款冷萃咖啡液写一句广告 slogan"), llm);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                answerOf,
                s -> s.<String>value("draft").orElse("").contains("低温慢萃")
                        && s.<Integer>value("round").orElse(99) < 3);
        report.print();
    }

    private static ScriptedLlm smoothScript() {
        return new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：没有体现「低温慢萃」的工艺卖点；句子偏长。评分 6/10。")
                .says("十二小时低温慢萃，唤醒每一刻。")
                .says("语言精炼、卖点清晰，符合要求。ACCEPT");
    }

    /** 坎坷剧本：maxRounds=3 需要 generator×3 + reflector×3 共 6 条预置回复。 */
    private static ScriptedLlm bumpyScript() {
        return new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：卖点仍不突出，句子不够顺口。评分 5/10。")
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：依旧没有工艺细节，再给一版。评分 5/10。")
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：仍未达标，但已达最大批改轮数。评分 5/10。");
    }

    /** 场景 B：recursionLimit 护栏——计划 5 步需要 7 个 superstep，上限 5 必撞。 */
    private static void scenarioRecursionTrap() {
        System.out.println("""
                ── 场景 B：Plan & Solve —— recursionLimit=5 的护栏命中 ──
                   planner(1) + executor×5 + finalizer(1) = 7 个 superstep > 上限 5
                   预期：每次 trial 都报 Maximum number of iterations，Pass@k = 0
                """);
        var report = EvalHarness.evaluate("plansolve-recursion-trap", 2,
                () -> {
                    CountingLlm llm = new CountingLlm(new ScriptedLlm()
                            .says("""
                                    1. 查询今日黄金价格
                                    2. 查询白银价格
                                    3. 查询铂金价格
                                    4. 对比三种贵金属走势
                                    5. 汇总回答用户""")
                            .says("今日黄金价格约 612 元/克（模拟数据）。")
                            .says("今日白银价格约 7.2 元/克（模拟数据）。")
                            .says("今日铂金价格约 228 元/克（模拟数据）。")
                            .says("黄金稳中有升，白银波动较大，铂金相对平稳。")
                            .says("三种贵金属的实物报价均来自模拟数据。")
                            .says("黄金约 612 元/克，白银约 7.2 元/克，铂金约 228 元/克。"));
                    try {
                        CompiledGraph<PlanSolveGraph.PlanState> graph =
                                new PlanSolveGraph(llm, Map.of()).build().compile(
                                        CompileConfig.builder()
                                                .checkpointSaver(new MemorySaver())
                                                .recursionLimit(5)
                                                .build());
                        return new SimpleTrial<>(graph,
                                Map.<String, Object>of("question", "今天黄金、白银、铂金价格分别是多少"), llm);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                s -> s.<String>value("finalAnswer").orElse(""),
                s -> s.<String>value("finalAnswer").orElse("").contains("612"));
        report.print();
    }
}
