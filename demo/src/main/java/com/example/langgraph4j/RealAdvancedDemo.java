package com.example.langgraph4j;

import com.example.harness.config.AgentConfig;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.LangChain4jLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.RetryingLlm;
import com.example.langgraph4j.llm.WebSearchLlm;
import com.example.langgraph4j.tool.WebSearchTool;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 真实演示进阶：Human-in-the-Loop 审批 + 客户端层联网搜索。
 *
 * <ol>
 *   <li><b>HITL</b>：ReAct 图编译时加 {@code MemorySaver} + {@code interruptBefore(TOOL_NODE)}——
 *       真实模型决定调用 Search（敏感工具）后，<b>在工具执行前中断</b>，快照写入 Checkpoint；
 *       人工查看待执行动作后 {@code GraphInput.resume()} 恢复执行；</li>
 *   <li><b>客户端层搜索</b>：同一问题改走 {@link WebSearchLlm}（网关内置 $web_search 下沉到
 *       LLM 客户端内部循环），图上只剩 Calculator 一个工具——与 RealDemo 的图级 Search
 *       节点形成对照：图级工具调用是显式节点（可中断、进轨迹、可并行），客户端级是节点黑盒。</li>
 * </ol>
 */
public class RealAdvancedDemo {

    static final String QUESTION = "维基百科记载，Google 成立于哪一年？距今（2026 年）多少年了？";

    /** 协议自检探针用的 Action 协议说明（与 ArticleDemo 注入图的格式同源）。 */
    static final String REACT_PROTOCOL = """
            你是会调用工具的 Agent。可用工具：
            - Calculator[算式]：算数
            - Search[查询词]：联网查事实
            - WebSearch[查询词]：联网查百科没有的新信息
            每轮只输出一行：Action: 工具名[参数] 或 Action: Finish[答案]。
            """;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(66));
        System.out.println("  真实 LLM 进阶演示（模型: "
                + AgentConfig.load().model() + "）");
        System.out.println("=".repeat(66));

        demoHitl(new RetryingLlm(LangChain4jLlm.kimi()));
        demoHybridSearch(new RetryingLlm(LangChain4jLlm.kimi()));
        demoClientLevelSearch();

        System.exit(0);
    }

    /** 混合调度：同一张 ReAct 图同时挂图级工具与客户端级工具，模型按问题类型路由。 */
    static void demoHybridSearch(LlmClient llm) throws Exception {
        System.out.println("\n【混合调度】同一张 ReAct 图，两个搜索路径并存：");
        System.out.println("  Search（维基百科，Java 实现，图级工具）");
        System.out.println("  WebSearch（节点外壳 + 客户端内置 $web_search 内核）");

        // 协议自检探针：三工具表的 Action 协议先验证，再开跑
        ProtocolProbe.requireOrExit(llm, REACT_PROTOCOL, Set.of("Calculator", "Search", "WebSearch", "Finish"));

        // WebSearch 的内核是客户端层 $web_search：图保留节点可见性/可中断性，执行交给托管搜索
        LlmClient clientSearch = new RetryingLlm(WebSearchLlm.kimi());
        Map<String, Function<String, String>> tools = new LinkedHashMap<>();
        tools.put("Calculator", ArticleDemo.calculatorTool());
        tools.put("Search", WebSearchTool::search);
        tools.put("WebSearch", q -> clientSearch.complete("请联网搜索：" + q + "，只回答关键事实。"));

        var graph = ArticleDemo.buildGraph(llm, 10, tools).compile();

        // Q1：百科事实 —— 期望路由到图级 Search（维基条目）
        runHybrid(graph, "hybrid-wiki",
                "维基百科记载，Google 成立于哪一年？距今（2026 年）多少年了？");
        // Q2：百科没有的新锐技术信息 —— 期望路由到 WebSearch（$web_search 客户端内核）
        runHybrid(graph, "hybrid-web",
                "langgraph4j 的最新版本是多少？（这个词条维基百科没有，请用 WebSearch 联网查，不要凭记忆回答）");
    }

    static void runHybrid(org.bsc.langgraph4j.CompiledGraph<ArticleDemo.ReActState> compiled,
                          String threadId, String question) throws Exception {
        System.out.println("\n  [问题] " + question);
        var finalState = compiled.invoke(GraphInput.args(
                Map.of("messages", List.of("User: " + question))),
                RunnableConfig.builder().threadId(threadId).build());
        finalState.ifPresent(s -> {
            @SuppressWarnings("unchecked")
            List<String> msgs = (List<String>) s.value("messages").orElseGet(List::of);
            msgs.stream().filter(m -> m.startsWith("Assistant: Thought") || m.startsWith("Observation"))
                    .forEach(m -> System.out.println("    " + m.replace("\n", "\n    ")));
            System.out.println("  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
        });
    }

    /** HITL：interruptBefore(TOOL_NODE) —— 敏感工具执行前中断，人工确认后恢复。 */
    static void demoHitl(LlmClient llm) throws Exception {
        System.out.println("\n【HITL 审批】问题：" + QUESTION);
        System.out.println("  图配置：MemorySaver + interruptBefore(tool_node) —— Search 执行前中断");

        // 协议自检探针：HITL 恢复逻辑依赖模型真的吐 Action 行，先验证再开跑
        ProtocolProbe.requireOrExit(llm, REACT_PROTOCOL, Set.of("Calculator", "Search", "Finish"));

        Map<String, Function<String, String>> tools = new LinkedHashMap<>();
        tools.put("Calculator", ArticleDemo.calculatorTool());
        tools.put("Search", WebSearchTool::search);

        // 与 ArticleDemo 概念 5 相同：编译时绑 CheckpointSaver + 在工具节点前中断
        var compiled = ArticleDemo.buildGraph(llm, 10, tools).compile(
                CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .interruptBefore(ArticleDemo.TOOL_NODE)
                        .build());
        var config = RunnableConfig.builder().threadId("real-hitl").build();

        // 第一轮：模型产出 Action: Search[...] 后中断（工具尚未执行）
        var interrupted = compiled.invoke(GraphInput.args(
                Map.of("messages", List.of("User: " + QUESTION))), config);

        // 真实模型可能首跳就 Finish（或没产出动作行）——图已正常跑完、没有工具待审批。
        // 恢复一个不存在的 checkpoint 会炸，所以先确认真的停在工具前再谈审批。
        boolean pausedBeforeTool = interrupted
                .flatMap(s -> s.<String>value("lastAction"))
                .map(a -> !a.isBlank() && !"Finish".equals(a))
                .orElse(false);

        if (!pausedBeforeTool) {
            System.out.println("  [跳过审批] 模型首跳直接给了终答（或没产出动作行）——"
                    + "没有工具要执行，HITL 无的放矢，打印结果收工。");
            interrupted.ifPresent(s -> System.out.println(
                    "  [最终答案] " + s.<String>value("finalAnswer").orElse("(空)")));
            return;
        }

        interrupted.ifPresent(s -> {
            System.out.println("  [中断] 停在工具执行前。待审批动作: "
                    + s.<String>value("lastAction").orElse("") + "[" + s.<String>value("actionArgs").orElse("") + "]");
        });
        System.out.println("  [Checkpoint 历史] 已存快照 "
                + compiled.getStateHistory(config).size() + " 个（中断点可回滚/恢复）");

        // 人工审批：确认允许执行 → 恢复（真实系统此处应是人审 UI / 审批流）
        System.out.println("  [人工审批] ✅ 允许执行 Search —— 恢复运行...");
        var resumed = compiled.invoke(GraphInput.resume(), config);
        resumed.ifPresent(s -> {
            System.out.println("\n  [最终 State 的完整轨迹]");
            @SuppressWarnings("unchecked")
            List<String> msgs = (List<String>) s.value("messages").orElseGet(List::of);
            msgs.forEach(m -> System.out.println("    " + m.replace("\n", "\n    ")));
            System.out.println("\n  [最终答案] " + s.<String>value("finalAnswer").orElse(""));
        });
    }

    /** 客户端层搜索：$web_search 在 LLM 客户端内部完成，一次调用直接出答案（无图、无工具节点）。 */
    static void demoClientLevelSearch() throws Exception {
        // 注意：不能带「2026 年 10 月」——模型内建时钟停在 2024 年，看到「未来日期」会拒绝搜索；
        // 就用 ws_test 实测验证过的措辞
        String question = "搜索一下 langgraph4j 的最新版本是多少？请根据搜索结果回答。";
        System.out.println("\n【客户端层搜索】同一事实问题，改由 WebSearchLlm（$web_search 内置工具）回答");
        System.out.println("  无状态图参与：LLM 客户端内部走 $web_search 循环，一次 complete() 直接出答案");

        LlmClient llm = new RetryingLlm(WebSearchLlm.kimi());
        long t0 = System.currentTimeMillis();
        String answer = llm.complete(question);
        System.out.println("\n  [一次调用耗时] " + (System.currentTimeMillis() - t0) / 1000 + "s（含 $web_search 两轮）");
        System.out.println("  [最终答案] " + answer.strip());
        System.out.println("""
                  对照 RealDemo 的图级 Search：那里搜索是显式工具节点 —— 每次调用进 State 轨迹、
                  可 interruptBefore 人工审批、可被 LLMCompiler 并行调度；这里是节点黑盒，
                实现最省事，但图上不可见、不可中断、结果不可复用。""");
    }
}
