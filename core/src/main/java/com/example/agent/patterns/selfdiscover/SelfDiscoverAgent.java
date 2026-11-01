package com.example.agent.patterns.selfdiscover;

import com.example.agent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式 8：Self-Discover（自我发现）
 *
 * <p>论文《Self-Discover: Large Language Models Self-Compose Reasoning Structures》。
 * 与 Plan&amp;Solve 反思「计划是否需要调整」不同，Self-Discover 是在更小粒度上
 * 对「任务本身该如何推理」进行反思 —— 让模型先为自己的任务挑选并组装一套
 * 推理结构，再按这套结构解题：</p>
 * <ol>
 *   <li>Selector：从原子推理模块库（分步骤推理、拆解子问题、批判性思考、
 *       类比、验证……）中选出与当前任务相关的模块；</li>
 *   <li>Adaptor：把选中的模块编排/实例化成针对本任务的结构化推理计划（JSON）；</li>
 *   <li>Implementor：严格按推理结构逐步执行（可调用工具），得出最终答案。</li>
 * </ol>
 */
public class SelfDiscoverAgent {

    /** 原子推理模块库。 */
    static final List<String> REASONING_MODULES = List.of(
            "分步骤推理：把问题拆成一步一步的中间推理",
            "拆解为子问题：把复杂问题分解成若干独立子问题逐一攻克",
            "批判性思考：识别论证中的假设、谬误与漏洞",
            "类比推理：从相似问题的解法中迁移经验",
            "事实核查：用外部工具验证关键事实",
            "逆向求解：从目标出发倒推需要的条件",
            "归纳总结：从具体案例中归纳一般规律",
            "验证与反思：对中间结果进行检验并自我修正");

    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)\\{.*\\}");

    private final LlmClient llm;

    public SelfDiscoverAgent(LlmClient llm) {
        this.llm = llm;
    }

    public String run(String task) {
        // ---- Stage 1 SELECT：挑选相关推理模块 ----
        String modules = String.join("\n", REASONING_MODULES);
        String selected = llm.complete("""
                任务：%s

                下面是一个原子推理模块库：
                %s

                请从中挑选出最适合解决该任务的 2~3 个模块，每行输出一个模块名称（冒号前的部分）。"""
                .formatted(task, modules));
        log("\n[Selector 选中的推理模块]\n" + selected);

        // ---- Stage 2 ADAPT：组装为结构化的推理计划 ----
        String adapted = llm.complete("""
                任务：%s
                已选推理模块：
                %s

                请把选中的模块编排成一份针对该任务的结构化推理计划，输出 JSON：
                {"structure": "推理结构名称", "steps": [{"step": "步骤说明", "module": "使用的模块"}]}
                只输出 JSON。"""
                .formatted(task, selected));
        log("\n[Adaptor 生成的推理结构]\n" + adapted);

        List<String> steps = parseSteps(adapted);
        if (steps.isEmpty()) {
            steps = List.of(adapted); // 容错：整体作为单步执行
        }

        // ---- Stage 3 IMPLEMENT：按结构逐步执行 ----
        List<String> stepResults = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String prior = stepResults.isEmpty() ? "（无）"
                    : String.join("\n", stepResults);
            String result = llm.complete("""
                    请只执行推理计划中的第 %d 步，必要时调用工具（格式 Action: 工具名[参数]）。
                    基于之前的步骤结果作答。

                    任务：%s
                    当前步骤：%s
                    之前步骤结果：
                    %s
                    本步结果：""".formatted(i + 1, task, steps.get(i), prior));
            log("\n[Implementor 第 " + (i + 1) + "步] " + result.strip());
            stepResults.add(steps.get(i) + " => " + result.strip());
        }

        String answer = llm.complete("""
                任务：%s
                已按推理结构完成全部步骤：
                %s
                请汇总给出最终答案：""".formatted(task, String.join("\n", stepResults)));
        log("\n[最终答案] " + answer.strip());
        return answer.strip();
    }

    static List<String> parseSteps(String adaptedJson) {
        List<String> steps = new ArrayList<>();
        Matcher block = JSON_BLOCK.matcher(adaptedJson);
        if (!block.find()) {
            return steps;
        }
        String json = block.group();
        // 极简提取："step": "..." 的值
        Matcher stepMatcher = Pattern.compile("\"step\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (stepMatcher.find()) {
            steps.add(stepMatcher.group(1));
        }
        return steps;
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
