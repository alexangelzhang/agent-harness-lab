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
import java.util.function.Predicate;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：Reflexion（语言强化学习）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 ReflexionAgent）：</p>
 * <pre>
 *   for trial in 1..maxTrials:                        ──▶ 条件边：trial 字段 + 路由判断
 *       answer = actor(question, reflections)          ──▶ actor 节点（reflections 追加通道 = 情景记忆）
 *       passed = environmentCheck.test(answer)         ──▶ evaluator 节点（纯 Java，不调 LLM！）
 *       if passed: return answer                       ──▶ 条件边 → END
 *       reflection = selfReflect(question, answer)     ──▶ self_reflection 节点
 *       reflections.add(reflection)                    ──▶ reflections 通道追加 → actor（记忆环）
 * </pre>
 *
 * <p>图结构（对应论文三组件）：</p>
 * <pre>
 *   START → actor → evaluator ──(条件边)──▶ passed 或达上限 → END
 *                        └──▶ self_reflection → actor（环）
 * </pre>
 * 建模要点：Evaluator 是程序化环境校验而非 LLM 调用，做成纯 Java 节点后可进轨迹与 Checkpoint；
 * 语言反思写入 appender 通道，actor 每轮从 State 读取全部历史反思 —— 这就是「口头强化学习」的记忆环。
 */
public class ReflexionGraph {

    // ---- State ----
    public static final class ReflexionState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "question", Channels.base(() -> ""),               // 问题（只写一次）
                "answer", Channels.base(() -> ""),                 // Actor 最近一次的回答（覆盖）
                "trial", Channels.base(() -> 0),                   // 尝试次数（覆盖 = 手写版 trial 循环变量）
                "passed", Channels.base(() -> false),              // 环境校验结果（覆盖）
                "reflections", Channels.appender(ArrayList::new),  // 情景记忆：语言反思列表（追加）
                "finalAnswer", Channels.base(() -> ""));           // 终态答案（覆盖）

        ReflexionState(Map<String, Object> initData) {
            super(initData);
        }

        String question() {
            return this.<String>value("question").orElse("");
        }

        String answer() {
            return this.<String>value("answer").orElse("");
        }

        int trial() {
            return this.<Integer>value("trial").orElse(0);
        }

        boolean passed() {
            return this.<Boolean>value("passed").orElse(false);
        }

        @SuppressWarnings("unchecked")
        List<String> reflections() {
            return this.<List<String>>value("reflections").orElseGet(List::of);
        }
    }

    static final String ACTOR = "actor";
    static final String EVALUATOR = "evaluator";
    static final String SELF_REFLECTION = "self_reflection";

    private final LlmClient llm;
    private final Predicate<String> environmentCheck;
    private final String checkRubric;
    private final int maxTrials;

    /**
     * @param environmentCheck 环境评估函数（外部依据，纯 Java，不调 LLM）
     * @param checkRubric      评估标准的自然语言描述，注入反思提示词
     */
    public ReflexionGraph(LlmClient llm, Predicate<String> environmentCheck,
                          String checkRubric, int maxTrials) {
        this.llm = llm;
        this.environmentCheck = environmentCheck;
        this.checkRubric = checkRubric;
        this.maxTrials = maxTrials;
    }

    /** Actor 节点：回答前先批判式思考，并读取全部历史反思（对应手写版 actor 调用）。 */
    Map<String, Object> actor(ReflexionState state) {
        String memory = memoryText(state);
        String output = llm.complete("""
                请回答下面的问题。回答前先进行自我批评式思考：
                有没有遗漏（missing）或多余（superfluous）的方面？然后给出答案。

                %s
                问题：%s
                回答：""".formatted(memory.isEmpty() ? "" : "之前的尝试与反思：\n" + memory, state.question()));
        System.out.println("  [actor 第 " + (state.trial() + 1) + " 次尝试] " + output.strip());
        return Map.of(
                "answer", output.strip(),
                "finalAnswer", output.strip(),
                "trial", state.trial() + 1);
    }

    /** Evaluator 节点：纯 Java 程序化校验（对应手写版 environmentCheck.test）。 */
    Map<String, Object> evaluator(ReflexionState state) {
        boolean passed = environmentCheck.test(state.answer());
        System.out.println("  [evaluator 环境校验] " + (passed ? "PASS" : "FAIL"));
        return Map.of("passed", passed);
    }

    /** Self-Reflection 节点：失败后生成语言反思，追加进情景记忆（对应手写版 selfReflect + reflections.add）。 */
    Map<String, Object> selfReflection(ReflexionState state) {
        String output = llm.complete("""
                上面的回答没有通过环境校验（校验标准：%s）。
                请分析失败原因，并用一段话给出建设性的改进建议（语言反思），
                这段反思会被存入记忆，指导下一次尝试。

                问题：%s
                失败的回答：%s
                反思：""".formatted(checkRubric, state.question(), state.answer()));
        System.out.println("  [self-reflection] " + output.strip());
        return Map.of("reflections", List.of(output.strip()));
    }

    /** 条件边路由（evaluator 之后）：对应手写版 for 循环 + if(passed) return。 */
    String routeAfterEvaluator(ReflexionState state) {
        if (state.passed()) {
            System.out.println("  [路由] 校验通过 → 结束");
            return StateGraph.END;
        }
        if (state.trial() >= maxTrials) {
            System.out.println("  [路由] 尝试 " + maxTrials + " 次仍未通过 → 结束（返回最后一版）");
            return StateGraph.END;
        }
        System.out.println("  [路由] 校验失败 → 生成语言反思后重试（记忆环）");
        return SELF_REFLECTION;
    }

    public StateGraph<ReflexionState> build() throws Exception {
        return new StateGraph<>(ReflexionState.SCHEMA, ReflexionState::new)
                .addNode(ACTOR, node_async(this::actor))
                .addNode(EVALUATOR, node_async(this::evaluator))
                .addNode(SELF_REFLECTION, node_async(this::selfReflection))
                .addEdge(StateGraph.START, ACTOR)
                .addEdge(ACTOR, EVALUATOR)
                .addEdge(SELF_REFLECTION, ACTOR)
                .addConditionalEdges(EVALUATOR,
                        edge_async(this::routeAfterEvaluator),
                        Map.of(SELF_REFLECTION, SELF_REFLECTION, StateGraph.END, StateGraph.END));
    }

    /** 演示剧本与手写版一致：17+25 第一次算错（52），反思进位问题后第二次答对（42）。 */
    public static void runScenario() throws Exception {
        System.out.println("""
                ── 手写版（for 循环）与本图的对照 ──
                   for trial in 1..maxTrials        → 条件边 routeAfterEvaluator（trial 字段防死循环）
                   answer = actor(question, ...)    → actor 节点
                   passed = envCheck.test(answer)   → evaluator 节点（纯 Java，外部依据）
                   if passed: return                → 条件边 → END
                   reflections.add(selfReflect)     → self_reflection 节点（appender 通道 = 情景记忆）
                   下一轮 actor 读取全部反思         → 记忆环：self_reflection → actor
                """);

        ScriptedLlm llm = new ScriptedLlm()
                .says("自我批评：直接相加即可。17+25 = 52。")
                .says("计算错误：个位 7+5=12 需要进位，17+25 的正确结果应为 42。下次先处理进位。")
                .says("自我批评：注意个位进位，7+5=12 进 1。17+25 = 42。");

        var graph = new ReflexionGraph(llm, answer -> answer.contains("42"),
                "最终答案必须包含正确数值 42", 3).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", "17 + 25 等于多少？")),
                RunnableConfig.builder().threadId("reflexion-场景").build());

        finalState.ifPresent(s -> {
            System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
            System.out.println("  [尝试次数] " + s.trial());
            System.out.println("  [情景记忆里的语言反思] 共 " + s.reflections().size() + " 条：");
            s.reflections().forEach(r -> System.out.println("    - " + r));
        });
    }

    /** 历史反思的提示词文本。 */
    static String memoryText(ReflexionState state) {
        StringBuilder sb = new StringBuilder();
        List<String> reflections = state.reflections();
        for (int i = 0; i < reflections.size(); i++) {
            sb.append("反思 ").append(i + 1).append(": ").append(reflections.get(i)).append('\n');
        }
        return sb.toString();
    }
}
