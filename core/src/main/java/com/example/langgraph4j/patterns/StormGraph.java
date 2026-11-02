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
import java.util.function.Function;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 模式：STORM（从零生成维基百科式长文）—— langgraph4j 状态图版。
 *
 * <p>手写版（agent-design-patterns 项目 StormAgent）：直接让 LLM 一口气写长文
 * 容易泛泛而谈，STORM 的思路是先调研、再成文：</p>
 * <ol>
 *   <li>OutlineGenerator：根据主题生成文章大纲（章节列表）；</li>
 *   <li>MultiPerspectiveResearch：针对当前章节，让 LLM 模拟不同视角
 *       （领域专家、初学者读者、质疑者）提出问题，再用搜索工具回答——收集写作素材；</li>
 *   <li>SectionWriter：结合章节的问题与答案，撰写该章节正文；</li>
 *   <li>Polisher：补写引言与结论，汇总成完整文章。</li>
 * </ol>
 *
 * <p><b>建模决策</b>：阶段同样是静态流水线，但有两层「运行期才知道长度」的循环：
 * 大纲章节数（OutlineGenerator 产出后才知）与每章的视角数（本实现固定 3 个视角，
 * 收在 researcher 节点内部，等价手写版的内层 for）。外层「逐章调研+撰写」翻译为
 * <b>researcher ⇄ writer 两节点环</b>，由 `sectionIndex` 游标推进；章节的正文累积在
 * `article` appender 通道，Polisher 读全量章节拼成终稿：</p>
 *
 * <pre>
 *   START → outline_generator → researcher → section_writer ──(条件边)── 还有章节：→ researcher
 *                                                         └── 全部完成 → polisher → END
 * </pre>
 */
public class StormGraph {

    /** 调研视角（与手写版一致，静态固定，收在 researcher 节点内部）。 */
    static final String[] PERSPECTIVES = {"领域专家", "初学者读者", "质疑者"};

    // ---- State ----
    public static final class StormState extends AgentState {

        static final Map<String, Channel<?>> SCHEMA = Map.of(
                "topic", Channels.base(() -> ""),                  // 文章主题
                "sections", Channels.base(ArrayList::new),          // 大纲章节列表
                "sectionIndex", Channels.base(() -> 0),             // 当前章节游标
                "qas", Channels.base(ArrayList::new),             // 当前章节的调研问答（每章覆盖）
                "article", Channels.appender(ArrayList::new),       // "## 章节\n正文"（追加累积）
                "finalArticle", Channels.base(() -> ""));          // polisher 拼出的终稿

        StormState(Map<String, Object> initData) {
            super(initData);
        }

        String topic() {
            return this.<String>value("topic").orElse("");
        }

        @SuppressWarnings("unchecked")
        List<String> sections() {
            return this.<List<String>>value("sections").orElseGet(List::of);
        }

        int sectionIndex() {
            return this.<Integer>value("sectionIndex").orElse(0);
        }

        @SuppressWarnings("unchecked")
        List<String> qas() {
            return this.<List<String>>value("qas").orElseGet(List::of);
        }

        @SuppressWarnings("unchecked")
        List<String> article() {
            return this.<List<String>>value("article").orElseGet(List::of);
        }
    }

    static final String OUTLINE = "outline_generator";
    static final String RESEARCHER = "researcher";
    static final String WRITER = "section_writer";
    static final String POLISHER = "polisher";

    private final LlmClient llm;
    private final Function<String, String> search;

    public StormGraph(LlmClient llm, Function<String, String> search) {
        this.llm = llm;
        this.search = search;
    }

    /** outline_generator 节点：生成章节大纲（对应手写版第 1 步）。 */
    Map<String, Object> outlineGenerator(StormState state) {
        String outlineOutput = llm.complete("""
                请为主题「%s」生成一篇百科式文章的章节大纲。
                每行输出一个章节标题，不要输出其他内容。
                章节大纲：""".formatted(state.topic()));
        List<String> sections = parseSections(outlineOutput);
        System.out.println("\n[大纲生成器]\n" + String.join("\n", sections));
        return Map.of("sections", sections);
    }

    /** researcher 节点：当前章节 × 每个视角各提一问、搜索回答（对应手写版 research() 内层循环）。 */
    Map<String, Object> researcher(StormState state) {
        String section = state.sections().get(state.sectionIndex());
        System.out.println("\n===== 调研章节：" + section + " =====");
        List<String> qas = new ArrayList<>();
        for (String perspective : PERSPECTIVES) {
            String question = llm.complete("""
                    你是一位「%s」。针对「%s」这个主题，请提出一个你最关心的问题。
                    只输出问题本身。"""
                    .formatted(perspective, section));
            String answer = search.apply(question.strip());
            System.out.println("  [%s 提问] %s\n  [搜索回答] %s"
                    .formatted(perspective, question.strip(), answer));
            qas.add("Q: " + question.strip() + "\nA: " + answer);
        }
        return Map.of("qas", qas);  // base 通道：每章覆盖，不串素材
    }

    /** section_writer 节点：按当前章节的调研素材撰写正文（对应手写版 for 循环体的撰写部分）。 */
    Map<String, Object> sectionWriter(StormState state) {
        String section = state.sections().get(state.sectionIndex());
        String body = llm.complete("""
                请根据以下调研素材，为主题「%s」的章节「%s」撰写一段 2~3 句的百科式正文。

                调研问答：
                %s
                正文：""".formatted(state.topic(), section, String.join("\n", state.qas())));
        System.out.println("\n[章节撰写] " + section + "\n" + body.strip());
        return Map.of(
                "article", List.of("## " + section + "\n" + body.strip()),
                "sectionIndex", state.sectionIndex() + 1);
    }

    /** 条件边：章节未写完 → 回 researcher 调研下一章；全部完成 → polisher。 */
    String routeAfterWriter(StormState state) {
        if (state.sectionIndex() >= state.sections().size()) {
            System.out.println("  [路由] 全部 " + state.sections().size() + " 章写完 → polisher 汇总润色");
            return POLISHER;
        }
        System.out.println("  [路由] 已完成 " + state.sectionIndex() + "/" + state.sections().size()
                + " 章 → researcher 调研下一章");
        return RESEARCHER;
    }

    /** polisher 节点：写引言与结论，拼装终稿（对应手写版第 4 步）。 */
    Map<String, Object> polisher(StormState state) {
        String points = String.join("\n", state.sections());
        String intro = llm.complete("""
                请为主题「%s」的百科文章写一段 1~2 句的引言。
                正文要点：
                %s
                引言：""".formatted(state.topic(), points));
        String conclusion = llm.complete("""
                请为主题「%s」的百科文章写一段 1~2 句的总结。
                正文要点：
                %s
                总结：""".formatted(state.topic(), points));
        String fullArticle = "# " + state.topic() + "\n\n" + intro.strip() + "\n\n"
                + String.join("\n\n", state.article()) + "\n\n" + conclusion.strip();
        System.out.println("\n[成稿]\n" + fullArticle);
        return Map.of("finalArticle", fullArticle);
    }

    public StateGraph<StormState> build() throws Exception {
        return new StateGraph<>(StormState.SCHEMA, StormState::new)
                .addNode(OUTLINE, node_async(this::outlineGenerator))
                .addNode(RESEARCHER, node_async(this::researcher))
                .addNode(WRITER, node_async(this::sectionWriter))
                .addNode(POLISHER, node_async(this::polisher))
                .addEdge(StateGraph.START, OUTLINE)
                .addEdge(OUTLINE, RESEARCHER)
                .addEdge(RESEARCHER, WRITER)
                .addEdge(POLISHER, StateGraph.END)
                .addConditionalEdges(WRITER,
                        edge_async(this::routeAfterWriter),
                        Map.of(RESEARCHER, RESEARCHER, POLISHER, POLISHER));
    }

    /** 解析大纲文本为章节列表（与手写版 parseSections 相同的去编号逻辑）。 */
    static List<String> parseSections(String outlineOutput) {
        List<String> sections = new ArrayList<>();
        for (String line : outlineOutput.split("\n")) {
            String trimmed = line.strip().replaceFirst("^(\\d+[.、]|[一二三四五六七八九十]+[、.])\\s*", "");
            if (!trimmed.isEmpty()) {
                sections.add(trimmed);
            }
        }
        if (sections.isEmpty()) {
            throw new IllegalStateException("无法解析大纲: " + outlineOutput);
        }
        return sections;
    }

    /** 演示剧本与手写版一致：3 章 ×（3 视角提问 + 1 正文）+ 引言 + 总结，共 15 次 LLM 调用。 */
    public static void runScenario() throws Exception {
        System.out.println("""
                ── 手写版（先调研后成文）与本图的对照 ──
                   生成大纲                       → outline_generator 节点
                   for section in sections        → researcher ⇄ section_writer 两节点环（条件边）
                   research() 内层 3 视角循环     → researcher 节点内 List 展开（视角数固定）
                   article.add("## 章节+正文")      → article appender 通道追加
                   引言 + 总结 + 拼装             → polisher 节点 → END
                章节数是运行期量 → sectionIndex 游标自环；视角数固定 → 收在 researcher 节点内
                """);

        Function<String, String> search = question ->
                question.contains("虚拟线程")
                        ? "Java 21 正式引入虚拟线程（Virtual Threads, JEP 444），大幅简化高并发编程。"
                        : "未找到与「" + question + "」相关的信息。";

        ScriptedLlm llm = new ScriptedLlm()
                // 大纲
                .says("""
                        概述
                        语言新特性
                        适用场景""")
                // 章节 1：概述 —— 三个视角的提问
                .says("什么是虚拟线程？")
                .says("虚拟线程解决了什么问题？")
                .says("虚拟线程是银弹吗？")
                // 章节 1 正文
                .says("虚拟线程是 JDK 21 正式引入的轻量级并发原语（JEP 444），它把线程的创建成本大幅降低，让 Java 服务端编程回归简单的同步风格。")
                // 章节 2：语言新特性 —— 三个视角的提问
                .says("虚拟线程在 Java 的哪个版本引入？")
                .says("虚拟线程与传统平台线程有何区别？")
                .says("虚拟线程的 API 是什么样的？")
                // 章节 2 正文
                .says("开发者通过 Thread.ofVirtual() 或 Executors.newVirtualThreadPerTaskExecutor() 即可创建虚拟线程，无需学习新的异步 API。")
                // 章节 3：适用场景 —— 三个视角的提问
                .says("虚拟线程适合什么类型的应用？")
                .says("使用虚拟线程有哪些注意事项？")
                .says("虚拟线程对吞吐量的提升有多大？")
                // 章节 3 正文
                .says("虚拟线程尤其适合 IO 密集型、高并发的服务端应用，如网关、聊天服务与数据库中间件；CPU 密集场景则收益有限。")
                // 引言 + 总结
                .says("本文系统介绍 Java 虚拟线程：它是什么、如何上手以及适合哪些场景。")
                .says("虚拟线程以极低的成本缓解了 Java 高并发编程的复杂度，是近年来 JVM 最重要的语言级更新之一。");

        var graph = new StormGraph(llm, search).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(GraphInput.args(
                Map.of("topic", "Java 虚拟线程")),
                RunnableConfig.builder().threadId("storm-场景").build());

        finalState.ifPresent(s ->
                System.out.println("\n  [最终文章] 共 " + s.value("article").map(a -> ((List<?>) a).size()).orElse(0)
                        + " 章，见上方 [成稿]"));
    }
}
