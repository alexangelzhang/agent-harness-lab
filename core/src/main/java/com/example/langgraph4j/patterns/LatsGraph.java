package com.example.langgraph4j.patterns;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：LATS（Language Agent Tree Search）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 LatsAgent）：
 * Expand（LLM 生成 k 个候选动作）→ Execute（逐个调工具得 Observation）→
 * Evaluate（LLM 对每个节点打 1~10 分）→ Descend（选最高分深入；
 * 若全是死路则 Reflect 生成语言反思并回溯父节点重扩）→ Finish 分支即答案。</p>
 *
 * <p><b>建模决策</b>：与 LLMCompiler 同理——搜索树是<b>运行时逐层分叉的动态结构</b>，
 * 而 StateGraph 的节点/边是编译期静态定义的，无法「按树动态加节点」。诚实的翻译是保留一个
 * <b>search 节点完成一整轮迭代</b>（expand→execute→evaluate→descend 在一次节点调用内完成，
 * 候选分支在节点内部以 List 展开），树的结构数据存入 State 的 base 通道；
 * 死路回溯不是图的回边，而是「reflect 节点生成语言反思 → 无条件边回到 search 重扩」：</p>
 *
 * <pre>
 *   START → search ──(条件边)── finish          → END
 *                    ├── deadend        → reflect → search（重扩，回溯语义）
 *                    ├── continue       → search 自环（下一层深度）
 *                    └── maxdepth/failed → END
 * </pre>
 * 手写版的「退回父节点重新扩展」在图版中体现为：search 节点把 currentId 改写为父节点 id，
 * reflect 节点把反思追加进 reflections appender 通道，二者经边汇合后 search 基于父轨迹重扩。
 */
public class LatsGraph {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "(?i)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    /** 搜索树节点的可序列化形态（对应手写版 LatsAgent.Node，parent 指针改为 parentId）。 */
    public record TreeNode(int id, Integer parentId, String thought, String actionName,
                           String actionInput, String observation,
                           double reward, boolean deadEnd) implements java.io.Serializable {

        @java.io.Serial
        private static final long serialVersionUID = 1L;


        boolean isFinish() {
            return "Finish".equals(actionName);
        }

        TreeNode withReward(double reward) {
            return new TreeNode(id, parentId, thought, actionName, actionInput, observation, reward, deadEnd);
        }

        TreeNode withDeadEnd() {
            return new TreeNode(id, parentId, thought, actionName, actionInput, observation, reward, true);
        }
    }

    record Action(String name, String input) {
    }

    // ---- State ----
    public static final class LatsState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),               // 用户问题
                "depth", Channels.base(() -> 0),                  // 当前搜索深度
                "tree", Channels.base(ArrayList::new),            // TreeNode 列表（整个搜索树）
                "currentId", Channels.base(() -> -1),             // 当前节点 id（-1 = 根，channel 默认值不允许 null）
                "deadEndTrace", Channels.base(() -> ""),          // 死路分支轨迹（reflect 的输入）
                "reflections", Channels.appender(ArrayList::new),   // 语言反思累积（回溯警示）
                "finalAnswer", Channels.base(() -> ""),           // Finish 分支的答案
                "status", Channels.base(() -> ""));               // 本轮迭代结果：finish/continue/deadend/failed

        LatsState(Map<String, Object> initData) {
            super(initData);
        }

        String question() {
            return this.<String>value("question").orElse("");
        }

        int depth() {
            return this.<Integer>value("depth").orElse(0);
        }

        @SuppressWarnings("unchecked")
        List<TreeNode> tree() {
            return this.<List<TreeNode>>value("tree").orElseGet(List::of);
        }

        /** 当前节点 id；-1 表示树根（尚无节点）。 */
        int currentId() {
            return this.<Integer>value("currentId").orElse(-1);
        }

        String deadEndTrace() {
            return this.<String>value("deadEndTrace").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> reflections() {
            return this.<List<String>>value("reflections").orElseGet(List::of);
        }

        String status() {
            return this.<String>value("status").orElse("");
        }
    }

    static final String SEARCH = "search";
    static final String REFLECT = "reflect";

    private final LlmClient llm;
    private final Map<String, Function<String, String>> tools;
    private final int maxDepth;
    private final int maxExpansions;

    public LatsGraph(LlmClient llm, Map<String, Function<String, String>> tools,
                     int maxDepth, int maxExpansions) {
        this.llm = llm;
        this.tools = tools;
        this.maxDepth = maxDepth;
        this.maxExpansions = maxExpansions;
    }

    // ---- 树工具（对应手写版 Node.trace 的 parent 链回溯）----

    static TreeNode byId(List<TreeNode> tree, int id) {
        for (TreeNode n : tree) {
            if (n.id() == id) {
                return n;
            }
        }
        return null;
    }

    /** 从根到本节点的完整轨迹描述，注入提示词作为上下文（对应手写版 Node.trace()）。 */
    static String traceOf(List<TreeNode> tree, TreeNode node) {
        Map<Integer, TreeNode> byId = new HashMap<>();
        for (TreeNode n : tree) {
            byId.put(n.id(), n);
        }
        StringBuilder sb = new StringBuilder();
        for (TreeNode n = node; n != null; n = n.parentId() == null ? null : byId.get(n.parentId())) {
            if (n.thought() != null) {
                sb.insert(0, "Thought: " + n.thought() + "\nAction: " + n.actionName()
                        + "[" + n.actionInput() + "]\nObservation: " + n.observation() + "\n");
            }
        }
        return sb.toString();
    }

    static List<Action> parseActions(String output) {
        List<Action> actions = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher matcher = ACTION_PATTERN.matcher(line.strip());
            if (matcher.find()) {
                actions.add(new Action(matcher.group(1), matcher.group(2).trim()));
            }
        }
        return actions;
    }

    private String describeTools() {
        StringBuilder sb = new StringBuilder();
        for (String name : tools.keySet()) {
            sb.append(" - ").append(name).append("\n");
        }
        sb.append(" - Finish（给出最终答案并结束）");
        return sb.toString();
    }

    // ---- 节点 ----

    /** search 节点：一整轮迭代 = Expand → Execute → Evaluate → Descend。 */
    Map<String, Object> search(LatsState state) {
        System.out.println("\n===== 深度 " + state.depth() + "：扩展 " + maxExpansions + " 个候选动作 =====");
        List<TreeNode> tree = new ArrayList<>(state.tree());
        TreeNode current = state.currentId() < 0 ? null : byId(tree, state.currentId());

        // ---- Expand：LLM 基于当前轨迹生成候选动作（对应手写版 expand()）----
        String reflectionsHint = state.reflections().isEmpty() ? "" : """
                之前走进死路的反思警示：
                %s
                """.formatted(String.join("\n", state.reflections()));
        String output = llm.complete("""
                你在用树搜索解决下面的问题。基于当前已走过的轨迹，请给出若干个不同的下一步候选动作（每行一个）：
                Action: 工具名[参数]  或  Action: Finish[最终答案]
                可用工具：
                %s
                问题：%s
                当前轨迹：
                %s
                %s
                候选动作：""".formatted(describeTools(), state.question(),
                traceOf(tree, current), reflectionsHint));
        System.out.println("[Expand 候选]\n" + output);

        List<Action> candidates = parseActions(output).stream().limit(maxExpansions).toList();
        if (candidates.isEmpty()) {
            return Map.of("status", "failed");
        }

        // ---- Execute：每个候选调工具得 Observation（对应手写版 run() 的 for 循环体）----
        List<TreeNode> newTree = new ArrayList<>(tree);
        List<TreeNode> children = new ArrayList<>();
        for (Action action : candidates) {
            int id = newTree.size() + children.size();
            Integer parentId = current == null ? null : current.id();
            TreeNode child;
            if (action.name().equals("Finish")) {
                child = new TreeNode(id, parentId, null, "Finish", action.input(), "任务完成", 0, false);
            } else {
                String observation = tools.getOrDefault(action.name(), a -> "工具未注册: " + action.name())
                        .apply(action.input());
                child = new TreeNode(id, parentId, "分支尝试 " + action.name(),
                        action.name(), action.input(), observation, 0, false);
            }
            children.add(child);
        }
        newTree.addAll(children);

        // ---- Evaluate：LLM 对每个新节点打分（对应手写版 evaluate()，逐个 LLM 调用）----
        List<TreeNode> evaluated = new ArrayList<>();
        for (TreeNode child : children) {
            double reward = evaluate(state.question(), traceOf(newTree, child));
            TreeNode scored = child.withReward(reward);
            System.out.printf("  候选 Action: %s[%s] -> reward=%.1f%n",
                    child.actionName(), child.actionInput(), reward);
            evaluated.add(scored);
        }
        newTree = new ArrayList<>(newTree);
        for (TreeNode scored : evaluated) {
            newTree.set(scored.id(), scored);
        }

        // ---- Descend：选最高分分支；死路则交给 reflect 节点反思回溯 ----
        TreeNode best = evaluated.stream().max(Comparator.comparingDouble(TreeNode::reward)).orElseThrow();
        if (best.reward() < 4.0) {
            List<TreeNode> marked = new ArrayList<>(newTree);
            marked.set(best.id(), best.withDeadEnd());
            System.out.println("  -> 最优分支仍是死路（reward=" + best.reward()
                    + "），转入 reflect 节点生成语言反思并回溯");
            Map<String, Object> deadend = new HashMap<>();
            deadend.put("tree", marked);
            deadend.put("deadEndTrace", traceOf(marked, byId(marked, best.id())));
            deadend.put("currentId", current == null ? -1 : current.parentId());  // 回溯到父节点
            deadend.put("status", "deadend");
            return deadend;
        }
        if (best.isFinish()) {
            System.out.println("\n[Finish] " + best.actionInput());
            return Map.of(
                    "tree", newTree,
                    "finalAnswer", best.actionInput(),
                    "status", "finish");
        }
        System.out.println("  -> 选择分支: " + best.actionName() + "[" + best.actionInput() + "]");
        return Map.of(
                "tree", newTree,
                "currentId", best.id(),
                "depth", state.depth() + 1,
                "status", "continue");
    }

    /** Evaluate：LLM 对节点打 1~10 分，解析不到数字时兜底 1.0（与手写版一致）。 */
    private double evaluate(String question, String trace) {
        String output = llm.complete("""
                请评估以下这条解决路径的当前状态，只输出 1~10 的数字评分
                （10 表示已解决问题或非常接近解决，1 表示完全错误/死路）。

                问题：%s
                轨迹：
                %s
                评分：""".formatted(question, trace));
        Matcher matcher = SCORE_PATTERN.matcher(output);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return 1.0;
    }

    /** reflect 节点：对死路分支生成语言反思，追加进 State 后回到 search 重扩（回溯语义）。 */
    Map<String, Object> reflect(LatsState state) {
        String reflection = llm.complete("""
                以下这条解决路径走进了死路（评分过低），请用一段话总结失败原因，
                作为后续搜索分支时的警示。
                问题：%s
                失败轨迹：
                %s
                反思：""".formatted(state.question(), state.deadEndTrace()));
        System.out.println("[Reflection] " + reflection.strip());
        return Map.of(
                "reflections", List.of(reflection.strip()),
                "status", "continue");
    }

    /** 条件边：按本轮迭代结果路由。 */
    String routeAfterSearch(LatsState state) {
        return switch (state.status()) {
            case "finish" -> StateGraph.END;
            case "deadend" -> REFLECT;
            case "continue" -> state.depth() >= maxDepth ? StateGraph.END : SEARCH;
            default -> StateGraph.END;  // failed / 达到最大深度仍未找到 Finish 分支
        };
    }

    public StateGraph<LatsState> build() throws Exception {
        return new StateGraph<>(LatsState.SCHEMA, LatsState::new)
                .addNode(SEARCH, node_async(this::search))
                .addNode(REFLECT, node_async(this::reflect))
                .addEdge(StateGraph.START, SEARCH)
                .addEdge(REFLECT, SEARCH)
                .addConditionalEdges(SEARCH,
                        edge_async(this::routeAfterSearch),
                        Map.of(StateGraph.END, StateGraph.END,
                                REFLECT, REFLECT,
                                SEARCH, SEARCH));
    }

    /** 演示剧本与手写版一致：两轮扩展——先搜「虚拟线程」再 Finish 总结。 */
    public static void demo() throws Exception {
        System.out.println("""
                ── 手写版（搜索树 + 反思回溯）与本图的对照 ──
                   for depth in 0..maxDepth            → search 节点自环（条件边按 status 路由）
                   expand(question, current)           → search 节点内一次 LLM 调用
                   每个候选 execute + evaluate        → search 节点内 List 展开，逐候选调工具+评分
                   选 reward 最高的分支深入            → currentId 写入 State（base 通道覆盖）
                   reward < 4.0 → reflect + 回退父节点  → 条件边 → reflect 节点 → 无条件边回 search
                   Finish 分支                         → finalAnswer 写入 State → 条件边 → END
                搜索树是运行期逐层分叉的动态结构 —— 树的形态存 State.base 通道，图保持 search↔reflect 静态环
                """);

        Map<String, Function<String, String>> tools = Map.of(
                "Search", query -> {
                    if (query.toLowerCase().contains("虚拟线程")) {
                        return "Java 21 正式引入虚拟线程（Virtual Threads, JEP 444），大幅简化高并发编程。";
                    }
                    return "未找到与「" + query + "」相关的信息。";
                });

        ScriptedLlm llm = new ScriptedLlm()
                // 第 1 层扩展：两个候选动作
                .says("""
                        Action: Search[Java 虚拟线程]
                        Action: Search[Java 历史]""")
                // 评估两个分支
                .says("9")
                .says("3")
                // 第 2 层扩展：收敛为 Finish
                .says("""
                        Action: Finish[虚拟线程是 JDK 21 通过 JEP 444 引入的轻量级线程，适合 IO 密集型高并发场景，如微服务网关与海量连接服务端程序。]""")
                // 评估 Finish 分支
                .says("10");

        var graph = new LatsGraph(llm, tools, 5, 2).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", "帮我了解 Java 的虚拟线程特性，并总结它适合什么场景")),
                RunnableConfig.builder().threadId("lats-demo").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
