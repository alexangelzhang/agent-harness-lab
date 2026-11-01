package com.example.harness.sensor;

/**
 * 一条 sensor 发现：问题在哪、怎么改——修正指引是给模型消费的
 * （Fowler：「自带修正指引的自定义 linter 消息是一种正向 prompt
 * injection」，信号本身就是下一轮行动的输入，不只是给人看的报告）。
 *
 * @param rule        出自哪条规则（sensor 名）
 * @param severity    严重度：BLOCK（回流必须处理）/ HINT（建议）
 * @param location    位置（文件名:行号粗标，演示够用）
 * @param problem     问题一句话
 * @param fix         修正指引：具体可执行，不许「请注意」式空话
 * @param guidesNote  非空 = 该项目 guides（AGENTS.md 类）已写过这条要求，
 *                    sensor 仍发现违反——guides 与 sensors 的一致性缺口，
 *                    原样标注不替模型裁决
 */
public record Finding(String rule, Severity severity, String location,
                      String problem, String fix, String guidesNote) {

    /** 严重度：BLOCK 回流下一轮必须处理；HINT 只是建议。 */
    public enum Severity { BLOCK, HINT }

    public static Finding block(String rule, String location,
                                String problem, String fix) {
        return new Finding(rule, Severity.BLOCK, location, problem, fix, "");
    }

    public static Finding hint(String rule, String location,
                               String problem, String fix) {
        return new Finding(rule, Severity.HINT, location, problem, fix, "");
    }

    /** 渲染成回流给模型的结构化块（正向 prompt injection 的载体）。 */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder()
                .append("  [").append(severity).append("] ").append(location)
                .append("（").append(rule).append("）\n")
                .append("  问题：").append(problem).append("\n")
                .append("  怎么改：").append(fix);
        if (!guidesNote.isEmpty()) {
            sb.append("\n  ⚠ ").append(guidesNote);
        }
        return sb.toString();
    }
}
