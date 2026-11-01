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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：LLMCompiler（并行 Function Calling）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 LlmCompilerAgent）：
 * planner 生成 DAG → 调度器每轮把「依赖已就绪」的任务并行提交线程池 → Joiner 合并。</p>
 *
 * <p><b>建模决策</b>：DAG 是 planner 运行时才产出的<b>动态结构</b>，而 StateGraph 的
 * 节点/边是编译期静态定义的，无法「按 plan 动态加节点」。因此诚实的翻译是保留一个
 * <b>scheduler 节点</b>：节点内部用 {@link CompletableFuture} 把当前就绪任务并行执行
 * （并行度不减，对应手写版的线程池波次调度），靠条件边自环推进下一波，
 * 全部任务完成后 → joiner 节点合并结果。</p>
 *
 * <pre>
 *   START → planner → scheduler ──(条件边)── 未跑完：scheduler 自环（下一波并行）
 *                              └──▶ 全部完成 → joiner → END
 * </pre>
 * langgraph4j 1.9 也支持「静态并行分支」（RunnableConfig.addParallelNodeExecutor），
 * 适合编译期就知道哪些节点可并行的场景；本模式的 DAG 是运行期产物，故用调度器节点方案。
 */
public class LlmCompilerGraph {

    private static final Pattern TASK_PATTERN = Pattern.compile(
            "(\\d+)\\s*\\|\\s*(.*?)\\s*\\|\\s*([A-Za-z0-9_]+)\\[(.*)\\]\\s*\\|\\s*([\\d,\\s]*)");

    // ---- State ----
    public static final class CompilerState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),
                "dagOutput", Channels.base(() -> ""),               // planner 的 DAG 文本（覆盖）
                "results", Channels.appender(ArrayList::new),       // "任务id|结果"（追加，跨波次累积）
                "finalAnswer", Channels.base(() -> ""));

        CompilerState(Map<String, Object> initData) {
            super(initData);
        }

        String question() {
            return this.<String>value("question").orElse("");
        }

        String dagOutput() {
            return this.<String>value("dagOutput").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> results() {
            return this.<List<String>>value("results").orElseGet(List::of);
        }
    }

    record Task(int id, String toolName, String args, List<Integer> deps) {
        boolean isJoin() {
            return toolName.equals("Join");
        }
    }

    static final String PLANNER = "planner";
    static final String SCHEDULER = "scheduler";
    static final String JOINER = "joiner";

    private final LlmClient llm;
    private final Map<String, Function<String, String>> tools;

    public LlmCompilerGraph(LlmClient llm, Map<String, Function<String, String>> tools) {
        this.llm = llm;
        this.tools = tools;
    }

    static List<Task> parseDag(String output) {
        List<Task> tasks = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher matcher = TASK_PATTERN.matcher(line.strip());
            if (matcher.find()) {
                List<Integer> deps = new ArrayList<>();
                if (!matcher.group(5).isBlank()) {
                    for (String d : matcher.group(5).split(",")) {
                        deps.add(Integer.parseInt(d.trim()));
                    }
                }
                tasks.add(new Task(Integer.parseInt(matcher.group(1)), matcher.group(3),
                        matcher.group(4).trim(), deps));
            }
        }
        if (tasks.isEmpty()) {
            throw new IllegalStateException("无法解析 DAG: " + output);
        }
        return tasks;
    }

    /** 已完成的任务 id 集合（Join 任务不算，其结果由 joiner 生成）。 */
    static Set<Integer> doneIds(List<String> results) {
        Set<Integer> done = new HashSet<>();
        for (String r : results) {
            done.add(Integer.parseInt(r.split("\\|", 2)[0]));
        }
        return done;
    }

    /** planner 节点：生成 DAG 文本。 */
    Map<String, Object> planner(CompilerState state) {
        String output = llm.complete("""
                请把用户的问题拆解为一张任务 DAG（有向无环图），尽量识别出可以并行执行的任务。
                每行一个任务，格式：任务id | 描述 | 工具名[参数] | 依赖任务id(逗号分隔，无依赖留空)
                合并/汇总任务使用 Join[汇总说明]。
                可用工具：Search / Calculator
                问题：%s
                任务DAG：""".formatted(state.question()));
        System.out.println("  [planner 生成 DAG]\n    " + output.strip().replace("\n", "\n    "));
        return Map.of("dagOutput", output.strip());
    }

    /**
     * scheduler 节点：执行<b>当前波次</b>所有依赖就绪的任务（节点内部并行），
     * 对应手写版线程池的一轮提交。条件边会把图路由回 scheduler 跑下一波。
     */
    Map<String, Object> scheduler(CompilerState state) {
        List<Task> dag = parseDag(state.dagOutput());
        Set<Integer> done = doneIds(state.results());

        List<Task> ready = dag.stream()
                .filter(t -> !t.isJoin() && !done.contains(t.id()) && done.containsAll(t.deps()))
                .toList();
        if (ready.isEmpty()) {
            throw new IllegalStateException("DAG 中存在环或不可满足的依赖: " + dag);
        }

        // 节点内并行：对应手写版 ExecutorService 提交，波次内 CompletableFuture 并发执行
        List<CompletableFuture<String>> futures = ready.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> {
                    String result = tools.getOrDefault(task.toolName(), a -> "工具未注册: " + task.toolName())
                            .apply(task.args());
                    System.out.println("    [并行任务 " + task.id() + "] " + task.toolName()
                            + "[" + task.args() + "] -> " + result);
                    return task.id() + "|" + result;
                }))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        List<String> waveResults = futures.stream().map(CompletableFuture::join).toList();
        System.out.println("  [scheduler 就绪波次] 并行完成 " + waveResults.size() + " 个任务");
        // appender 通道：跨波次累积全部结果
        return Map.of("results", waveResults);
    }

    /** joiner 节点：合并全部任务结果，生成最终答案。 */
    Map<String, Object> joiner(CompilerState state) {
        String joined = String.join("\n", state.results()).replace("|", " 结果: ");
        String output = llm.complete("""
                请合并以下并行任务的结果，回答用户的问题。

                问题：%s
                任务结果：
                %s
                最终答案：""".formatted(state.question(), joined));
        System.out.println("  [joiner 合并] " + output.strip());
        return Map.of("finalAnswer", output.strip());
    }

    /** 条件边：还有任务没跑完 → scheduler 自环（下一波）；全部完成 → joiner。 */
    String routeAfterScheduler(CompilerState state) {
        List<Task> dag = parseDag(state.dagOutput());
        long total = dag.stream().filter(t -> !t.isJoin()).count();
        Set<Integer> done = doneIds(state.results());
        if (done.size() >= total) {
            System.out.println("  [路由] 全部 " + total + " 个任务完成 → joiner 合并");
            return JOINER;
        }
        System.out.println("  [路由] 已完成 " + done.size() + "/" + total + " → scheduler 再跑一波");
        return SCHEDULER;
    }

    public StateGraph<CompilerState> build() throws Exception {
        return new StateGraph<>(CompilerState.SCHEMA, CompilerState::new)
                .addNode(PLANNER, node_async(this::planner))
                .addNode(SCHEDULER, node_async(this::scheduler))
                .addNode(JOINER, node_async(this::joiner))
                .addEdge(StateGraph.START, PLANNER)
                .addEdge(PLANNER, SCHEDULER)
                .addEdge(JOINER, StateGraph.END)
                .addConditionalEdges(SCHEDULER,
                        edge_async(this::routeAfterScheduler),
                        Map.of(SCHEDULER, SCHEDULER, JOINER, JOINER));
    }

    /** 演示剧本与手写版一致：张译/吴京出生日期并行搜索 → 求年龄差。 */
    public static void demo() throws Exception {
        System.out.println("""
                ── 手写版（线程池波次调度）与本图的对照 ──
                   dag = planner(question)          → planner 节点
                   while 有就绪任务                  → scheduler 节点自环（条件边按完成数路由）
                   并行执行就绪任务                   → scheduler 节点内 CompletableFuture 并发（波次）
                   results.put(id, result)          → results appender 通道追加
                   return joiner(question, results) → joiner 节点 → END
                DAG 是运行期动态结构，StateGraph 编译期静态 —— 故并行度封装在 scheduler 节点内
                """);

        Map<String, Function<String, String>> tools = Map.of(
                "Search", query -> {
                    if (query.contains("张译")) {
                        return "张译，1978 年 2 月 17 日出生，中国内地男演员。";
                    }
                    if (query.contains("吴京")) {
                        return "吴京，1974 年 4 月 3 日出生，中国内地男演员、导演。";
                    }
                    return "未找到与「" + query + "」相关的信息。";
                },
                "Calculator", expr -> {
                    double v = Evaluator.eval(expr);
                    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
                });

        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        1 | 搜索张译的出生日期 | Search[张译 出生日期] |
                        2 | 搜索吴京的出生日期 | Search[吴京 出生日期] |
                        3 | 计算两人出生年份之差 | Calculator[1978-1974] | 1,2
                        4 | 合并结果给出结论 | Join[汇总两人出生年份与年龄差] | 3""")
                .says("吴京出生于 1974 年，张译出生于 1978 年，两人相差 4 岁。");

        var graph = new LlmCompilerGraph(llm, tools).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", "张译和吴京相差几岁？")),
                RunnableConfig.builder().threadId("compiler-demo").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
