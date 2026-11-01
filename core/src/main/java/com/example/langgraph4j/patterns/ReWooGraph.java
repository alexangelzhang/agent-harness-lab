package com.example.langgraph4j.patterns;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.tool.Evaluator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：REWOO（Reasoning Without Observation）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 ReWooAgent）：</p>
 * <pre>
 *   planOutput = planner(question)          ──▶ planner 节点（一次 LLM 调用产出完整计划）
 *   for step in plan:                       ──▶ worker 节点自环 + 条件边（cursor vs 步数）
 *       vars[step.var] = tool(args, vars)   ──▶ worker 节点（variables 通道覆盖 = 手写版 vars Map）
 *       evidence.add(...)                   ──▶ evidence 通道追加
 *   return solver(question, evidence)       ──▶ solver 节点 → END
 * </pre>
 *
 * <p>图结构：{@code START → planner → worker ⇄(条件边) → solver → END}</p>
 * 相比 ReAct 的「LLM⇄工具」环，REWOO 的环里没有 LLM（worker 是纯 Java 工具执行），
 * 这正是它省 token 的本质 —— 图上一眼可见：环内只有 worker 一个节点。
 */
public class ReWooGraph {

    private static final Pattern STEP_PATTERN =
            Pattern.compile("#(E\\d+)\\s*=\\s*([A-Za-z0-9_]+)\\[(.*)\\]");

    // ---- State ----
    public static final class ReWooState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),                 // 问题（只写一次）
                "planOutput", Channels.base(() -> ""),               // planner 的原始输出（覆盖）
                "cursor", Channels.base(() -> 0),                    // 执行游标（覆盖）
                // variables：手写版的 vars Map<String,String> —— base 通道整图覆盖
                "variables", Channels.base((java.util.function.Supplier<Map<String, String>>) HashMap::new),
                "evidence", Channels.appender(ArrayList::new),       // 证据列表（追加）
                "finalAnswer", Channels.base(() -> ""));

        ReWooState(Map<String, Object> initData) {
            super(initData);
        }

        String question() {
            return this.<String>value("question").orElse("");
        }

        String planOutput() {
            return this.<String>value("planOutput").orElse("");
        }

        int cursor() {
            return this.<Integer>value("cursor").orElse(0);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> variables() {
            return this.<Map<String, String>>value("variables").orElseGet(Map::of);
        }
    }

    /** 一条计划：变量名、工具名、原始参数（可能含 #E 引用）。原始文本在每次 worker 执行时重新解析。 */
    record PlanStep(String variable, String toolName, String rawArgs) {
    }

    static final String PLANNER = "planner";
    static final String WORKER = "worker";
    static final String SOLVER = "solver";

    private final LlmClient llm;
    private final Map<String, Function<String, String>> tools;
    private final Map<String, String> toolHints;

    public ReWooGraph(LlmClient llm, Map<String, Function<String, String>> tools) {
        this(llm, tools, Map.of());
    }

    /** @param toolHints 每个工具的参数格式说明，进 planner 提示词（如 Maven → "参数为 groupId:artifactId"）。 */
    public ReWooGraph(LlmClient llm, Map<String, Function<String, String>> tools,
                      Map<String, String> toolHints) {
        this.llm = llm;
        this.tools = tools;
        this.toolHints = toolHints;
    }

    static List<PlanStep> parsePlan(String output) {
        List<PlanStep> steps = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher matcher = STEP_PATTERN.matcher(line.strip());
            if (matcher.find()) {
                steps.add(new PlanStep(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("无法从 Planner 输出解析计划: " + output);
        }
        return steps;
    }

    /** 工具一览：工具名 + 参数格式说明（如 Maven[groupId:artifactId]），进 planner 提示词。 */
    private String toolDescriptions() {
        List<String> lines = new ArrayList<>();
        for (String name : tools.keySet()) {
            lines.add(toolHints.containsKey(name) ? name + "[" + toolHints.get(name) + "]" : name);
        }
        return String.join(" / ", lines);
    }

    /** 变量替换：#E1 → E1 值中的关键数字（模拟「从证据中提取关键值」）。
     *  启发式：优先四位年份（19xx/20xx），其次第一个数字片段，都不是则原样传入。 */
    static String substitute(String args, Map<String, String> variables) {
        String result = args;
        for (var entry : variables.entrySet()) {
            Matcher year = Pattern.compile("(19|20)\\d{2}").matcher(entry.getValue());
            Matcher number = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(entry.getValue());
            String replacement = year.find() ? year.group()
                    : number.find() ? number.group()
                    : entry.getValue();
            result = result.replace("#" + entry.getKey(), replacement);
        }
        return result;
    }

    /** planner 节点：一次 LLM 调用生成链式计划（REWOO 省 token 的关键）。 */
    Map<String, Object> planner(ReWooState state) {
        String output = llm.complete("""
                请把用户的问题拆解为若干相互依赖的计划步骤。
                每个步骤包含：Plan i: 步骤描述 和 #Ei = 工具名[参数]（参数中可用 #Ej 引用前面步骤的结果）
                可用工具：%s
                问题：%s
                计划：""".formatted(toolDescriptions(), state.question()));
        System.out.println("  [planner 一次调用产出完整计划]\n    " + output.strip().replace("\n", "\n    "));
        return Map.of("planOutput", output.strip());
    }

    /** worker 节点：纯 Java，执行一步工具调用并做变量传递（对应手写版循环体）。 */
    Map<String, Object> worker(ReWooState state) {
        List<PlanStep> plan = parsePlan(state.planOutput());
        PlanStep step = plan.get(state.cursor());

        // 变量替换：手写版 substituteVariables(args, vars)；planner 可能给参数加引号，剥掉
        String args = substitute(step.rawArgs(), state.variables())
                .strip()
                .replaceAll("^[\"']+|[\"']+$", "");
        String result = tools.getOrDefault(step.toolName(), a -> "工具未注册: " + step.toolName()).apply(args);

        // 手写版 vars.put(step.variable, result) —— base 通道返回新 Map，整图覆盖
        Map<String, String> newVars = new HashMap<>(state.variables());
        newVars.put(step.variable(), result);

        System.out.println("  [worker 步骤 " + (state.cursor() + 1) + "/" + plan.size() + "] "
                + step.variable() + " = " + step.toolName() + "[" + args + "] -> " + result);

        return Map.of(
                "variables", newVars,
                "evidence", List.of(step.variable() + " = " + result),
                "cursor", state.cursor() + 1);
    }

    /** solver 节点：汇总证据生成最终答案（第二次也是最后一次 LLM 调用）。 */
    Map<String, Object> solver(ReWooState state) {
        // 证据截断：Search 结果可能很长，每条只保留头部 350 字（关键事实已置于 Search 输出最前）
        String evidence = state.<List<String>>value("evidence").orElseGet(List::of).stream()
                .map(e -> e.length() > 350 ? e.substring(0, 350) + "……" : e)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（无证据）");
        String output = llm.complete("""
                根据以下计划步骤及其执行结果（证据），回答用户的问题。

                问题：%s
                计划与证据：
                %s
                最终答案：""".formatted(state.question(), evidence));
        System.out.println("  [solver 汇总] " + output.strip());
        return Map.of("finalAnswer", output.strip());
    }

    /** 条件边：worker 自环直到计划执行完，再进 solver。 */
    String routeAfterWorker(ReWooState state) {
        int total = parsePlan(state.planOutput()).size();
        if (state.cursor() >= total) {
            return SOLVER;
        }
        return WORKER;
    }

    public StateGraph<ReWooState> build() throws Exception {
        return new StateGraph<>(ReWooState.SCHEMA, ReWooState::new)
                .addNode(PLANNER, node_async(this::planner))
                .addNode(WORKER, node_async(this::worker))
                .addNode(SOLVER, node_async(this::solver))
                .addEdge(StateGraph.START, PLANNER)
                .addEdge(PLANNER, WORKER)
                .addEdge(SOLVER, StateGraph.END)
                .addConditionalEdges(WORKER,
                        edge_async(this::routeAfterWorker),
                        Map.of(WORKER, WORKER, SOLVER, SOLVER));
    }

    /** 演示剧本与手写版一致：Google 创立年份 + 距今年数（全程仅 2 次 LLM 调用）。 */
    public static void demo() throws Exception {
        System.out.println("""
                ── 手写版（顺序执行）与本图的对照 ──
                   planOutput = planner(question)   → planner 节点（唯一一次规划 LLM 调用）
                   for step in plan                 → worker 节点自环 + 条件边（cursor）
                   vars[var] = tool(substitute)     → worker 节点（variables base 通道覆盖）
                   evidence.add(step)               → evidence appender 通道追加
                   return solver(question, evidence)→ solver 节点 → END（第二次 LLM 调用）
                图上一眼可见：环内只有 worker（纯 Java），没有 LLM —— 这就是 REWOO 省 token 的本质
                """);

        Map<String, Function<String, String>> tools = Map.of(
                "Search", query -> {
                    String lower = query.toLowerCase();
                    if (lower.contains("google")) {
                        return "Google 由拉里·佩奇和谢尔盖·布林于 1998 年创立。";
                    }
                    return "未找到与「" + query + "」相关的信息。";
                },
                "Calculator", expr -> {
                    double v = Evaluator.eval(expr);
                    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
                });

        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        Plan 1: 搜索 Google 的创立年份
                        #E1 = Search[Google 创立年份]
                        Plan 2: 用当前年份减去创立年份计算年数
                        #E2 = Calculator[2026-#E1]""")
                .says("Google 于 1998 年由拉里·佩奇和谢尔盖·布林创立，距今（2026 年）28 年。");

        var graph = new ReWooGraph(llm, tools).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", "Google 成立于哪一年？距今多少年了？")),
                RunnableConfig.builder().threadId("rewoo-demo").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
