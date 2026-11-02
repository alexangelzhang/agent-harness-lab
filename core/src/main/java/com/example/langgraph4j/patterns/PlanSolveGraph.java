package com.example.langgraph4j.patterns;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：Plan & Solve（先计划再执行）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 PlanAndSolveAgent）：
 * <pre>
 *   plan = planner(question)                     ──▶ planner 节点（plan/cursor 通道更新）
 *   while cursor < plan.size():                  ──▶ 条件边：cursor 与 plan.size() 比较
 *       result = executor(question, step)        ──▶ executor 节点（每次只执行一步，自环回到自己）
 *       if isFailure(result):                    ──▶ 条件边：失败 → replanner
 *           plan = replanner(...); cursor = 0    ──▶ replanner 节点（plan 覆盖 + cursor 归零）
 *       else: cursor++                           ──▶ 条件边：成功且未走完 → executor（自环）
 *   return synthesize(completed)                 ──▶ finalizer 节点 → END
 * </pre>
 * 图结构：START → planner → executor ⇄ (条件边: 失败→replanner→executor / 成功→executor 自环 / 走完→finalizer) → END
 */
public class PlanSolveGraph {

    // ---- State：手写版的 question/plan/completed/cursor 局部变量 ----
    public static final class PlanState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),             // 原始问题（只写一次）
                "plan", Channels.base(() -> List.<String>of()),  // 当前计划（覆盖：planner/replanner 改写）
                "cursor", Channels.base(() -> 0),                // 执行游标（覆盖：对应手写版 cursor 变量）
                "stepResult", Channels.base(() -> ""),           // 最近一步结果（覆盖）
                "completed", Channels.appender(ArrayList::new)); // 已完成步骤（追加：对应 completed.add）

        PlanState(Map<String, Object> initData) {
            super(initData);
        }

        String question() {
            return this.<String>value("question").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> plan() {
            return this.<List<String>>value("plan").orElseGet(List::of);
        }

        int cursor() {
            return this.<Integer>value("cursor").orElse(0);
        }

        String stepResult() {
            return this.<String>value("stepResult").orElse("");
        }
    }

    static final String PLANNER = "planner";
    static final String EXECUTOR = "executor";
    static final String REPLANNER = "replanner";
    static final String FINALIZER = "finalizer";

    private static final Pattern ACTION = Pattern.compile("(?s)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    private final LlmClient llm;
    private final Map<String, Function<String, String>> tools;

    public PlanSolveGraph(LlmClient llm, Map<String, Function<String, String>> tools) {
        this.llm = llm;
        this.tools = tools;
    }

    static boolean isFailure(String result) {
        return result.contains("工具执行出错") || result.contains("工具未注册")
                || result.contains("未找到") || result.contains("抓取失败")
                || result.contains("不存在");
    }

    static List<String> parseNumberedSteps(String output) {
        List<String> steps = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.matches("^\\d+[.、)]\\s*.*")) {
                steps.add(trimmed.replaceFirst("^\\d+[.、)]\\s*", ""));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("无法从 LLM 输出解析计划: " + output);
        }
        return steps;
    }

    /** planner 节点：生成编号计划（对应手写版 planner()）。 */
    Map<String, Object> planner(PlanState state) {
        String output = llm.complete("""
                你是一个任务规划器。请把用户的问题拆解为若干有序的、可执行的具体步骤。
                只输出编号步骤，每行一条，不要输出其他内容。

                问题：%s
                计划：""".formatted(state.question()));
        List<String> steps = parseNumberedSteps(output);
        System.out.println("  [planner 生成计划] " + steps);
        // 对应手写版 plan = planner(question); cursor = 0
        return Map.of("plan", steps, "cursor", 0);
    }

    /** executor 节点：每次只执行 plan[cursor] 一步（对应手写版循环体的一次迭代）。 */
    Map<String, Object> executor(PlanState state) {
        String step = state.plan().get(state.cursor());
        String output = llm.complete("""
                请完成下面这个任务步骤。你可以直接使用工具，工具调用格式为 Action: 工具名[参数]。
                如果不需要工具，直接给出该步骤的完成结果。

                原始问题：%s
                当前步骤：%s
                回答：""".formatted(state.question(), step));

        String result = output.strip();
        Matcher matcher = ACTION.matcher(output);
        if (matcher.find()) { // 对应手写版 executor 的工具调用分支
            String toolName = matcher.group(1);
            String args = matcher.group(2).trim();
            result = tools.getOrDefault(toolName, a -> "工具未注册: " + toolName).apply(args);
            System.out.println("  [executor 步骤 " + (state.cursor() + 1) + "/" + state.plan().size() + "] "
                    + step + " → Action: " + toolName + "[" + args + "] → " + result);
        } else {
            System.out.println("  [executor 步骤 " + (state.cursor() + 1) + "/" + state.plan().size() + "] "
                    + step + " → " + result);
        }

        // stepResult 供条件边判断是否失败；completed 追加记录；cursor 先 +1，失败时由 replanner 归零
        return Map.of(
                "stepResult", result,
                "completed", List.of(step + " => " + result),
                "cursor", state.cursor() + 1);
    }

    /** replanner 节点：某步失败，重规划剩余步骤（对应手写版 replanner() + cursor=0）。 */
    Map<String, Object> replanner(PlanState state) {
        System.out.println("  [replanner] 步骤失败（" + state.stepResult() + "），重规划剩余步骤");
        String output = llm.complete("""
                原计划执行中某一步失败了，请根据目标重新规划剩余的步骤。
                只输出剩余步骤的编号列表（从 1 开始重新编号），不要输出其他内容。

                目标：%s
                当前计划：%s
                剩余步骤计划：""".formatted(state.question(), state.plan()));
        List<String> steps = parseNumberedSteps(output);
        System.out.println("  [replanner 新计划] " + steps);
        // 对应手写版 plan = replanner(...); cursor = 0
        return Map.of("plan", steps, "cursor", 0);
    }

    /** finalizer 节点：汇总已完成步骤，生成最终回答（对应手写版最后的 synthesize 调用）。 */
    Map<String, Object> finalizer(PlanState state) {
        String completed = String.join("\n", state.<List<String>>value("completed").orElseGet(List::of));
        String output = llm.complete("""
                基于以下已完成的计划步骤及其结果，给出对原始问题的最终回答（一句话）。

                原始问题：%s
                已完成步骤：
                %s
                最终回答：""".formatted(state.question(), completed));
        System.out.println("  [finalizer 汇总] " + output.strip());
        return Map.of("finalAnswer", output.strip());
    }

    /** 条件边路由（executor 之后）：对应手写版 while 循环体的 if-else 分支。 */
    String routeAfterExecutor(PlanState state) {
        if (isFailure(state.stepResult())) {
            return REPLANNER;
        }
        if (state.cursor() >= state.plan().size()) {
            return FINALIZER;
        }
        return EXECUTOR; // 自环：继续执行下一步（对应手写版 cursor++ 后的下一次循环）
    }

    public StateGraph<PlanState> build() throws Exception {
        return new StateGraph<>(PlanState.SCHEMA, PlanState::new)
                .addNode(PLANNER, node_async(this::planner))
                .addNode(EXECUTOR, node_async(this::executor))
                .addNode(REPLANNER, node_async(this::replanner))
                .addNode(FINALIZER, node_async(this::finalizer))
                .addEdge(StateGraph.START, PLANNER)
                .addEdge(PLANNER, EXECUTOR)
                .addEdge(REPLANNER, EXECUTOR)
                .addEdge(FINALIZER, StateGraph.END)
                .addConditionalEdges(EXECUTOR,
                        edge_async(this::routeAfterExecutor),
                        Map.of(REPLANNER, REPLANNER, FINALIZER, FINALIZER,
                                EXECUTOR, EXECUTOR, StateGraph.END, StateGraph.END));
    }

    /** 运行演示：查订单 + 黄金价格（与手写版同一剧本，含一次失败重规划）。 */
    public static void runScenario() throws Exception {
        System.out.println("""
                ── 手写版（while/if 循环）与本图的对照 ──
                   plan = planner(question)            → planner 节点
                   while cursor < plan.size()          → 条件边 routeAfterExecutor（cursor vs plan.size()）
                   result = executor(step)             → executor 节点（自环 = 循环的下一次迭代）
                   if isFailure(result)                → 条件边 → replanner 节点 → 回到 executor
                   cursor++                            → executor 返回 cursor+1
                   return synthesize(completed)        → finalizer 节点 → END
                """);

        Map<String, Function<String, String>> tools = Map.of(
                "QueryOrder", orderId -> Map.of(
                                "12345", "订单 12345：状态=已发货，商品=《深入理解Java虚拟机》，金额=99.00 元",
                                "67890", "订单 67890：状态=待支付，商品=机械键盘，金额=399.00 元")
                        .getOrDefault(orderId.trim(), "订单 " + orderId + " 不存在"),
                "Search", query -> query.contains("黄金价格")
                        ? "今日黄金价格约 612 元/克（模拟数据）。"
                        : "未找到与「" + query + "」相关的信息。");

        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        1. 查询订单 12345 的状态
                        2. 查询订单 12345 的退款政策
                        3. 汇总信息并回答用户""")
                .says("Action: QueryOrder[12345]")
                .says("Action: QueryOrder[12345 退款政策]")
                .says("""
                        1. 查询今日黄金价格
                        2. 汇总信息并回答用户""")
                .says("Action: Search[黄金价格]")
                .says("订单 12345 已发货（《深入理解Java虚拟机》，99.00 元），今日黄金价格约 612 元/克。")
                .says("订单 12345 已发货，商品为《深入理解Java虚拟机》，金额 99.00 元；今日黄金价格约 612 元/克。");

        var graph = new PlanSolveGraph(llm, tools).build().compile(
                CompileConfig.builder().checkpointSaver(new org.bsc.langgraph4j.checkpoint.MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", "帮我查一下订单 12345 的状态，再告诉我今天黄金价格")),
                RunnableConfig.builder().threadId("plansolve-场景").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终回答] " + s.<String>value("finalAnswer").orElse("")));
    }
}
