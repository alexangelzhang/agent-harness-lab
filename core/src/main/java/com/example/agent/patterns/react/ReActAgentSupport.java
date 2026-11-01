package com.example.agent.patterns.react;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ReAct 的 Action 解析逻辑，供多个模式复用。 */
public final class ReActAgentSupport {

    private static final Pattern ACTION_PATTERN =
            Pattern.compile("(?s)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    private ReActAgentSupport() {
    }

    /** 尝试从 LLM 输出中解析 Action: 工具名[参数]。 */
    public static Optional<Action> tryParseAction(String output) {
        Matcher matcher = ACTION_PATTERN.matcher(output);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new Action(matcher.group(1), matcher.group(2).trim()));
    }

    public record Action(String name, String input) {
    }
}
