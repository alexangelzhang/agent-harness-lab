package com.example.harness.memory.kibitzer;

import java.util.regex.Pattern;

/**
 * 喂给侧车的一条主会话事件：prompt / 工具调用 / 工具结果。
 *
 * <p>对应 OmO Kibitzer 的「bounded, redacted event」：进侧车之前先过两道——
 * 截断（事件正文最长 {@value #MAX_CHARS} 字符）和脱敏（API key / token /
 * 密码这类 secret 在落侧车之前就遮掉）。侧车永远看不到完整原文，
 * 这本身就是防线，不靠侧车自觉。</p>
 */
public record KibitzerEvent(Kind kind, String text) {

    /** 事件种类：用户输入 / 主 agent 的工具调用 / 工具返回。 */
    public enum Kind { PROMPT, TOOL_CALL, TOOL_RESULT }

    /** 单条事件正文的截断上限。 */
    public static final int MAX_CHARS = 500;

    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9]{6,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+");

    /** 构造一条脱敏 + 截断后的事件（先脱敏再截断——
     *  先截断会让横跨边界的 secret 留下半截真值）。 */
    public static KibitzerEvent of(Kind kind, String raw) {
        String redacted = API_KEY.matcher(raw).replaceAll("sk-***");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1=***");
        String bounded = redacted.length() <= MAX_CHARS
                ? redacted : redacted.substring(0, MAX_CHARS) + "…";
        return new KibitzerEvent(kind, bounded);
    }
}
