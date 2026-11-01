package com.example.agent.patterns.react;

import com.example.agent.llm.LlmClient;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolRegistry;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式 1：ReAct（Reasoning + Acting）
 *
 * <p>最基础的 Agent 模式：Thought → Action → Observation 循环。
 * 每轮把「问题 + 到目前为止的 scratchpad」发给 LLM，LLM 输出下一步的
 * Thought 和 Action（Action 指向某个工具，Finish 表示任务完成），
 * 框架执行工具、把 Observation 追加回 scratchpad，再进入下一轮。
 * 提示词模板（few-shot 的 Question/Thought/Action/Observation 格式）
 * 就是人类思维模式的结构化体现。</p>
 */
public class ReActAgent {

    private static final Pattern ACTION_PATTERN =
            Pattern.compile("(?s)Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]");

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final int maxIterations;

    public ReActAgent(LlmClient llm, ToolRegistry tools, int maxIterations) {
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
    }

    public String run(String question) {
        StringBuilder scratchpad = new StringBuilder();
        for (int i = 1; i <= maxIterations; i++) {
            String prompt = buildPrompt(question, scratchpad.toString());
            String output = llm.complete(prompt);
            log("\n[LLM 第 " + i + " 轮]\n" + output);

            Action action = parseAction(output);
            if (action.name().equals("Finish")) {
                return action.input();
            }
            Optional<Tool> tool = tools.find(action.name());
            if (tool.isEmpty()) {
                throw new IllegalStateException("LLM 请求了未注册的工具: " + action.name());
            }
            String observation = tool.get().observe(action.input());
            log("[Observation] " + observation);
            scratchpad.append(output).append("\nObservation: ").append(observation).append('\n');
        }
        throw new IllegalStateException("超过最大迭代次数 " + maxIterations + "，任务未完成");
    }

    private String buildPrompt(String question, String scratchpad) {
        return """
                请按照 Thought -> Action -> Observation 的格式回答问题。
                你可以使用以下工具：
                %s
                Action 的格式为：Action: 工具名[参数]；当任务完成时使用 Action: Finish[最终答案]。
                只输出 Thought 和 Action，Observation 由系统自动补充。

                Question: %s
                %sThought:""".formatted(tools.describe(), question, scratchpad);
    }

    static Action parseAction(String output) {
        Matcher matcher = ACTION_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("无法从 LLM 输出中解析 Action: " + output);
        }
        return new Action(matcher.group(1), matcher.group(2).trim());
    }

    record Action(String name, String input) {
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
