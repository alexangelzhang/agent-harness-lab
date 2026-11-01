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
 * 模式 10（系列补充）：Multi-Agent（多智能体协作）—— langgraph4j 状态图版。
 *
 * <p>九大模式原文没有覆盖 Multi-Agent，这里作为系列补充。它是已翻译模式里唯一一个
 * <b>图优先</b>的模式：其他模式都是「手写 while 循环 → 翻译成图的环」，而多智能体的
 * 协作拓扑<b>本身就是一张图</b>——角色是节点、派单路由是条件边、对话记录是共享 State，
 * 不存在翻译问题，反而是 langgraph4j 的主场（官方 multi-agent 示例就是 supervisor 拓扑）。</p>
 *
 * <p>拓扑（Supervisor 中心调度）：</p>
 * <pre>
 *                 ┌──────────────────────────┐
 *                 │                          ▼
 *   START → supervisor ──(条件边)── NEXT:Researcher → researcher ──┐
 *            │ └─ NEXT:Calculator  → calculator  ──────────────┤
 *            └── FINISH ────────────────────────────────────────▶ END
 * </pre>
 * <ul>
 *   <li>supervisor 节点：读任务 + 对话记录，决策「NEXT: 角色名」派单或「FINISH: 最终答案」收工；</li>
 *   <li>worker 节点（每个角色一个<b>静态节点</b>）：LLM 以角色身份回应，可输出
 *       {@code Action: 工具名[参数]} 调用自己的工具，结果进对话记录；</li>
 *   <li>所有 worker 完成后无条件边回到 supervisor，形成调度环；</li>
 *   <li>transcript appender 通道 = 共享黑板，supervisor 与 worker 都能读到全部历史。</li>
 * </ul>
 */
public class MultiAgentGraph {

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "(?is)(NEXT|FINISH)\\s*[:：]\\s*(.+)");  // (?s)：FINISH 的最终答案可能是多行
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "(?i)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    /** 一个 worker 角色：名字 + 职责描述 + 自己专属的工具。 */
    public record Worker(String name, String duty, String toolName, Function<String, String> tool) {
    }

    // ---- State ----
    public static final class MultiAgentState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "task", Channels.base(() -> ""),                 // 用户任务
                "transcript", Channels.appender(ArrayList::new),    // "角色: 回应" 共享黑板（追加）
                "round", Channels.base(() -> 0),                // 调度轮数（supervisor 覆盖）
                "next", Channels.base(() -> ""),                 // supervisor 的决策（路由用）
                "finalAnswer", Channels.base(() -> ""));

        MultiAgentState(Map<String, Object> initData) {
            super(initData);
        }

        String task() {
            return this.<String>value("task").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> transcript() {
            return this.<List<String>>value("transcript").orElseGet(List::of);
        }

        int round() {
            return this.<Integer>value("round").orElse(0);
        }

        String next() {
            return this.<String>value("next").orElse("");
        }
    }

    static final String SUPERVISOR = "supervisor";

    private final LlmClient llm;
    private final List<Worker> workers;
    private final int maxRounds;

    public MultiAgentGraph(LlmClient llm, List<Worker> workers, int maxRounds) {
        this.llm = llm;
        this.workers = workers;
        this.maxRounds = maxRounds;
    }

    private String describeWorkers() {
        StringBuilder sb = new StringBuilder();
        for (Worker w : workers) {
            sb.append(" - ").append(w.name()).append("：").append(w.duty()).append("\n");
        }
        return sb.toString().strip();
    }

    /** supervisor 节点：读任务 + 黑板，决策派单（NEXT: 角色）或收工（FINISH: 答案）。 */
    Map<String, Object> supervisor(MultiAgentState state) {
        int round = state.round() + 1;
        if (round > maxRounds) {
            System.out.println("  [supervisor] 达到最大调度轮数 " + maxRounds + "，强制收工");
            return Map.of(
                    "round", round,
                    "finalAnswer", "达到最大调度轮数仍未收敛：" + String.join(" | ", state.transcript()),
                    "next", StateGraph.END);
        }
        String transcript = state.transcript().isEmpty() ? "（还没有对话）"
                : String.join("\n", state.transcript());
        String output = llm.complete("""
                你是多智能体系统的调度者。根据任务与对话记录，决定下一步：
                输出「NEXT: 角色名」派对应角色出手，或「FINISH: 最终答案」结束任务。

                任务：%s
                对话记录：
                %s
                各角色职责：
                %s
                决策：""".formatted(state.task(), transcript, describeWorkers()));
        System.out.println("  [supervisor 第 " + round + " 轮] " + output.strip());

        Matcher matcher = DECISION_PATTERN.matcher(output.strip());
        if (!matcher.find()) {
            // 兜底：输出不符合协议时，把整段输出当作最终答案收工（与手写系列一致的宽容策略）
            return Map.of("round", round, "finalAnswer", output.strip(), "next", StateGraph.END);
        }
        boolean finish = matcher.group(1).equalsIgnoreCase("FINISH");
        if (finish) {
            return Map.of("round", round, "finalAnswer", matcher.group(2).strip(),
                    "next", StateGraph.END);
        }
        // NEXT 只取第一行（(?s) 模式下组内容可能跨行，角色名必须单行）
        String who = matcher.group(2).lines().findFirst().orElse("").strip();
        boolean known = workers.stream().anyMatch(w -> w.name().equals(who));
        if (!known) {
            System.out.println("  [supervisor] 未知角色「" + who + "」，按 FINISH 收工");
            return Map.of("round", round, "finalAnswer", output.strip(), "next", StateGraph.END);
        }
        return Map.of("round", round, "next", who);
    }

    /** 条件边：supervisor 决策路由——角色名 → 对应 worker 节点；END → 结束。 */
    String routeAfterSupervisor(MultiAgentState state) {
        return state.next();
    }

    /** worker 节点执行体：每个角色节点闭包捕获自己的 Worker 定义。 */
    Map<String, Object> workerNode(MultiAgentState state, Worker worker) {
        String transcript = state.transcript().isEmpty() ? "（还没有对话）"
                : String.join("\n", state.transcript());
        String output = llm.complete("""
                你是「%s」，职责：%s。
                请只完成你职责内的这一部分，不要越俎代庖。

                任务：%s
                对话记录：
                %s
                如需使用你的工具（%s），输出：Action: %s[参数]；否则直接给出结论。
                你的回应：""".formatted(worker.name(), worker.duty(), state.task(),
                transcript, worker.toolName(), worker.toolName()));
        System.out.println("  [" + worker.name() + "] " + output.strip());

        String entry;
        Matcher action = ACTION_PATTERN.matcher(output.strip());
        if (action.find()) {
            String observation = worker.tool().apply(action.group(2).trim());
            System.out.println("    [" + worker.toolName() + "[" + action.group(2).trim() + "] -> "
                    + observation + "]");
            entry = worker.name() + ": " + observation;
        } else {
            entry = worker.name() + ": " + output.strip();
        }
        return Map.of("transcript", List.of(entry));  // appender：进共享黑板
    }

    public StateGraph<MultiAgentState> build() throws Exception {
        StateGraph<MultiAgentState> g = new StateGraph<>(MultiAgentState.SCHEMA, MultiAgentState::new)
                .addNode(SUPERVISOR, node_async(this::supervisor))
                .addEdge(StateGraph.START, SUPERVISOR)
                .addConditionalEdges(SUPERVISOR,
                        edge_async(this::routeAfterSupervisor),
                        pathMap());
        for (Worker w : workers) {
            Worker captured = w;
            g = g.addNode(captured.name(), node_async(s -> workerNode(s, captured)))
                    .addEdge(captured.name(), SUPERVISOR);  // 干完活回调度者
        }
        return g;
    }

    /** pathMap：角色名 → 角色节点；END → END。 */
    Map<String, String> pathMap() {
        Map<String, String> map = new HashMap<>();
        for (Worker w : workers) {
            map.put(w.name(), w.name());
        }
        map.put(StateGraph.END, StateGraph.END);
        return map;
    }

    /** 演示剧本：调度 2 轮（Researcher 查事实 → Calculator 算年数）后 FINISH。 */
    public static void demo() throws Exception {
        System.out.println("""
                ── Multi-Agent（系列补充，图优先模式）的建模 ──
                   角色                     → worker 静态节点（每个角色一个，闭包捕获职责与工具）
                   派单/收工决策             → supervisor 节点 + 条件边（NEXT: 角色 / FINISH: 答案）
                   调度环                   → worker ──▶ supervisor 无条件边
                   共享对话记录             → transcript appender 通道（黑板，全员可读）
                多智能体的协作拓扑本身就是图 —— 这是唯一一个「不需要翻译」的模式
                """);

        Map<String, Function<String, String>> tools = Map.of(
                "Search", q -> q.contains("Google")
                        ? "Google 于 1998 年 9 月 4 日成立。"
                        : "未找到与「" + q + "」相关的信息。",
                "Calculator", expr -> {
                    double v = Evaluator.eval(expr);
                    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
                });

        List<Worker> workers = List.of(
                new Worker("Researcher", "负责事实性问题，用搜索工具查证关键信息",
                        "Search", tools.get("Search")),
                new Worker("Calculator", "负责数值计算", "Calculator", tools.get("Calculator")));

        ScriptedLlm llm = new ScriptedLlm()
                // supervisor 派单：先查事实
                .says("NEXT: Researcher")
                // Researcher 用搜索工具
                .says("Action: Search[Google 成立年份]")
                // supervisor 派单：再算年数
                .says("NEXT: Calculator")
                // Calculator 计算
                .says("Action: Calculator[2026-1998]")
                // supervisor 汇总收工
                .says("FINISH: 维基百科记载，Google 成立于 1998 年 9 月 4 日，距今（2026 年）28 年。");

        var graph = new MultiAgentGraph(llm, workers, 6).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", "维基百科记载，Google 成立于哪一年？距今（2026 年）多少年了？")),
                RunnableConfig.builder().threadId("multiagent-demo").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
