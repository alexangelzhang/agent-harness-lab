package com.example.agentloop;

import com.example.langgraph4j.llm.EnvException;
import com.example.langgraph4j.llm.LlmClient;

/**
 * LLM 版验收者：拿目标条件对照本轮成果，回 YES 或 NO：差在哪。
 * NO 的理由原样回流给下一轮行动（{@link LlmActor} 会把它拼进提示词）。
 *
 * <p>想要确定性终点（「测试全过」「分数 ≥ 90」这种硬指标），别靠它——
 * 那种用 {@link AgentLoop.StopCondition}，模型判断「够好了吧」是靠不住的。
 */
public class LlmVerifier implements AgentLoop.Verifier {

    private final LlmClient llm;
    private final String goalCriteria;

    public LlmVerifier(LlmClient llm, String goalCriteria) {
        this.llm = llm;
        this.goalCriteria = goalCriteria;
    }

    @Override
    public Verdict verify(LoopContext ctx, String actionOutput) {
        String prompt = """
                你是验收方。对照下面的达标条件看本轮成果够不够格，按意思判断，
                换个说法也算数。

                达标条件：%s
                任务背景：%s
                本轮成果：%s

                只回答 YES；不够格就回 NO：再加一句具体差在哪。
                """.formatted(goalCriteria, ctx.task(), actionOutput);
        final String reply;
        try {
            reply = llm.complete(prompt);
        } catch (RuntimeException e) {
            // 超时 / 断连这类环境故障交给 AgentLoop 单独处理，不占修复额度
            throw EnvException.wrapIfEnv(e);
        }
        boolean pass = reply != null
                && reply.strip().replace("*", "").toUpperCase().startsWith("YES");
        if (pass) {
            return Verdict.pass("达标");
        }
        String reason = reply == null ? "验收方没有回复" : reply.strip();
        int idx = reason.indexOf('：');
        if (idx >= 0 && idx < reason.length() - 1) {
            reason = reason.substring(idx + 1).strip();
        }
        return Verdict.retry(reason.isEmpty() ? "未达标（未说明原因）" : reason);
    }
}
