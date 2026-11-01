package com.example.harness.sensor;

import com.example.langgraph4j.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 推断型 sensor：LLM 语义评审（语义重复 / 过度设计 / 暴力修复这类
 * <b>计算型抓不稳、只有语义判断能碰</b>的问题——Fowler：高影响问题
 * 两者都抓不可靠，这里至少把推断型信号结构化）。
 *
 * <p>显式标注贵 + 不确定：kind 是 {@link Kind#INFERENTIAL}，编排层
 * 抽样跑（quality left），不会每次都烧。产出解析成 {@link Finding}
 * ——推断型信号也统一成「问题 + 修正指引」的格式，模型下一轮照样
 * 能消费，只是可信度按推断型对待。</p>
 */
public final class LlmReviewSensor implements Sensor {

    private final String ruleName;
    private final LlmClient llm;

    public LlmReviewSensor(LlmClient llm) {
        this("语义评审", llm);
    }

    public LlmReviewSensor(String ruleName, LlmClient llm) {
        this.ruleName = ruleName;
        this.llm = llm;
    }

    @Override
    public String name() {
        return ruleName;
    }

    @Override
    public Kind kind() {
        return Kind.INFERENTIAL;
    }

    @Override
    public List<Finding> check(CodeChange change) {
        String raw = llm.complete("""
                你是代码评审感知器，只找三类问题：语义重复（两处代码长得不像
                但干的是同一件事）、过度设计（为不存在的需求预留的抽象）、
                暴力修复（ suppress 报错 / 写死值过关 / 注释掉失败断言）。

                每条发现严格按格式输出一行，没有就一行都不输出：
                FINDING: <位置> | <问题一句话> | <具体怎么改>

                待审文件：%s
                %s
                """.formatted(change.fileName(), change.content()));
        List<Finding> out = new ArrayList<>();
        for (String line : raw.lines().toList()) {
            String s = line.strip();
            if (!s.startsWith("FINDING:")) {
                continue;
            }
            String[] parts = s.substring("FINDING:".length()).split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            out.add(Finding.hint(ruleName, parts[0].strip(),
                    parts[1].strip(), parts[2].strip()));
        }
        return out;
    }
}
