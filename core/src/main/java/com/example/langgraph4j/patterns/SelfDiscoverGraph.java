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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：Self-Discover（自我发现）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 SelfDiscoverAgent）：
 * 在比 Plan&amp;Solve 更小的粒度上反思「任务本身该如何推理」——先为自己的任务
 * 挑选并组装一套推理结构，再按这套结构解题：</p>
 * <ol>
 *   <li>Selector：从原子推理模块库中选出与任务相关的 2~3 个模块；</li>
 *   <li>Adaptor：把选中的模块编排成结构化推理计划（JSON）；</li>
 *   <li>Implementor：严格按推理结构逐步执行，逐步累积结果；</li>
 *   <li>最后汇总全部步骤结果给出最终答案。</li>
 * </ol>
 *
 * <p><b>建模决策</b>：与 LLMCompiler / LATS 不同，Self-Discover 的阶段划分是
 * <b>静态流水线</b>，可以一对一忠实翻译成节点链；唯一运行期才能确定的是推理计划的
 * <b>步骤数</b>（Adaptor 产出 JSON 后才知），故 Implementor 每次节点调用只执行一步，
 * 靠条件边自环推进（与 Plan &amp; Solve 的 executor 同构）。图的形态把
 * 「先反思推理方式、再按该方式解题」的两段式直接画在了图上：</p>
 *
 * <pre>
 *   START → selector → adaptor → implementor ──(条件边)── 未走完：implementor 自环
 *                                                     └── 走完 → finalizer → END
 * </pre>
 */
public class SelfDiscoverGraph {

    /** 原子推理模块库（与手写版一致）。 */
    static final List<String> REASONING_MODULES = List.of(
            "分步骤推理：把问题拆成一步一步的中间推理",
            "拆解为子问题：把复杂问题分解成若干独立子问题逐一攻克",
            "批判性思考：识别论证中的假设、谬误与漏洞",
            "类比推理：从相似问题的解法中迁移经验",
            "事实核查：用外部工具验证关键事实",
            "逆向求解：从目标出发倒推需要的条件",
            "归纳总结：从具体案例中归纳一般规律",
            "验证与反思：对中间结果进行检验并自我修正");

    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)\\{.*\\}");
    private static final Pattern STEP_PATTERN = Pattern.compile("\"step\"\\s*:\\s*\"([^\"]+)\"");

    // ---- State ----
    public static final class SelfDiscoverState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "task", Channels.base(() -> ""),                  // 用户任务
                "selectedModules", Channels.base(() -> ""),       // Selector 选中的模块（覆盖）
                "planJson", Channels.base(() -> ""),              // Adaptor 的 JSON 计划（覆盖）
                "steps", Channels.base(ArrayList::new),           // 解析出的步骤列表（覆盖）
                "stepIndex", Channels.base(() -> 0),              // 执行游标（覆盖）
                "stepResults", Channels.appender(ArrayList::new), // "步骤 => 结果"（追加累积）
                "finalAnswer", Channels.base(() -> ""));

        SelfDiscoverState(Map<String, Object> initData) {
            super(initData);
        }

        String task() {
            return this.<String>value("task").orElse("");
        }

        String selectedModules() {
            return this.<String>value("selectedModules").orElse("");
        }

        String planJson() {
            return this.<String>value("planJson").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> steps() {
            return this.<List<String>>value("steps").orElseGet(List::of);
        }

        int stepIndex() {
            return this.<Integer>value("stepIndex").orElse(0);
        }

        @SuppressWarnings("unchecked")
        List<String> stepResults() {
            return this.<List<String>>value("stepResults").orElseGet(List::of);
        }
    }

    static final String SELECTOR = "selector";
    static final String ADAPTOR = "adaptor";
    static final String IMPLEMENTOR = "implementor";
    static final String FINALIZER = "finalizer";

    private final LlmClient llm;

    public SelfDiscoverGraph(LlmClient llm) {
        this.llm = llm;
    }

    /** Selector 节点：从推理模块库中挑选相关模块（对应手写版 Stage 1）。 */
    Map<String, Object> selector(SelfDiscoverState state) {
        String modules = String.join("\n", REASONING_MODULES);
        String selected = llm.complete("""
                任务：%s

                下面是一个原子推理模块库：
                %s

                请从中挑选出最适合解决该任务的 2~3 个模块，每行输出一个模块名称（冒号前的部分）。"""
                .formatted(state.task(), modules));
        System.out.println("\n[Selector 选中的推理模块]\n" + selected);
        return Map.of("selectedModules", selected.strip());
    }

    /** Adaptor 节点：把选中的模块编排成结构化推理计划 JSON（对应手写版 Stage 2）。 */
    Map<String, Object> adaptor(SelfDiscoverState state) {
        String adapted = llm.complete("""
                任务：%s
                已选推理模块：
                %s

                请把选中的模块编排成一份针对该任务的结构化推理计划，输出 JSON：
                {"structure": "推理结构名称", "steps": [{"step": "步骤说明", "module": "使用的模块"}]}
                只输出 JSON。"""
                .formatted(state.task(), state.selectedModules()));
        System.out.println("\n[Adaptor 生成的推理结构]\n" + adapted);

        List<String> steps = parseSteps(adapted);
        if (steps.isEmpty()) {
            steps = List.of(adapted.strip());  // 容错：整体作为单步执行（与手写版一致）
        }
        return Map.of("planJson", adapted.strip(), "steps", steps);
    }

    /** Implementor 节点：每次调用只执行一步（对应手写版 Stage 3 的 for 循环体）。 */
    Map<String, Object> implementor(SelfDiscoverState state) {
        List<String> steps = state.steps();
        int i = state.stepIndex();
        String prior = state.stepResults().isEmpty() ? "（无）"
                : String.join("\n", state.stepResults());
        String result = llm.complete("""
                请只执行推理计划中的第 %d 步，必要时调用工具（格式 Action: 工具名[参数]）。
                基于之前的步骤结果作答。

                任务：%s
                当前步骤：%s
                之前步骤结果：
                %s
                本步结果：""".formatted(i + 1, state.task(), steps.get(i), prior));
        System.out.println("\n[Implementor 第 " + (i + 1) + "步] " + result.strip());
        return Map.of(
                "stepResults", List.of(steps.get(i) + " => " + result.strip()),
                "stepIndex", i + 1);
    }

    /** finalizer 节点：汇总全部步骤结果（对应手写版最后的汇总 complete）。 */
    Map<String, Object> finalizer(SelfDiscoverState state) {
        String answer = llm.complete("""
                任务：%s
                已按推理结构完成全部步骤：
                %s
                请汇总给出最终答案：""".formatted(state.task(), String.join("\n", state.stepResults())));
        System.out.println("\n[finalizer 汇总] " + answer.strip());
        return Map.of("finalAnswer", answer.strip());
    }

    /** 条件边：步骤未走完 → implementor 自环；走完 → finalizer。 */
    String routeAfterImplementor(SelfDiscoverState state) {
        if (state.stepIndex() >= state.steps().size()) {
            System.out.println("  [路由] 全部 " + state.steps().size() + " 步执行完 → finalizer 汇总");
            return FINALIZER;
        }
        System.out.println("  [路由] 已完成 " + state.stepIndex() + "/" + state.steps().size()
                + " 步 → implementor 继续下一步");
        return IMPLEMENTOR;
    }

    public StateGraph<SelfDiscoverState> build() throws Exception {
        return new StateGraph<>(SelfDiscoverState.SCHEMA, SelfDiscoverState::new)
                .addNode(SELECTOR, node_async(this::selector))
                .addNode(ADAPTOR, node_async(this::adaptor))
                .addNode(IMPLEMENTOR, node_async(this::implementor))
                .addNode(FINALIZER, node_async(this::finalizer))
                .addEdge(StateGraph.START, SELECTOR)
                .addEdge(SELECTOR, ADAPTOR)
                .addEdge(ADAPTOR, IMPLEMENTOR)
                .addEdge(FINALIZER, StateGraph.END)
                .addConditionalEdges(IMPLEMENTOR,
                        edge_async(this::routeAfterImplementor),
                        Map.of(IMPLEMENTOR, IMPLEMENTOR, FINALIZER, FINALIZER));
    }

    /** 极简提取 JSON 里 "step": "..." 的值（与手写版 parseSteps 相同的正则思路）。 */
    static List<String> parseSteps(String adaptedJson) {
        List<String> steps = new ArrayList<>();
        Matcher block = JSON_BLOCK.matcher(adaptedJson);
        if (!block.find()) {
            return steps;
        }
        Matcher stepMatcher = STEP_PATTERN.matcher(block.group());
        while (stepMatcher.find()) {
            steps.add(stepMatcher.group(1));
        }
        return steps;
    }

    /** 演示剧本与手写版一致：选模块 → JSON 计划（2 步）→ 逐步执行 → 汇总。 */
    public static void runScenario() throws Exception {
        System.out.println("""
                ── 手写版（三阶段流水线）与本图的对照 ──
                   SELECT 阶段              → selector 节点
                   ADAPT 阶段               → adaptor 节点（JSON 计划解析为 steps 存 State）
                   for 循环逐步执行          → implementor 节点 + 条件边自环（每轮一步）
                   stepResults.add(...)     → stepResults appender 通道追加
                   汇总 complete            → finalizer 节点 → END
                阶段是静态的 → 一对一翻译成节点链；步骤数是运行期才知道的 → implementor 自环
                """);

        ScriptedLlm llm = new ScriptedLlm()
                // Selector
                .says("""
                        分步骤推理
                        拆解为子问题""")
                // Adaptor
                .says("""
                        {"structure": "先拆解数量关系再逐步计算", "steps": [
                          {"step": "弄清 2 打苹果是多少个", "module": "拆解为子问题"},
                          {"step": "把原有苹果与新买的苹果相加", "module": "分步骤推理"}
                        ]}""")
                // Implementor 第 1 步
                .says("2 打 = 2 × 12 = 24 个苹果")
                // Implementor 第 2 步
                .says("3 + 24 = 27 个苹果")
                // 汇总
                .says("小明一共有 27 个苹果。");

        var graph = new SelfDiscoverGraph(llm).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", "小明有 3 个苹果，又买了 2 打（每打 12 个），他现在一共有多少个苹果？")),
                RunnableConfig.builder().threadId("selfdiscover-场景").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
