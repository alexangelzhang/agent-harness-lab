package com.example.agent.patterns.storm;

import com.example.agent.llm.LlmClient;
import com.example.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式 9：STORM（从零生成维基百科式长文）
 *
 * <p>论文《Assisting in Writing Wikipedia-like Articles From Scratch with
 * Large Language Models》。直接让 LLM 一口气写长文容易泛泛而谈，STORM 的思路
 * 是先调研、再成文：</p>
 * <ol>
 *   <li>OutlineGenerator：根据主题生成文章大纲（章节列表）；</li>
 *   <li>MultiPerspectiveResearch：针对每个章节，先让 LLM 模拟不同视角
 *       （如领域专家、普通读者、批评者）提出问题，再用搜索工具回答
 *       ——收集写作素材；</li>
 *   <li>SectionWriter：结合章节的问题与答案，撰写该章节正文；</li>
 *   <li>Polisher：补写引言与结论，汇总成完整文章。</li>
 * </ol>
 */
public class StormAgent {

    private static final String[] PERSPECTIVES = {"领域专家", "初学者读者", "质疑者"};

    private final LlmClient llm;
    private final Tool search;

    public StormAgent(LlmClient llm, Tool search) {
        this.llm = llm;
        this.search = search;
    }

    public String run(String topic) {
        // ---- 1. 生成大纲 ----
        String outlineOutput = llm.complete("""
                请为主题「%s」生成一篇百科式文章的章节大纲。
                每行输出一个章节标题，不要输出其他内容。
                章节大纲：""".formatted(topic));
        List<String> sections = parseSections(outlineOutput);
        log("\n[大纲生成器]\n" + String.join("\n", sections));

        // ---- 2. 逐章调研 + 3. 撰写 ----
        List<String> article = new ArrayList<>();
        for (String section : sections) {
            List<String> qas = research(section);
            String body = llm.complete("""
                    请根据以下调研素材，为主题「%s」的章节「%s」撰写一段 2~3 句的百科式正文。

                    调研问答：
                    %s
                    正文：""".formatted(topic, section, String.join("\n", qas)));
            log("\n[章节撰写] " + section + "\n" + body.strip());
            article.add("## " + section + "\n" + body.strip());
        }

        // ---- 4. 汇总润色 ----
        String intro = llm.complete("""
                请为主题「%s」的百科文章写一段 1~2 句的引言。
                正文要点：
                %s
                引言：""".formatted(topic, String.join("\n", sections)));
        String conclusion = llm.complete("""
                请为主题「%s」的百科文章写一段 1~2 句的总结。
                正文要点：
                %s
                总结：""".formatted(topic, String.join("\n", sections)));

        String fullArticle = "# " + topic + "\n\n" + intro.strip() + "\n\n"
                + String.join("\n\n", article) + "\n\n" + conclusion.strip();
        log("\n[成稿]\n" + fullArticle);
        return fullArticle;
    }

    /** 多视角调研：每个视角提出一个问题，用搜索工具回答。 */
    private List<String> research(String section) {
        List<String> qas = new ArrayList<>();
        for (String perspective : PERSPECTIVES) {
            String question = llm.complete("""
                    你是一位「%s」。针对「%s」这个主题，请提出一个你最关心的问题。
                    只输出问题本身。"""
                    .formatted(perspective, section));
            String answer = search.observe(question.strip());
            log("  [%s 提问] %s\n  [搜索回答] %s".formatted(perspective, question.strip(), answer));
            qas.add("Q: " + question.strip() + "\nA: " + answer);
        }
        return qas;
    }

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

    private static void log(String message) {
        System.out.println(message);
    }
}
