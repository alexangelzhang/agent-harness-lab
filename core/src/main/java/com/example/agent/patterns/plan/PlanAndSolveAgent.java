package com.example.agent.patterns.plan;

import com.example.agent.llm.LlmClient;
import com.example.agent.patterns.react.ReActAgentSupport;
import com.example.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式 2：Plan & Solve（先计划再执行）
 *
 * <p>适合需要规划、且执行过程中计划可能变化的任务（论文：
 * 《Plan-and-Solve Prompting》）。相比 ReAct 每步都重新决策，
 * 它先生成完整的多步计划，再逐步骤执行：</p>
 * <ul>
 *   <li>Planner：根据目标生成编号的多步计划；</li>
 *   <li>Executor：携带「目标 + 当前步骤 + 可用工具」调用 LLM，
 *       或直接作答，或输出 Action: 工具名[参数] 调用一次工具；</li>
 *   <li>Replanner：某一步失败时，结合目标、原计划、已完成步骤重新规划剩余步骤。</li>
 * </ul>
 */
public class PlanAndSolveAgent {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final int maxSteps;

    public PlanAndSolveAgent(LlmClient llm, ToolRegistry tools, int maxSteps) {
        this.llm = llm;
        this.tools = tools;
        this.maxSteps = maxSteps;
    }

    public record Plan(List<String> steps) {
    }

    public String run(String question) {
        Plan plan = planner(question);
        log("\n[Planner 生成的计划]");
        for (int i = 0; i < plan.steps().size(); i++) {
            log((i + 1) + ". " + plan.steps().get(i));
        }

        List<String> completed = new ArrayList<>();
        int cursor = 0;
        int replans = 0;
        while (cursor < plan.steps().size()) {
            if (completed.size() + replans >= maxSteps) {
                throw new IllegalStateException("超过最大执行步数，任务未完成");
            }
            String step = plan.steps().get(cursor);
            log("\n[Executor] 执行步骤 " + (cursor + 1) + ": " + step);
            String result = executor(question, step);

            if (isFailure(result)) {
                replans++;
                log("[Replanner] 步骤失败（" + result + "），重新规划剩余步骤");
                // Replanner 输出的是「剩余步骤」的完整新计划，从头开始执行
                plan = replanner(question, plan, completed);
                log("[Replanner] 新计划: " + plan.steps());
                if (replans > maxSteps / 2) {
                    throw new IllegalStateException("Replanner 重规划次数过多，任务无法继续");
                }
                cursor = 0;
            } else {
                log("[步骤结果] " + result);
                completed.add(step + " => " + result);
                cursor++;
            }
        }

        StringBuilder summary = new StringBuilder();
        for (String c : completed) {
            summary.append(c).append('\n');
        }
        String finalAnswer = llm.complete("""
                基于以下已完成的计划步骤及其结果，给出对原始问题的最终回答（一句话）。

                原始问题：%s
                已完成步骤：
                %s
                最终回答：""".formatted(question, summary));
        log("\n[最终回答] " + finalAnswer);
        return finalAnswer;
    }

    /** Planner：zero-shot 提示词，让 LLM 生成编号计划。 */
    private Plan planner(String question) {
        String output = llm.complete("""
                你是一个任务规划器。请把用户的问题拆解为若干有序的、可执行的具体步骤。
                只输出编号步骤，每行一条，不要输出其他内容。

                问题：%s
                计划：""".formatted(question));
        return new Plan(parseNumberedSteps(output));
    }

    /** Executor：单步执行，允许 LLM 直接作答或调用一次工具。 */
    private String executor(String question, String step) {
        String output = llm.complete("""
                请完成下面这个任务步骤。你可以直接使用工具，工具调用格式为 Action: 工具名[参数]。
                可用工具：
                %s
                如果不需要工具，直接给出该步骤的完成结果。

                原始问题：%s
                当前步骤：%s
                回答：""".formatted(tools.describe(), question, step));
        var action = ReActAgentSupport.tryParseAction(output);
        if (action.isPresent()) {
            if (action.get().name().equals("Finish")) {
                return action.get().input();
            }
            return tools.find(action.get().name())
                    .map(tool -> tool.observe(action.get().input()))
                    .orElse("工具未注册: " + action.get().name());
        }
        return output.trim();
    }

    /** Replanner：结合目标、原计划与已完成情况，输出剩余步骤的新计划。 */
    private Plan replanner(String question, Plan oldPlan, List<String> completed) {
        String output = llm.complete("""
                原计划执行中某一步失败了，请根据目标、原计划和已完成情况，重新规划剩余的步骤。
                只输出剩余步骤的编号列表（从 1 开始重新编号），不要输出其他内容。

                目标：%s
                原计划：%s
                已完成：
                %s
                剩余步骤计划：""".formatted(question, oldPlan.steps(), completed));
        return new Plan(parseNumberedSteps(output));
    }

    static List<String> parseNumberedSteps(String output) {
        List<String> steps = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.matches("^\\d+[.、)]\\s*.*")) {
                steps.add(trimmed.replaceFirst("^\\d+[.、)]\\s*", ""));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("无法从 LLM 输出解析计划: " + output);
        }
        return steps;
    }

    static boolean isFailure(String result) {
        return result.contains("工具执行出错") || result.contains("工具未注册")
                || result.contains("未找到") || result.contains("抓取失败")
                || result.contains("不存在");
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
