package com.example.agent.patterns.rewoo;

import com.example.agent.llm.LlmClient;
import com.example.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式 3：REWOO（Reasoning Without Observation）
 *
 * <p>ReAct 的提示词结构是 Thought → Action → Observation，每一步都要重新调用 LLM，
 * 长任务开销巨大。REWOO 把 Observation 从显式循环中拿掉，改为「一次规划 + 批量执行」：</p>
 * <ul>
 *   <li>Planner：一次 LLM 调用生成相互依赖的链式计划，
 *       每步形如 {@code Plan i: 描述} 和 {@code #Ei = 工具名[参数]}，
 *       参数中可以用 {@code #Ej} 引用前面步骤的结果变量；</li>
 *   <li>Worker：按顺序执行计划，把每步结果存入变量，并替换后续参数中的变量引用
 *       ——观察结果被隐式地嵌入到下一步的执行单元中；</li>
 *   <li>Solver：把所有「Plan + 证据」整合成最终答案。</li>
 * </ul>
 * <p>相比 ReAct，LLM 调用次数从 O(步数×2) 降到 O(1) + Solver，显著省钱省延迟。</p>
 */
public class ReWooAgent {

    private static final Pattern STEP_PATTERN =
            Pattern.compile("#(E\\d+)\\s*=\\s*([A-Za-z0-9_]+)\\[(.*)\\]");

    private final LlmClient llm;
    private final ToolRegistry tools;

    public ReWooAgent(LlmClient llm, ToolRegistry tools) {
        this.llm = llm;
        this.tools = tools;
    }

    /** 一条计划：描述 + 变量名 + 工具名 + 原始参数（可能含 #E 变量引用）。 */
    record PlanStep(String description, String variable, String toolName, String rawArgs) {
    }

    public String run(String question) {
        // ---- Planner：一次调用产出完整计划 ----
        String planOutput = llm.complete("""
                请把用户的问题拆解为若干相互依赖的计划步骤。
                每个步骤包含两行：
                  Plan i: 步骤描述
                  #Ei = 工具名[参数]（参数中可用 #Ej 引用前面步骤的结果）
                可用工具：
                %s
                问题：%s
                计划：""".formatted(tools.describe(), question));
        log("\n[Planner 输出]\n" + planOutput);

        List<PlanStep> plan = parsePlan(planOutput);

        // ---- Worker：顺序执行，做变量替换 ----
        var variables = new java.util.HashMap<String, String>();
        List<String> evidence = new ArrayList<>();
        for (PlanStep step : plan) {
            String args = substituteVariables(step.rawArgs(), variables);
            String result = tools.find(step.toolName())
                    .map(tool -> tool.observe(args))
                    .orElse("工具未注册: " + step.toolName());
            variables.put(step.variable(), result);
            evidence.add(step.description() + "（" + step.variable() + " = " + result + "）");
            log("[Worker] " + step.variable() + " = " + step.toolName() + "[" + args + "] -> " + result);
        }

        // ---- Solver：汇总证据，生成最终答案 ----
        String finalAnswer = llm.complete("""
                根据以下计划步骤及其执行结果（证据），回答用户的问题。

                问题：%s
                计划与证据：
                %s
                最终答案：""".formatted(question, String.join("\n", evidence)));
        log("\n[Solver 最终答案] " + finalAnswer);
        return finalAnswer;
    }

    static List<PlanStep> parsePlan(String output) {
        List<PlanStep> steps = new ArrayList<>();
        String[] lines = output.split("\n");
        String currentDesc = "";
        for (String line : lines) {
            String trimmed = line.strip();
            Matcher matcher = STEP_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                steps.add(new PlanStep(currentDesc, matcher.group(1), matcher.group(2), matcher.group(3)));
                currentDesc = "";
            } else if (trimmed.matches("(?i)^Plan\\s*\\d+[:：].*")) {
                currentDesc = trimmed.replaceFirst("(?i)^Plan\\s*\\d+[:：]\\s*", "");
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("无法从 Planner 输出解析计划: " + output);
        }
        return steps;
    }

    static String substituteVariables(String args, java.util.Map<String, String> variables) {
        String result = args;
        for (var entry : variables.entrySet()) {
            // 用步骤结果中的第一个数字片段做替换，模拟「从证据中提取关键值」
            String value = entry.getValue();
            Matcher number = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(value);
            String replacement = number.find() ? number.group() : value;
            result = result.replace("#" + entry.getKey(), replacement);
        }
        return result;
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
