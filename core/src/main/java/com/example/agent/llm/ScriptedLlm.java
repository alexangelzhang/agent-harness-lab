package com.example.agent.llm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Function;

/**
 * 基于规则的脚本化 LLM：按顺序弹出预置回复，弹尽后回退到规则函数。
 * 用于在没有 API Key 的环境下完整跑通各个模式的控制流。
 * 也可以传入一个规则函数，根据提示词内容动态生成回复。
 */
public class ScriptedLlm implements LlmClient {

    private final Deque<Function<String, String>> responders = new ArrayDeque<>();

    public ScriptedLlm() {
    }

    /** 依次追加回复提供者；每个 provider 收到完整 prompt，返回回复。 */
    public ScriptedLlm next(Function<String, String> responder) {
        responders.add(responder);
        return this;
    }

    /** 追加一个无条件返回固定文本的回复。 */
    public ScriptedLlm says(String fixedReply) {
        return next(prompt -> fixedReply);
    }

    /** 追加多个依次返回固定文本的回复。 */
    public ScriptedLlm says(String... fixedReplies) {
        Arrays.stream(fixedReplies).forEach(this::says);
        return this;
    }

    /** 追加一个基于关键字匹配的回复：prompt 包含 key 时返回 value。 */
    public ScriptedLlm whenContains(String key, String value) {
        return next(prompt -> prompt.contains(key) ? value : null);
    }

    @Override
    public String complete(String prompt) {
        Function<String, String> responder = responders.poll();
        if (responder == null) {
            throw new IllegalStateException(
                    "ScriptedLlm 没有更多预置回复。最后一个 prompt：\n" + abbreviate(prompt));
        }
        String reply = responder.apply(prompt);
        if (reply == null) {
            throw new IllegalStateException(
                    "ScriptedLlm 的当前 responder 返回 null（未命中匹配条件）。prompt：\n" + abbreviate(prompt));
        }
        return reply;
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
