package com.example.langgraph4j.llm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 脚本化 LLM：按顺序弹出预置回复，弹尽后抛异常。
 * 与 agent-design-patterns 项目中的 ScriptedLlm 相同，便于无 API Key 离线演示。
 */
public class ScriptedLlm implements LlmClient {

    private final Deque<Function<String, String>> responders = new ArrayDeque<>();

    public ScriptedLlm next(Function<String, String> responder) {
        responders.add(responder);
        return this;
    }

    public ScriptedLlm says(String fixedReply) {
        return next(prompt -> fixedReply);
    }

    public ScriptedLlm says(String... fixedReplies) {
        Arrays.stream(fixedReplies).forEach(this::says);
        return this;
    }

    @Override
    public String complete(String prompt) {
        Function<String, String> responder = responders.poll();
        if (responder == null) {
            throw new IllegalStateException("ScriptedLlm 没有更多预置回复。最后一个 prompt：\n"
                    + (prompt.length() <= 200 ? prompt : prompt.substring(0, 200) + "..."));
        }
        String reply = responder.apply(prompt);
        if (reply == null) {
            throw new IllegalStateException("ScriptedLlm 的当前 responder 返回 null。prompt：\n" + prompt);
        }
        return reply;
    }

    /**
     * 流式：弹出同一条预置回复，按小块逐次回调——块与块之间没有真实网络
     * 延迟，但「逐块消费 / 中途中止」的代码路径和真实实现完全一致，
     * 离线就能演练预算熔断、关键词收口这些流式专属逻辑。
     */
    @Override
    public boolean stream(String prompt, Predicate<String> onChunk) {
        String reply = complete(prompt);
        int step = Math.max(1, reply.length() / 8);
        for (int i = 0; i < reply.length(); i += step) {
            String chunk = reply.substring(i, Math.min(i + step, reply.length()));
            if (!onChunk.test(chunk)) {
                return false;
            }
        }
        return true;
    }
}
