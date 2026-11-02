package com.example.langgraph4j;

import com.example.harness.config.AgentConfig;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.LangChain4jLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.patterns.ReWooGraph;
import com.example.langgraph4j.tool.MavenTool;
import com.example.langgraph4j.tool.WebSearchTool;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.GraphInput;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 真实 LLM 演示：用 LLM_BASE_URL 指定的 OpenAI 兼容端点（真实模型）驱动状态图。
 * LLM 客户端用 langchain4j 官方 {@code OpenAiChatModel}（见 {@code LangChain4jLlm}）。
 *
 * <p>跑两张图：</p>
 * <ol>
 *   <li>ReAct 图（ArticleScenario.buildGraph）：真实模型自主产出 Thought/Action，
 *       经条件边路由驱动「LLM 节点 ⇄ 工具节点」的环；</li>
 *   <li>REWOO 图：真实模型一次调用产出链式计划（含 #E 变量引用），
 *       worker 纯 Java 执行，solver 汇总 —— 全程仅 2 次 LLM 调用。</li>
 * </ol>
 *
 * 配置：环境变量 LLM_API_KEY（本机已注入）、LLM_BASE_URL、LLM_MODEL。
 */
public class RealScenario {

    public static void main(String[] args) throws Exception {
        LlmClientBanner();

        LlmClient llm = LangChain4jLlm.fromEnv();

        scenarioReAct(llm);
        scenarioReWoo(llm);

        System.exit(0);
    }

    static void LlmClientBanner() {
        System.out.println("=".repeat(66));
        System.out.println("  真实 LLM 演示（模型: " + AgentConfig.load().model()
                + "，端点: agent-gw OpenAI 兼容接口）");
        System.out.println("=".repeat(66));
    }

    /** ReAct 图 + 真实模型：模型自主决定何时调工具、何时 Finish。 */
    static void scenarioReAct(LlmClient llm) throws Exception {
        // 协议自检探针：开跑前先确认模型会吐 Action 行（ArticleScenario 同款协议），
        // 装置没搭对就 fail fast，不烧后面多轮真实循环的额度
        ProtocolProbe.requireOrExit(llm, """
                你是会调用工具的 Agent。可用工具：
                - Calculator[算式]：算数
                - Search[查询词]：联网查事实
                每轮只输出一行：Action: 工具名[参数] 或 Action: Finish[答案]。
                """, java.util.Set.of("Calculator", "Search", "Finish"));

        // 事实必须经 WikipediaSearch 工具联网查回，逼模型真实走完
        // Search -> Calculator -> Finish 的多轮循环（条件边每轮在 TOOL 与 END 间路由）
        String question = "维基百科记载，Google 成立于哪一年？成立于几月几日？距今（2026 年）多少年了？";
        System.out.println("\n【ReAct 状态图】问题：" + question);

        Map<String, Function<String, String>> tools = new java.util.LinkedHashMap<>();
        tools.put("Calculator", ArticleScenario.calculatorTool());
        tools.put("Search", WebSearchTool::search);

        var graph = ArticleScenario.buildGraph(llm, 10, tools).compile();

        var finalState = graph.invoke(GraphInput.args(
                Map.of("messages", List.of("User: " + question))),
                RunnableConfig.builder().threadId("real-react").build());

        finalState.ifPresent(s -> {
            System.out.println("\n  [最终 State 的完整轨迹]");
            s.messages().forEach(m -> System.out.println("    " + m.replace("\n", "\n    ")));
            System.out.println("\n  [最终答案] " + s.finalAnswer());
        });
    }

    /** REWOO 图 + 真实模型：一次规划、批量执行、汇总作答（三步链式计划跨三个数据源）。 */
    static void scenarioReWoo(LlmClient llm) throws Exception {
        String question = "Google 成立于哪一年？距今（2026 年）多少年了？"
                + "langgraph4j-core 的最新版本是多少？";
        System.out.println("\n【REWOO 状态图】问题：" + question);
        Map<String, Function<String, String>> tools = new java.util.LinkedHashMap<>();
        tools.put("Search", WebSearchTool::search);
        tools.put("Calculator", ArticleScenario.calculatorTool());
        tools.put("Maven", MavenTool::latestVersion);

        var graph = new ReWooGraph(llm, tools,
                Map.of("Maven", "参数为 groupId:artifactId，如 Maven[org.bsc.langgraph4j:langgraph4j-core]"))
                .build().compile();

        var finalState = graph.invoke(GraphInput.args(
                Map.of("question", question)),
                RunnableConfig.builder().threadId("real-rewoo").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse("")));
    }
}
