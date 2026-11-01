package com.example.agentloop;

import com.example.langgraph4j.llm.EnvException;
import com.example.langgraph4j.llm.LlmClient;

/**
 * LLM 版行动者：把任务、走过的轮次和上一轮的反馈意见拼进提示词，
 * 让模型给出本轮的完整结果。
 *
 * <p>反馈意见（{@link LoopContext#lastFeedback()}）是反馈式重试的载体：
 * 上一轮验收说差在哪，这一轮就照着改。
 */
public class LlmActor implements AgentLoop.Actor {

    private final LlmClient llm;

    public LlmActor(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public String act(LoopContext ctx) {
        StringBuilder prompt = new StringBuilder("""
                你在一个「行动—评估—改进」的循环里干活。每一轮交一版成果，
                验收方会指出差距，你下一轮照着改。直接输出本轮成果的完整内容，
                不要输出标题、引号和解释。

                任务：%s
                """.formatted(ctx.task()));
        if (!ctx.steps().isEmpty()) {
            prompt.append("\n已经走过的轮次：\n");
            for (LoopStep s : ctx.steps()) {
                prompt.append("  第 ").append(s.turn()).append(" 轮成果：").append(s.output())
                        .append("\n  验收意见：").append(s.verdict().reason()).append("\n");
            }
        }
        ctx.lastFeedback().ifPresent(f -> prompt.append("\n上一轮验收要你改：").append(f).append("\n"));
        prompt.append("\n本轮成果：");
        try {
            return llm.complete(prompt.toString()).strip();
        } catch (RuntimeException e) {
            // 超时 / 断连这类环境故障交给 AgentLoop 单独处理，不占修复额度
            throw EnvException.wrapIfEnv(e);
        }
    }
}
