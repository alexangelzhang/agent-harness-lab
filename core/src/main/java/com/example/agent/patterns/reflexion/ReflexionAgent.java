package com.example.agent.patterns.reflexion;

import com.example.agent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 模式 6：Reflexion（语言强化学习）
 *
 * <p>论文《Reflexion: Language Agents with Verbal Reinforcement Learning》。
 * 相比 Basic Reflection 的「师生互评」，Reflexion 引入了三组件闭环：</p>
 * <ul>
 *   <li>Actor：回答前先做批判式思考（critique），再给出答案；</li>
 *   <li>Evaluator：用外部依据（环境反馈/规则/评分函数）判断回答是否通过，
 *       而不仅仅依赖另一个 LLM 的主观意见；</li>
 *   <li>Self-Reflection：失败后生成文字形式的反思，存入情景记忆（episodic memory），
 *       下一轮 Actor 带着历史反思重试 —— 用语言反馈代替梯度更新，即「口头强化学习」。</li>
 * </ul>
 */
public class ReflexionAgent {

    private final LlmClient llm;
    private final Predicate<String> environmentCheck;
    private final String checkRubric;
    private final int maxTrials;

    /**
     * @param environmentCheck 环境评估函数：对 Actor 的最终答案做程序化校验（外部依据）
     * @param checkRubric      评估标准的自然语言描述，注入自我反思提示词，指导改进方向
     */
    public ReflexionAgent(LlmClient llm, Predicate<String> environmentCheck,
                          String checkRubric, int maxTrials) {
        this.llm = llm;
        this.environmentCheck = environmentCheck;
        this.checkRubric = checkRubric;
        this.maxTrials = maxTrials;
    }

    public String run(String question) {
        List<String> reflections = new ArrayList<>();
        String lastAnswer = "";

        for (int trial = 1; trial <= maxTrials; trial++) {
            // ---- Actor：带历史反思作答 ----
            StringBuilder memory = new StringBuilder();
            for (int i = 0; i < reflections.size(); i++) {
                memory.append("反思 ").append(i + 1).append(": ").append(reflections.get(i)).append('\n');
            }
            String actorOutput = llm.complete("""
                    请回答下面的问题。回答前先进行自我批评式思考：
                    有没有遗漏（missing）或多余（superfluous）的方面？然后给出答案。

                    %s
                    问题：%s
                    回答：""".formatted(memory.isEmpty() ? "" : "之前的尝试与反思：\n" + memory, question));
            lastAnswer = actorOutput.strip();
            log("\n[Actor 第 " + trial + " 次尝试] " + lastAnswer);

            // ---- Evaluator：用外部依据评估 ----
            boolean passed = environmentCheck.test(lastAnswer);
            log("[Evaluator] " + (passed ? "PASS" : "FAIL（与预期结果不符）"));
            if (passed) {
                return lastAnswer;
            }

            // ---- Self-Reflection：生成语言反思，写入长期情景记忆 ----
            String reflection = llm.complete("""
                    上面的回答没有通过环境校验（校验标准：%s）。
                    请分析失败原因，并用一段话给出建设性的改进建议（语言反思），
                    这段反思会被存入记忆，指导下一次尝试。

                    问题：%s
                    失败的回答：%s
                    反思：""".formatted(checkRubric, question, lastAnswer));
            reflections.add(reflection.strip());
            log("[Self-Reflection] " + reflection.strip());
        }
        throw new IllegalStateException("尝试 " + maxTrials + " 次仍未通过环境校验，最后一版回答: " + lastAnswer);
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
