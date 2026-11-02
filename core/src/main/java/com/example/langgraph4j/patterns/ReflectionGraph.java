package com.example.langgraph4j.patterns;

import com.example.harness.Budget;
import com.example.langgraph4j.llm.EnvException;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：Basic Reflection（基础反思）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 BasicReflectionAgent）：
 * <pre>
 *   draft = generate(task, "")                     ──▶ generator 节点（critique 为空 → 初稿）
 *   for round in 1..maxRounds:                     ──▶ 条件边：round 字段 + 路由判断（防死循环）
 *       critique = critique(task, draft)           ──▶ reflector 节点
 *       if critique.contains("ACCEPT"): return     ──▶ 条件边：ACCEPT → END
 *       draft = generate(task, critique)           ──▶ generator 节点（critique 非空 → 修改稿）
 * </pre>
 * 图结构：START → generator → reflector ⇄ (条件边) → END
 *
 * <p>失败分两路（与 agentloop 包的 AgentLoop 同一套约定，出自 Meta/Stripe
 * 的失败分流实践）：成果问题（批语未通过 / judge 打回）走原路回流，吃
 * maxRounds 修复额度；<b>环境故障</b>（节点 LLM 调用抛 {@link EnvException}，
 * 如网关超时）不占额度、单独计数 maxEnvFailures，超限终止并在 verdict
 * 通道写 ENV_ABORTED——失败怎么失败的，看 verdict 就知道。</p>
 *
 * <p>预算（可选，{@link Budget}）：生产级的油表。节点每次 LLM 调用后结算，
 * 预算耗尽不看 maxRounds 的脸色，路由直接 END 并在 verdict 通道写
 * BUDGET_EXHAUSTED——「转人工」是显式终态，不是悄悄跑完。</p>
 */
public class ReflectionGraph {

    // ---- State：手写版的局部变量变成带合并策略的通道 ----
    public static final class ReflectionState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "task", Channels.base(() -> ""),        // 任务要求（只写一次）
                "draft", Channels.base(() -> ""),       // 当前稿件（覆盖：generator 每轮改写）
                "critique", Channels.base(() -> ""),    // 最近一批语（覆盖：reflector 每轮改写）
                "round", Channels.base(() -> 0),        // 轮数（覆盖：手写版的 round 循环变量）
                "verdict", Channels.base(() -> ""),     // 终裁结果：ACCEPT / JUDGE_PASS / JUDGE_FAIL / ENV_ABORTED（覆盖）
                "envError", Channels.base(() -> ""),    // 最近环境故障描述；空 = 没有（覆盖）
                "envCount", Channels.base(() -> 0));    // 环境故障次数（覆盖：超限终止）

        ReflectionState(Map<String, Object> initData) {
            super(initData);
        }

        String task() {
            return this.<String>value("task").orElse("");
        }

        String draft() {
            return this.<String>value("draft").orElse("");
        }

        String critique() {
            return this.<String>value("critique").orElse("");
        }

        int round() {
            return this.<Integer>value("round").orElse(0);
        }

        String verdict() {
            return this.<String>value("verdict").orElse("");
        }

        String envError() {
            return this.<String>value("envError").orElse("");
        }

        int envCount() {
            return this.<Integer>value("envCount").orElse(0);
        }
    }

    static final String GENERATOR = "generator";
    static final String REFLECTOR = "reflector";
    static final String JUDGE = "judge";

    /** 环境故障的默认容忍次数（不占 maxRounds 的修复额度）。 */
    public static final int DEFAULT_MAX_ENV_FAILURES = 3;

    private final LlmClient llm;
    private final int maxRounds;
    private final LlmClient judgeLlm;
    private final int maxEnvFailures;
    private final Budget budget;

    public ReflectionGraph(LlmClient llm, int maxRounds) {
        this(llm, maxRounds, null, DEFAULT_MAX_ENV_FAILURES, null);
    }

    /**
     * @param judgeLlm 终裁裁判（可空）。顶满 maxRounds 时不再直接交稿，转给 judge
     *                 按意思终裁——reflector 太严一直没放行时，好稿子还有机会被捞回来。
     */
    public ReflectionGraph(LlmClient llm, int maxRounds, LlmClient judgeLlm) {
        this(llm, maxRounds, judgeLlm, DEFAULT_MAX_ENV_FAILURES, null);
    }

    /**
     * @param maxEnvFailures 环境故障（LLM 超时、断连）容忍次数：不占 maxRounds 额度，
     *                       超限终止并在 verdict 通道写 ENV_ABORTED
     */
    public ReflectionGraph(LlmClient llm, int maxRounds, LlmClient judgeLlm, int maxEnvFailures) {
        this(llm, maxRounds, judgeLlm, maxEnvFailures, null);
    }

    /**
     * @param budget 任务预算（可空）。节点 LLM 调用后结算，耗尽即路由 END，
     *               verdict 通道写 BUDGET_EXHAUSTED（转人工）
     */
    public ReflectionGraph(LlmClient llm, int maxRounds, LlmClient judgeLlm,
                           int maxEnvFailures, Budget budget) {
        if (maxEnvFailures < 1) {
            throw new IllegalArgumentException("maxEnvFailures 至少为 1");
        }
        this.llm = llm;
        this.maxRounds = maxRounds;
        this.judgeLlm = judgeLlm;
        this.maxEnvFailures = maxEnvFailures;
        this.budget = budget;
    }

    private boolean budgetHit() {
        return budget != null && budget.exhausted();
    }

    /**
     * 节点 LLM 调用的环境故障处理：是环境故障就返回写 state 的更新（计数 +1、
     * 超限时顺手写 verdict=ENV_ABORTED），让节点重试；不是就返回 null，异常原样上抛。
     */
    private Map<String, Object> envFailure(ReflectionState state, RuntimeException e, String nodeName) {
        RuntimeException translated = EnvException.wrapIfEnv(e);
        if (!(translated instanceof EnvException env)) {
            return null;
        }
        int n = state.envCount() + 1;
        System.out.println("  [" + nodeName + " 环境故障 第 " + n + "/" + maxEnvFailures + " 次] "
                + env.getMessage());
        if (n >= maxEnvFailures) {
            return Map.of("envError", env.getMessage(), "envCount", n, "verdict", "ENV_ABORTED");
        }
        return Map.of("envError", env.getMessage(), "envCount", n);
    }

    /** generator 节点：critique 为空 → 初稿；否则按批语修改（对应手写版 generate() 的两个分支）。 */
    Map<String, Object> generator(ReflectionState state) {
        final String output;
        try {
            if (state.critique().isEmpty()) {
                output = llm.complete("""
                        请完成下面的创作任务，直接输出作品内容。

                        任务：%s
                        作品：""".formatted(state.task()));
                System.out.println("  [generator 初稿] " + output.strip());
            } else {
                output = llm.complete("""
                        请根据老师的批改意见修改你的作品，直接输出修改后的完整作品。
                        只输出作品本身的全文：不要输出「修改后的作品：」这类标题，不要加引号，不要附加任何解释或说明文字。

                        任务：%s
                        原作品：%s
                        批改意见：%s
                        修改后的作品：""".formatted(state.task(), state.draft(), state.critique()));
                System.out.println("  [generator 按批语修改] " + output.strip());
            }
        } catch (RuntimeException e) {
            // 环境故障：不占轮数，重试生成；成果问题不算这条路，原样上抛
            Map<String, Object> env = envFailure(state, e, "generator");
            if (env != null) {
                return env;
            }
            throw e;
        }
        // 对应手写版 draft = generate(...) 与 round++；成功时清掉环境故障标记
        if (budgetHit()) {
            // 预算耗尽：稿子照记（审计要看到最后一版），终态写明转人工
            return Map.of("draft", output.strip(), "round", state.round() + 1,
                    "envError", "", "verdict", "BUDGET_EXHAUSTED");
        }
        return Map.of("draft", output.strip(), "round", state.round() + 1, "envError", "");
    }

    /** reflector 节点：批改并给建议（对应手写版 critique()）。 */
    Map<String, Object> reflector(ReflectionState state) {
        final String output;
        try {
            output = llm.complete("""
                    你是一位严格的评审。请对下面的作品进行批改，指出存在的问题并给出具体修改建议。
                    如果作品已经足够好，请输出「ACCEPT」表示通过。

                    任务要求：%s
                    作品：%s
                    批语：""".formatted(state.task(), state.draft()));
        } catch (RuntimeException e) {
            Map<String, Object> env = envFailure(state, e, "reflector");
            if (env != null) {
                return env;
            }
            throw e;
        }
        System.out.println("  [reflector 批语] " + output.strip());
        // 对应手写版 critique = critique(task, draft)；ACCEPT 时顺手把终裁写进 verdict 通道
        if (budgetHit()) {
            return Map.of("critique", output.strip(), "verdict", "BUDGET_EXHAUSTED", "envError", "");
        }
        return Map.of(
                "critique", output.strip(),
                "verdict", output.contains("ACCEPT") ? "ACCEPT" : "",
                "envError", "");
    }

    /** judge 节点：两种职责，按 critique 里有没有 ACCEPT 区分——
     *  复核：reflector 说合格了，judge 把关（防 reflector 太好说话）；
     *  终裁：顶满轮数，稿子本身行不行（防 reflector 太严误杀好稿）。
     *  复核打回时把理由写进 critique 通道：generator 下一轮按裁判的批语改，
     *  而不是按 reflector 那句客套的 ACCEPT 瞎改（评估反馈回流）。 */
    Map<String, Object> judge(ReflectionState state) {
        boolean review = state.critique().contains("ACCEPT");
        final String reply;
        try {
            reply = judgeLlm.complete(review ? """
                    你是终审裁判。评审已经放行了下面的作品，你来复核把关：它真的达到任务要求了吗？
                    按意思判断，换个说法也算。

                    只回答 YES；不达标就回 NO：再加一句哪里不行。

                    任务：%s
                    作品：%s""".formatted(state.task(), state.draft()) : """
                    你来做终裁。这位作者交了下面的作品，评审一直没给通过。你看作品本身达没达到任务要求，
                    按意思判断，换个说法也算。

                    只回答 YES；不达标就回 NO：再加一句哪里不行。

                    任务：%s
                    作品：%s""".formatted(state.task(), state.draft()));
        } catch (RuntimeException e) {
            Map<String, Object> env = envFailure(state, e, "judge");
            if (env != null) {
                return env;
            }
            throw e;
        }
        boolean pass = reply != null
                && reply.strip().replace("*", "").toUpperCase().startsWith("YES");
        String reason = "";
        if (!pass && reply != null) {
            int idx = reply.indexOf('：');
            if (idx >= 0 && idx < reply.length() - 1) {
                reason = reply.substring(idx + 1).strip();
            }
        }
        if (review) {
            System.out.println("  [judge 复核] " + (pass ? "YES → 维持放行（reflector 通过，judge 复核确认）"
                    : "NO → 打回修改" + (reason.isEmpty() ? "" : "（" + reason + "）")));
            if (pass) {
                return Map.of("verdict", "ACCEPT", "envError", "");
            }
            // 打回理由回流给 generator；没给理由就留一句兜底批语
            return Map.of(
                    "verdict", "JUDGE_REJECT",
                    "critique", reason.isEmpty() ? "judge 复核未通过，请对照任务要求重写"
                            : "judge 复核打回：" + reason,
                    "envError", "");
        }
        System.out.println("  [judge 终裁] " + (pass ? "YES → 放行（reflector 未通过，judge 兜底）"
                : "NO → 不予放行" + (reason.isEmpty() ? "" : "（" + reason + "）")));
        return Map.of("verdict", pass ? "JUDGE_PASS" : "JUDGE_FAIL", "envError", "");
    }

    /** 条件边路由（generator 之后）：正常 → reflector；环境故障 → 重试生成（不占轮数），
     *  超上限 → 终止；预算耗尽 → 转人工。 */
    String routeAfterGenerator(ReflectionState state) {
        if (!state.envError().isEmpty()) {
            if (state.envCount() >= maxEnvFailures) {
                System.out.println("  [路由] 环境故障达到上限 " + maxEnvFailures + " 次 → 终止（ENV_ABORTED）");
                return StateGraph.END;
            }
            System.out.println("  [路由] generator 环境故障 → 重试生成");
            return GENERATOR;
        }
        if (budgetHit()) {
            System.out.println("  [路由] 预算耗尽（" + budget.describe() + "）→ 转人工（BUDGET_EXHAUSTED）");
            return StateGraph.END;
        }
        return REFLECTOR;
    }

    /** 条件边路由（reflector 之后）：环境故障 → 重试批改（不占轮数），超上限 → 终止；
     *  预算耗尽 → 转人工；ACCEPT → judge 复核（有裁判时）或直接定稿；
     *  顶满轮数 → judge 终裁（有裁判时）或交最后一版；否则回 generator。 */
    String routeAfterReflector(ReflectionState state) {
        if (!state.envError().isEmpty()) {
            if (state.envCount() >= maxEnvFailures) {
                System.out.println("  [路由] 环境故障达到上限 " + maxEnvFailures + " 次 → 终止（ENV_ABORTED）");
                return StateGraph.END;
            }
            System.out.println("  [路由] reflector 环境故障 → 重试批改");
            return REFLECTOR;
        }
        if (budgetHit()) {
            System.out.println("  [路由] 预算耗尽（" + budget.describe() + "）→ 转人工（BUDGET_EXHAUSTED）");
            return StateGraph.END;
        }
        if (state.critique().contains("ACCEPT")) {
            if (judgeLlm != null) {
                System.out.println("  [路由] Reflector 通过（ACCEPT）→ judge 复核把关");
                return JUDGE;
            }
            System.out.println("  [路由] Reflector 通过（ACCEPT）→ 定稿");
            return StateGraph.END;
        }
        if (state.round() >= maxRounds) {
            if (judgeLlm != null) {
                System.out.println("  [路由] 达到最大轮数 " + maxRounds + " → 交 judge 终裁");
                return JUDGE;
            }
            System.out.println("  [路由] 达到最大轮数 " + maxRounds + " → 返回最后一版");
            return StateGraph.END;
        }
        System.out.println("  [路由] 批语未通过 → 回到 generator 修改（第 " + (state.round() + 1) + " 轮）");
        return GENERATOR;
    }

    /** 条件边路由（judge 之后）：环境故障 → 重试裁判（不占轮数），超上限 → 终止；
     *  复核打回且还有轮数 → generator；其余（复核通过 /
     *  终裁无论是否放行）→ END，终裁就是最后一句话。 */
    String routeAfterJudge(ReflectionState state) {
        if (!state.envError().isEmpty()) {
            if (state.envCount() >= maxEnvFailures) {
                System.out.println("  [路由] 环境故障达到上限 " + maxEnvFailures + " 次 → 终止（ENV_ABORTED）");
                return StateGraph.END;
            }
            System.out.println("  [路由] judge 环境故障 → 重试裁判");
            return JUDGE;
        }
        if (state.verdict().equals("JUDGE_REJECT")) {
            if (budgetHit()) {
                System.out.println("  [路由] judge 复核打回，但预算耗尽 → 转人工（BUDGET_EXHAUSTED）");
                return StateGraph.END;
            }
            if (state.round() >= maxRounds) {
                System.out.println("  [路由] judge 复核打回，且已顶满轮数 → 交稿（未通过）");
                return StateGraph.END;
            }
            System.out.println("  [路由] judge 复核打回 → 回到 generator 修改（第 " + (state.round() + 1) + " 轮）");
            return GENERATOR;
        }
        System.out.println("  [路由] " + (state.verdict().equals("JUDGE_FAIL")
                ? "judge 终裁不放行" : "judge 放行") + "（" + state.verdict() + "）→ 定稿");
        return StateGraph.END;
    }

    public StateGraph<ReflectionState> build() throws Exception {
        var graph = new StateGraph<>(ReflectionState.SCHEMA, ReflectionState::new)
                .addNode(GENERATOR, node_async(this::generator))
                .addNode(REFLECTOR, node_async(this::reflector))
                .addEdge(StateGraph.START, GENERATOR)          // 入口
                // generator 之后也是条件边：环境故障重试生成（不占轮数），正常去批改
                .addConditionalEdges(GENERATOR, edge_async(this::routeAfterGenerator),
                        Map.of(REFLECTOR, REFLECTOR, GENERATOR, GENERATOR, StateGraph.END, StateGraph.END));
        // 顶满轮数 → judge 终裁（可选）；END 的值是 "__END__"（大写），键必须精确匹配
        var routes = new java.util.HashMap<String, String>(
                Map.of(GENERATOR, GENERATOR, REFLECTOR, REFLECTOR, StateGraph.END, StateGraph.END));
        if (judgeLlm != null) {
            graph.addNode(JUDGE, node_async(this::judge))
                    // judge 之后也是条件边：环境故障重试裁判、复核打回 → generator；其余 → END
                    .addConditionalEdges(JUDGE, edge_async(this::routeAfterJudge),
                            Map.of(GENERATOR, GENERATOR, JUDGE, JUDGE, StateGraph.END, StateGraph.END));
            routes.put(JUDGE, JUDGE);
        }
        return graph.addConditionalEdges(REFLECTOR, edge_async(this::routeAfterReflector), routes);
    }

    /** 运行演示：为一款冷萃咖啡液写一句广告 slogan（与手写版同一剧本）。 */
    public static void runScenario() throws Exception {
        System.out.println("""
                ── 手写版（while/if 循环）与本图的对照 ──
                   draft = generate(task, critique)   → generator 节点（draft/round 通道更新）
                   critique = critique(task, draft)   → reflector 节点（critique 通道更新）
                   if ACCEPT or round >= max: 结束     → 条件边 routeAfterReflector → END
                   else: 继续循环                      → 条件边 → generator（图上的环）
                """);

        ScriptedLlm llm = new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：没有体现‘低温慢萃’的工艺卖点；句子偏长，不够顺口。评分 6/10。")
                .says("十二小时低温慢萃，唤醒每一刻。")
                .says("语言精炼、卖点清晰，符合要求。ACCEPT");

        var graph = new ReflectionGraph(llm, 5).build().compile(
                CompileConfig.builder().checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", "为一款冷萃咖啡液写一句广告 slogan")),
                RunnableConfig.builder().threadId("reflection-场景").build());

        finalState.ifPresent(s -> System.out.println("\n  [定稿] " + s.draft() + "（共 " + s.round() + " 轮）"));

        // ── 场景 2（剧本）：generator 前两次调用网关超时 → 第三次成功，环境故障不占轮数 ──
        System.out.println("""
                ── 场景 2（剧本）：generator 超时两次，不算轮数 ──
                   剧本：超时 → 超时 → 初稿合格 → 评审 ACCEPT
                   maxRounds = 1：超时要算一轮，第一轮就顶死；环境故障单独计数（上限 3 次）
                """);
        ScriptedLlm flaky = new ScriptedLlm()
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                })
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                })
                .says("十二小时低温慢萃，把时间的味道融进每一滴。")
                .says("语言精炼、卖点清晰，符合要求。ACCEPT");

        var graph2 = new ReflectionGraph(flaky, 1).build().compile(
                CompileConfig.builder().checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver()).build());

        var finalState2 = graph2.invoke(GraphInput.args(
                Map.of("task", "为一款冷萃咖啡液写一句广告 slogan")),
                RunnableConfig.builder().threadId("reflection-scenario-env").build());

        finalState2.ifPresent(s -> System.out.println("\n  [定稿] " + s.draft()
                + "（共 " + s.round() + " 轮，终裁=" + s.verdict() + "，环境故障 " + s.envCount() + " 次）"));

        // ── 场景 3（剧本）：评审一直不通过，预算耗尽转人工（maxRounds=10 只是摆设）──
        System.out.println("""
                ── 场景 3（剧本）：评审一直不通过，预算耗尽转人工 ──
                   剧本：初稿 → 批语 NO → 修改稿 → 批语 NO → …（够烧 5 轮）
                   maxRounds = 10，预算 = 4 次 LLM 调用：
                   第 2 轮批改完恰好 4 次调用——不看轮数脸色，BUDGET_EXHAUSTED 收场
                """);
        var metered = new com.example.langgraph4j.llm.CountingLlm(new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：没有体现低温慢萃，评分 5/10。")
                .says("十二小时低温慢萃，唤醒每一刻。")
                .says("建议：还是差点意思，评分 6/10。")
                .says("继续修改的稿子")
                .says("建议：继续不行")
                .says("再改的稿子")
                .says("建议：再不行")
                .says("最后的稿子")
                .says("建议：最后也不行"));

        var graph3 = new ReflectionGraph(metered, 10, null,
                DEFAULT_MAX_ENV_FAILURES, Budget.calls(metered::calls, 4)).build().compile(
                CompileConfig.builder().checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver()).build());

        var finalState3 = graph3.invoke(GraphInput.args(
                Map.of("task", "为一款冷萃咖啡液写一句广告 slogan")),
                RunnableConfig.builder().threadId("reflection-scenario-budget").build());

        finalState3.ifPresent(s -> System.out.println("\n  [定稿] " + s.draft()
                + "（共 " + s.round() + " 轮，终裁=" + s.verdict() + "）"));
    }
}
