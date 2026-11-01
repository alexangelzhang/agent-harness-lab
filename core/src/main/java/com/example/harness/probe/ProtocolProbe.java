package com.example.harness.probe;

import com.example.agent.tool.Tool;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 协议自检探针：跑真实 ReAct 实验<b>之前</b>，先花一次最小调用确认模型
 * 会按协议吐 Action 行——不然实验跑 N 遍全是自然语言闲聊，额度烧完了
 * 才发现装置都没搭对（realfoldeval 第一版 6/6 遍的教训）。
 *
 * <p>探针判定：把「协议说明 + 一个自检问题」发给模型，回的行解析出
 * {@code Action: 工具名[参数]} 且工具名在<b>声明的工具表</b>里才算过。
 * 两层失败分开报：没吐 Action 行 = prompt 缺协议说明；吐了的工具不在
 * 表里 = 模型自造工具，提示词和工具表对不上。</p>
 *
 * <p>{@link #check} 返回结果不抛，适合打报告；{@link #require} 是硬闸门，
 * 探针不过直接抛——真实 demo 的 wiring 层用它 fail fast，别拿额度跑
 * 注定无效的实验。</p>
 */
public final class ProtocolProbe {

    private static final Pattern ACTION = Pattern.compile("(?s)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    private ProtocolProbe() {
    }

    /** 探针结果：过没过 + 模型原话 + 说人话的判定理由。 */
    public record Result(boolean ok, String rawOutput, String reason) {
    }

    /**
     * 发一次探针调用（默认 Action 协议）。
     *
     * @param llm            真实客户端（外面包好 RetryingLlm 更稳）
     * @param protocolPrompt 待验证的协议说明（协议说明部分照抄实验要用的 prompt）
     * @param declaredTools  实验声明的工具表（工具名集合）
     */
    public static Result check(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                               Collection<String> declaredTools) {
        return check(llm, protocolPrompt, ACTION, declaredTools);
    }

    /**
     * 发一次探针调用（自定义协议行 pattern，如 multiagent 的「分派：专员 | 任务」）。
     *
     * @param protocolLine   协议行的正则，group(1) 必须是被引用的名字（工具名 / 专员名）
     */
    public static Result check(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                               Pattern protocolLine, Collection<String> declaredTools) {
        String out = llm.complete(protocolPrompt + "\n\n自检问题：用户问「我的订单 1001 买的什么，什么时候到？」")
                .strip();
        Matcher matcher = protocolLine.matcher(out);
        if (!matcher.find()) {
            return new Result(false, out, "没吐协议行——prompt 里大概率缺协议说明"
                    + "（可用对象清单 + 每轮输出格式的约定）");
        }
        String name = matcher.group(1).strip();
        if (!declaredTools.contains(name)) {
            return new Result(false, out, "协议行里的「" + name + "」不在声明的清单 "
                    + declaredTools + " 里——模型自造名字，提示词和清单没对上");
        }
        return new Result(true, out, "探针通过：" + name + " 已按协议输出");
    }

    /** 硬闸门：探针不过直接抛，message 里带判定理由和模型原话（截取）。 */
    public static Result require(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                                 Collection<String> declaredTools) {
        return require(llm, protocolPrompt, ACTION, declaredTools);
    }

    /** 硬闸门（自定义协议行 pattern）。 */
    public static Result require(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                                 Pattern protocolLine, Collection<String> declaredTools) {
        Result result = check(llm, protocolPrompt, protocolLine, declaredTools);
        if (!result.ok()) {
            String sample = result.rawOutput().length() > 120
                    ? result.rawOutput().substring(0, 120) + "…" : result.rawOutput();
            throw new IllegalStateException("协议自检探针未通过（" + result.reason()
                    + "）。模型原话：" + sample);
        }
        return result;
    }

    /** demo wiring 专用：探针不过打印原因退出（exit 1），过了返回结果。 */
    public static Result requireOrExit(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                                       Collection<String> declaredTools) {
        return requireOrExit(llm, protocolPrompt, ACTION, declaredTools);
    }

    /** demo wiring 专用（自定义协议行 pattern）。 */
    public static Result requireOrExit(com.example.langgraph4j.llm.LlmClient llm, String protocolPrompt,
                                       Pattern protocolLine, Collection<String> declaredTools) {
        try {
            Result result = require(llm, protocolPrompt, protocolLine, declaredTools);
            System.out.println("  [探针预检] 过——" + result.reason());
            return result;
        } catch (IllegalStateException e) {
            System.err.println("  [探针预检] 挂，实验中止：" + e.getMessage());
            System.exit(1);
            throw new AssertionError("unreachable");
        }
    }

    /** 从 Tool 列表提取工具名集合（真实 demo wiring 少写一层）。 */
    public static java.util.Set<String> names(java.util.List<Tool> tools) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Tool t : tools) {
            names.add(t.name());
        }
        return names;
    }

    /** multiagent 分派协议的探针 prompt 模板：专员名单必须写进 prompt，不然模型照单自造。 */
    public static String rosterProtocol(Collection<String> expertNames) {
        return """
                你是主管，把任务拆给下面的专员，可以派多人，每人一段。
                可选专员：%s
                输出格式：每行一条「分派：专员名 | 交给他的具体任务」。
                专员名必须从上面的列表里选，不要自造名字，不要输出别的内容。
                """.formatted(String.join("、", expertNames));
    }
}
