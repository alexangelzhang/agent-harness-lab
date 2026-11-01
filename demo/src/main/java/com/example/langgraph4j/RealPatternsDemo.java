package com.example.langgraph4j;

import com.example.harness.config.AgentConfig;
import com.example.langgraph4j.llm.LangChain4jLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.patterns.LatsGraph;
import com.example.langgraph4j.patterns.LlmCompilerGraph;
import com.example.langgraph4j.patterns.MultiAgentGraph;
import com.example.langgraph4j.patterns.PlanSolveGraph;
import com.example.langgraph4j.patterns.SelfDiscoverGraph;
import com.example.langgraph4j.patterns.StormGraph;
import com.example.langgraph4j.patterns.ReflectionGraph;
import com.example.langgraph4j.patterns.ReflexionGraph;
import com.example.langgraph4j.tool.WebSearchTool;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 真实 LLM 跑另外七个模式图（ReAct / REWOO 见 {@link RealDemo}）。
 *
 * <p>与 {@code PatternsDemo}（ScriptedLlm 剧本）不同，这里全部调用真实模型，
 * 展示各模式在真实输出下的表现：</p>
 * <ul>
 *   <li><b>Basic Reflection</b>：slogan 创作-批改循环，直到 ACCEPT 或达到最大轮数；</li>
 *   <li><b>Plan &amp; Solve</b>：编号计划逐步执行（executor 每步可调用工具），
 *       失败触发 replanner 重规划；</li>
 *   <li><b>Reflexion</b>：硬约束任务（恰好 20 个汉字）——真实模型首轮通常不满足
 *       程序化环境校验，经语言反思（情景记忆环）后修正；</li>
 *   <li><b>LLMCompiler</b>：planner 产出任务 DAG，scheduler 节点内并行执行就绪波次，
 *       joiner 汇总；</li>
 *   <li><b>LATS</b>：每轮 expand k 个候选分支 → 执行+评分 → 择优深入；
 *       全是死路时经 reflect 节点生成语言反思后回溯重扩；</li>
 *   <li><b>Self-Discover</b>：selector 选推理模块 → adaptor 编排 JSON 推理计划 →
 *       implementor 按步执行（条件边自环）→ finalizer 汇总；</li>
 *   <li><b>STORM</b>：大纲 → 逐章「多视角提问 + 搜索 + 撰写」→ 引言/总结拼装成文。</li>
 * </ul>
 *
 * 可用 {@code -Dexec.args="reflection,compiler"} 只跑部分（逗号分隔）。
 */
public class RealPatternsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(66));
        System.out.println("  真实 LLM 模式演示（模型: "
                + AgentConfig.load().model() + "）");
        System.out.println("=".repeat(66));

        List<String> which = args.length == 0
                ? List.of("reflection", "plansolve", "reflexion", "compiler", "lats", "selfdiscover", "storm")
                : Arrays.asList(args[0].split(","));

        LlmClient llm = LangChain4jLlm.fromEnv();
        if (which.contains("reflection")) {
            demoReflection(llm);
        }
        if (which.contains("plansolve")) {
            demoPlanSolve(llm);
        }
        if (which.contains("reflexion")) {
            demoReflexion(llm);
        }
        if (which.contains("compiler")) {
            demoCompiler(llm);
        }
        if (which.contains("lats")) {
            demoLats(llm);
        }
        if (which.contains("selfdiscover")) {
            demoSelfDiscover(llm);
        }
        if (which.contains("storm")) {
            demoStorm(llm);
        }
        if (which.contains("multiagent")) {
            demoMultiAgent(llm);
        }

        System.exit(0);
    }

    /** Basic Reflection：真实「生成 → 批改 → 修改」循环。 */
    static void demoReflection(LlmClient llm) throws Exception {
        System.out.println("\n【Reflection 状态图】任务：为一款冷萃咖啡液写一句广告 slogan");
        var graph = new ReflectionGraph(llm, 3).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", "为一款冷萃咖啡液写一句广告 slogan，突出低温慢萃工艺")),
                RunnableConfig.builder().threadId("real-reflection").build());
        finalState.ifPresent(s -> System.out.println(
                "\n  [定稿] " + s.<String>value("draft").orElse("") + "（共 " + s.<Integer>value("round").orElse(0) + " 轮）"));
    }

    /** Plan & Solve：编号计划逐步执行，executor 每步可调用 Search / Calculator。 */
    static void demoPlanSolve(LlmClient llm) throws Exception {
        String question = "Google 成立于哪一年？距今（2026 年）多少年了？";
        System.out.println("\n【Plan&Solve 状态图】问题：" + question);
        Map<String, Function<String, String>> tools = Map.of(
                "Search", WebSearchTool::search,
                "Calculator", ArticleDemo.calculatorTool());
        var graph = new PlanSolveGraph(llm, tools).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", question)),
                RunnableConfig.builder().threadId("real-plansolve").build());
        finalState.ifPresent(s -> System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }

    /** Reflexion：硬约束（恰好 20 个汉字）逼出「失败 → 语言反思 → 带记忆重试」环。 */
    static void demoReflexion(LlmClient llm) throws Exception {
        String question = "请写一句恰好 20 个汉字的广告 slogan 推销便携式咖啡机，"
                + "但 slogan 中不能出现「咖啡」二字（标点不计入字数）。只输出 slogan 本身。";
        System.out.println("\n【Reflexion 状态图】问题：" + question);
        var graph = new ReflexionGraph(llm,
                answer -> chineseCharCount(answer) == 20 && !answer.contains("咖啡"),
                "答案必须恰好 20 个汉字，且不能出现「咖啡」二字（标点不计入）",
                3).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", question)),
                RunnableConfig.builder().threadId("real-reflexion").build());
        finalState.ifPresent(s -> {
            String answer = s.<String>value("finalAnswer").orElse("");
            long counted = chineseCharCount(answer);
            System.out.println("\n  [最终答案] " + answer);
            System.out.println("  [尝试次数] " + s.<Integer>value("trial").orElse(0)
                    + "（汉字数=" + counted + "，含禁用词=" + answer.contains("咖啡") + "）");
            @SuppressWarnings("unchecked")
            List<String> reflections = (List<String>) s.value("reflections").orElseGet(List::of);
            System.out.println("  [情景记忆里的语言反思] 共 " + reflections.size() + " 条：");
            reflections.forEach(r -> System.out.println("    - " + r));
        });
    }

    /** LLMCompiler：planner 产 DAG → scheduler 并行波次 → joiner 汇总。 */
    static void demoCompiler(LlmClient llm) throws Exception {
        String question = "Google 和 Microsoft 分别成立于哪一年？哪一家成立更早，早多少年？";
        System.out.println("\n【LLMCompiler 状态图】问题：" + question);
        Map<String, Function<String, String>> tools = new LinkedHashMap<>();
        tools.put("Search", WebSearchTool::search);
        tools.put("Calculator", ArticleDemo.calculatorTool());
        var graph = new LlmCompilerGraph(llm, tools).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", question)),
                RunnableConfig.builder().threadId("real-compiler").build());
        finalState.ifPresent(s -> System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }

    /** LATS：expand→执行→评分→择优；死路经 reflect 语言反思后回溯。LLM 调用量最大，最慢。 */
    static void demoLats(LlmClient llm) throws Exception {
        String question = "维基百科记载，Google 成立于哪一年？距今（2026 年）多少年了？";
        System.out.println("\n【LATS 状态图】问题：" + question);
        Map<String, Function<String, String>> tools = new LinkedHashMap<>();
        tools.put("Search", WebSearchTool::search);
        tools.put("Calculator", ArticleDemo.calculatorTool());
        var graph = new LatsGraph(llm, tools, 3, 3).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", question)),
                RunnableConfig.builder().threadId("real-lats").build());
        finalState.ifPresent(s -> {
            System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
            @SuppressWarnings("unchecked")
            List<String> reflections = (List<String>) s.value("reflections").orElseGet(List::of);
            if (!reflections.isEmpty()) {
                System.out.println("  [搜索中的语言反思] 共 " + reflections.size() + " 条：");
                reflections.forEach(r -> System.out.println("    - " + r));
            }
        });
    }

    /** Self-Discover：真实模型自选推理模块 → 编排 JSON 计划 → 逐步执行 → 汇总。 */
    static void demoSelfDiscover(LlmClient llm) throws Exception {
        String task = "一个水池有进水管和出水管，单开进水管 6 小时注满，单开出水管 8 小时放空。"
                + "同时打开两管，几小时注满水池？请先用选定的推理结构分步推理，再给最终答案。";
        System.out.println("\n【Self-Discover 状态图】任务：" + task);
        var graph = new SelfDiscoverGraph(llm).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", task)),
                RunnableConfig.builder().threadId("real-selfdiscover").build());
        finalState.ifPresent(s -> {
            System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) s.value("steps").orElseGet(List::of);
            System.out.println("  [推理结构步骤数] " + steps.size());
        });
    }

    /** STORM：真实模型生成大纲 → 逐章多视角调研（真实维基搜索）→ 撰写 → 引言/总结成稿。
     * 大纲章节数由模型自由产出（实测一次 17 章），图的步数 = 1 + 2×章节数 + 1，
     * 需放宽 langgraph4j 默认 recursionLimit(25)。 */
    static void demoStorm(LlmClient llm) throws Exception {
        String topic = "Java 虚拟线程";
        System.out.println("\n【STORM 状态图】主题：" + topic);
        var graph = new StormGraph(llm, WebSearchTool::search).build().compile(
                CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .recursionLimit(100)
                        .build());
        var finalState = graph.invoke(GraphInput.args(
                Map.of("topic", topic)),
                RunnableConfig.builder().threadId("real-storm").build());
        finalState.ifPresent(s -> System.out.println(
                "\n  [终稿字符数] " + s.<String>value("finalArticle").orElse("").length()
                        + "，全文见上方 [成稿]"));
    }

    /** Multi-Agent：supervisor 真实调度 Researcher（维基搜索）/ Calculator 分工协作。 */
    static void demoMultiAgent(LlmClient llm) throws Exception {
        String task = "维基百科记载，Google 成立于哪一年？距今（2026 年）多少年了？";
        System.out.println("\n【Multi-Agent 状态图】任务：" + task);
        List<MultiAgentGraph.Worker> workers = List.of(
                new MultiAgentGraph.Worker("Researcher", "负责事实性问题，用搜索工具查证关键信息",
                        "Search", WebSearchTool::search),
                new MultiAgentGraph.Worker("Calculator", "负责数值计算",
                        "Calculator", ArticleDemo.calculatorTool()));
        var graph = new MultiAgentGraph(llm, workers, 6).build().compile();
        var finalState = graph.invoke(GraphInput.args(
                Map.of("task", task)),
                RunnableConfig.builder().threadId("real-multiagent").build());
        finalState.ifPresent(s -> {
            System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
            @SuppressWarnings("unchecked")
            List<String> transcript = (List<String>) s.value("transcript").orElseGet(List::of);
            System.out.println("  [共享黑板] 共 " + transcript.size() + " 条对话：");
            transcript.forEach(t -> System.out.println("    - " + t));
        });
    }

    /** 统计 CJK 统一表意文字数量（Reflexion 环境校验的硬约束）。 */
    static long chineseCharCount(String s) {
        return s.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
    }
}
