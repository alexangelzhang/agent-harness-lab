package com.example.langgraph4j;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.tool.Evaluator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知乎文章《LangGraph 核心概念系统详解（结合手写 ReAct 对比，面试级理解）》的 Java 版实现，
 * 使用 LangGraph 的 Java 移植版 <b>langgraph4j</b>（org.bsc.langgraph4j:langgraph4j-core）。
 *
 * <p>文章五个概念与 langgraph4j 的对应：</p>
 * <pre>
 *   Python LangGraph                    langgraph4j
 *   ──────────────────────────────────  ─────────────────────────────────────
 *   StateGraph                          StateGraph（addNode/addEdge/addConditionalEdges）
 *   AgentState + Annotated[..., add]    AgentState + Channels.appender（追加策略）
 *   普通字段（默认覆盖）                  Channels.base（覆盖策略 + 默认值）
 *   Node（返回待更新字段 dict）           NodeAction（返回 Map&lt;String,Object&gt;）
 *   普通边 / 条件边 + 路由函数             addEdge / addConditionalEdges + EdgeAction
 *   graph.invoke(initial_state)         compiledGraph.invoke(GraphInput.args(Map), RunnableConfig)
 *   MemorySaver + checkpointer          CompileConfig.builder().checkpointSaver(new MemorySaver())
 *   __end__                             StateGraph.END
 * </pre>
 */
public class ArticleScenario {

    static final String LLM_NODE = "llm_think_node";
    static final String TOOL_NODE = "tool_node";

    // ---- 概念 1：State（全局数据容器）----
    // 对应文章：messages 用 Annotated[..., operator.add] 追加；普通字段直接覆盖。
    static final class ReActState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                // 对话历史：AppenderChannel = operator.add，追加而不是覆盖（最容易踩的坑）
                "messages", Channels.appender(ArrayList::new),
                // 以下普通字段：每次节点返回直接覆盖旧值
                "stepCount", Channels.base(() -> 0),
                "lastAction", Channels.base(() -> ""),
                "actionArgs", Channels.base(() -> ""),
                "finalAnswer", Channels.base(() -> ""));

        ReActState(Map<String, Object> initData) {
            super(initData);
        }

        @SuppressWarnings("unchecked")
        List<String> messages() {
            return this.<List<String>>value("messages").orElseGet(List::of);
        }

        int stepCount() {
            return this.<Integer>value("stepCount").orElse(0);
        }

        String lastAction() {
            return this.<String>value("lastAction").orElse("");
        }

        String actionArgs() {
            return this.<String>value("actionArgs").orElse("");
        }

        String finalAnswer() {
            return this.<String>value("finalAnswer").orElse("");
        }
    }

    // ---- 概念 2：Node（执行单元），固定签名：输入 State，返回待更新字段 Map ----

    /** LLM 推理节点：读取对话历史，生成 Thought + Action（对应文章 while 循环里的 LLM 调用）。 */
    static class LlmThinkNode implements org.bsc.langgraph4j.action.NodeAction<ReActState> {

        private static final Pattern ACTION = Pattern.compile("(?s)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");
        private final LlmClient llm;
        private final Map<String, Function<String, String>> tools;

        LlmThinkNode(LlmClient llm, Map<String, Function<String, String>> tools) {
            this.llm = llm;
            this.tools = tools;
        }

        @Override
        public Map<String, Object> apply(ReActState state) {
            StringBuilder history = new StringBuilder();
            state.messages().forEach(m -> history.append(m).append('\n'));

            String output = llm.complete("""
                    请按 Thought -> Action 的格式回答。
                    可用工具：%s
                    Action 格式：Action: 工具名[参数] 或 Action: Finish[答案]，每轮只输出一个 Action。

                    对话历史：
                    %s
                    Thought:""".formatted(String.join("、", tools.keySet()), history));
            System.out.println("  [" + LLM_NODE + "] " + output.strip());

            Matcher matcher = ACTION.matcher(output);
            if (!matcher.find()) {
                // 真实模型漂移兜底：偶尔只吐 Thought 不吐 Action 行（或首跳直接答完）。
                // 按「直接终答」处理而不是炸掉整轮——答案质量由模型负责，循环骨架负责不死。
                System.out.println("  [" + LLM_NODE + "] 输出里没有 Action 行——按直接终答收口"
                        + "（真实模型漂移兜底，不炸循环）");
                return Map.of(
                        "messages", List.of("Assistant: " + output.strip()),
                        "stepCount", state.stepCount() + 1,
                        "lastAction", "Finish",
                        "actionArgs", output.strip(),
                        "finalAnswer", output.strip());
            }
            String action = matcher.group(1);
            String args = matcher.group(2).trim();

            // 只返回要更新的字段；messages 通过 appender 追加，不会覆盖历史
            return Map.of(
                    "messages", List.of("Assistant: " + output.strip()),
                    "stepCount", state.stepCount() + 1,
                    "lastAction", action,
                    "actionArgs", args,
                    "finalAnswer", action.equals("Finish") ? args : state.finalAnswer());
        }
    }

    /** 工具执行节点：解析 Action，查工具表执行，把结果（Observation）追加进 State。 */
    static class ToolExecuteNode implements org.bsc.langgraph4j.action.NodeAction<ReActState> {

        private final Map<String, Function<String, String>> tools;

        ToolExecuteNode(Map<String, Function<String, String>> tools) {
            this.tools = tools;
        }

        @Override
        public Map<String, Object> apply(ReActState state) {
            Function<String, String> tool = tools.get(state.lastAction());
            String result = tool != null
                    ? tool.apply(state.actionArgs())
                    : "工具未注册: " + state.lastAction();
            System.out.println("  [" + TOOL_NODE + "] " + state.lastAction() + "[" + state.actionArgs() + "] -> " + result);

            return Map.of(
                    "messages", List.of("Observation: " + result),
                    "lastAction", ""); // 清空动作，避免重复执行
        }
    }

    // ---- 概念 3：Edge（边）----

    /** 路由函数：条件边的灵魂 —— 读取 State 决定下一个节点（对应手写 if-else 分支）。 */
    static class RouteAfterLlm implements org.bsc.langgraph4j.action.EdgeAction<ReActState> {

        private final int maxSteps;

        RouteAfterLlm(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        @Override
        public String apply(ReActState state) {
            if ("Finish".equals(state.lastAction())) {
                return StateGraph.END; // __end__：图终止
            }
            if (state.stepCount() >= maxSteps) { // 对应文章：State 步数字段 + 路由判断防死循环
                System.out.println("  [路由] 达到最大步数 " + maxSteps + "，强制结束");
                return StateGraph.END;
            }
            if (state.lastAction().isEmpty()) {
                return StateGraph.END;
            }
            return TOOL_NODE;
        }
    }

    public static void main(String[] args) throws Exception {
        scenario1CompileAndRun();
        scenario2CheckpointAndInterrupt();
        printMappingTable();
        // langgraph4j 内部的异步执行器是非 daemon 线程，演示结束后显式退出
        System.exit(0);
    }

    /** 概念 3/4：建图、编译、运行（stream 逐步观察 State 演化）。 */
    static void scenario1CompileAndRun() throws Exception {
        banner("概念 1-4：State / Node / Edge / Compile —— ReAct 状态图");

        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        Thought: 需要计算 1024*256，用计算器工具。
                        Action: Calculator[1024*256]""")
                .says("""
                        Thought: 已得到结果 262144，任务完成。
                        Action: Finish[1024 × 256 = 262144]""");

        var graph = buildGraph(llm, 5).compile();

        var finalState = graph.invoke(GraphInput.args(
                Map.of("messages", List.of("User: 1024 乘以 256 等于多少？"))),
                RunnableConfig.builder().threadId("scenario1").build());
        System.out.println("[invoke] 逐步执行后的最终 State：");
        finalState.ifPresent(s -> {
            s.messages().forEach(m -> System.out.println("  " + abbreviate(m)));
            System.out.println("\n[invoke] 最终答案: " + s.finalAnswer());
        });
    }

    /** 概念 5：Checkpoint 持久化 —— MemorySaver + 中断（人工审批）+ 恢复 + 多轮记忆。 */
    static void scenario2CheckpointAndInterrupt() throws Exception {
        banner("概念 5：Checkpoint（MemorySaver + interruptBefore + resume）");

        ScriptedLlm llm = new ScriptedLlm()
                .says("""
                        Thought: 需要计算 1024*256。
                        Action: Calculator[1024*256]""")
                .says("""
                        Thought: 得到结果，给出最终答案。
                        Action: Finish[1024 × 256 = 262144]""")
                .says("""
                        Thought: 继续计算 512*2。
                        Action: Calculator[512*2]""")
                .says("""
                        Thought: 得到结果，给出最终答案。
                        Action: Finish[512 × 2 = 1024]""");

        // 编译时绑定 CheckpointSaver，并在工具节点前设置中断（Human-in-the-loop）
        var compiled = buildGraph(llm, 5).compile(
                CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .interruptBefore(TOOL_NODE)
                        .build());

        var config = RunnableConfig.builder().threadId("scenario-thread").build();

        // 第一轮：会在 tool_node 前中断 —— 模拟「敏感工具执行前等待人工确认」
        System.out.println("第一轮提问：1024 乘以 256 等于多少？");
        Optional<ReActState> interrupted = compiled.invoke(GraphInput.args(
                Map.of("messages", List.of("User: 1024 乘以 256 等于多少？"))), config);
        interrupted.ifPresent(s ->
                System.out.println("  [中断] 停在工具执行前。当前最后一条消息: " + abbreviate(s.messages().get(s.messages().size() - 1))));

        // 中断点的快照已写入 MemorySaver（resume 依赖它们恢复执行）
        var historyAtInterrupt = compiled.getStateHistory(config);
        System.out.println("  [Checkpoint 历史] 中断点已保存 " + historyAtInterrupt.size() + " 个快照（可回滚/恢复）");

        // 人工确认后恢复执行
        System.out.println("  [人工确认] 允许执行工具，恢复运行...");
        var resumed = compiled.invoke(GraphInput.resume(), config);
        System.out.println("  [恢复后最终答案] " + resumed.map(ReActState::finalAnswer).orElse("（无）"));

        // 多轮对话：同一 threadId 再次 invoke，会再次在中断点暂停，确认后再恢复
        System.out.println("\n第二轮提问（同一线程）: 那 512 乘以 2 呢？");
        var second = compiled.invoke(GraphInput.args(
                Map.of("messages", List.of("User: 那 512 乘以 2 呢？"))), config);
        second.ifPresent(s ->
                System.out.println("  [再次中断] 停在工具执行前，最后一条消息: " + abbreviate(s.messages().get(s.messages().size() - 1))));
        var secondResumed = compiled.invoke(GraphInput.resume(), config);
        secondResumed.ifPresent(s ->
                System.out.println("  [恢复后最终答案] " + s.finalAnswer()));
    }

    /** 默认工具集（与文章演示一致）：只有计算器。 */
    static Map<String, Function<String, String>> defaultTools() {
        return Map.of("Calculator", calculatorTool());
    }

    /** 计算器工具：数学表达式求值（供默认工具集与 RealScenario 复用）。 */
    static Function<String, String> calculatorTool() {
        return expr -> {
            double v = Evaluator.eval(expr);
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        };
    }

    /** 建图：节点 + 普通边（tool -> llm 的环）+ 条件边（路由函数）。 */
    static StateGraph<ReActState> buildGraph(LlmClient llm, int maxSteps) throws Exception {
        return buildGraph(llm, maxSteps, defaultTools());
    }

    /** 建图（可传入自定义工具集，如真实 LLM 演示里的 Search）。 */
    static StateGraph<ReActState> buildGraph(LlmClient llm, int maxSteps,
                                             Map<String, Function<String, String>> tools) throws Exception {
        return new StateGraph<>(ReActState.SCHEMA, ReActState::new)
                .addNode(LLM_NODE, AsyncNodeAction.node_async(new LlmThinkNode(llm, tools)))
                .addNode(TOOL_NODE, AsyncNodeAction.node_async(new ToolExecuteNode(tools)))
                // 入口
                .addEdge(StateGraph.START, LLM_NODE)
                // 条件边：LLM 之后根据路由函数决定 去工具节点 还是 结束
                // 注意：END 常量的值是 "__END__"（大写），pathMap 的键必须与路由返回值精确匹配
                .addConditionalEdges(LLM_NODE,
                        AsyncEdgeAction.edge_async(new RouteAfterLlm(maxSteps)),
                        Map.of(TOOL_NODE, TOOL_NODE, StateGraph.END, StateGraph.END))
                // 普通边：工具执行完，固定回到 LLM 继续思考（这个环就是手写 ReAct 的 while 循环）
                .addEdge(TOOL_NODE, LLM_NODE);
    }

    static void printMappingTable() {
        banner("手写 ReAct ↔ LangGraph4j 对照表（面试秒答）");
        System.out.println("""
                手写 ReAct                              LangGraph4j
                ──────────────────────────────────────  ─────────────────────────────────────────
                self.messages 全局列表                  State 里的 messages（Channels.appender 追加）
                while True 循环                         边形成的环（tool_node -> llm_think_node）
                if 有工具调用 else 结束                 条件边 + RouteAfterLlm 路由函数
                llm.chat() 逻辑                         LlmThinkNode
                execute_tool() 函数                     ToolExecuteNode
                max_steps 防死循环                      stepCount 字段 + 路由函数判断
                手动保存对话                            MemorySaver Checkpoint 自动持久化
                """);
    }

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  " + title);
        System.out.println("=".repeat(66));
    }

    static String abbreviate(String s) {
        return s.length() <= 90 ? s : s.substring(0, 90) + "...";
    }
}
