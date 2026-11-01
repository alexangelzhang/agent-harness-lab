package com.example.agent.patterns.basicreflection;

import com.example.agent.llm.LlmClient;

/**
 * 模式 5：Basic Reflection（基础反思）
 *
 * <p>类比师生关系：Generator（学生）产出初稿，Reflector（老师）批改并给出建议，
 * Generator 根据建议修改，如此往复直到 Reflector 认为可以定稿。
 * 提示词就是复刻师生之间的交互。结构上是「Generator + Reflector」两个角色，
 * 通常由同一个模型配合不同系统提示词扮演。</p>
 */
public class BasicReflectionAgent {

    private final LlmClient llm;
    private final int maxRounds;

    public BasicReflectionAgent(LlmClient llm, int maxRounds) {
        this.llm = llm;
        this.maxRounds = maxRounds;
    }

    public String run(String task) {
        String draft = generate(task, "");
        for (int round = 1; round <= maxRounds; round++) {
            String critique = critique(task, draft);
            log("\n[Reflector 第 " + round + " 轮批语]\n" + critique);

            if (critique.contains("ACCEPT")) {
                log("\n[Reflector] 认为可以定稿");
                return draft;
            }
            log("\n[Generator 根据批语修改]");
            draft = generate(task, critique);
        }
        log("\n[达到最大轮数，返回最后一版]");
        return draft;
    }

    /** Generator：产出（或根据批语修改）内容。 */
    private String generate(String task, String critique) {
        String prompt = critique.isBlank()
                ? """
                请完成下面的创作任务，直接输出作品内容。

                任务：%s
                作品：""".formatted(task)
                : """
                请根据老师的批改意见修改你的作品，直接输出修改后的完整作品。

                任务：%s
                原作品：%s
                批改意见：%s
                修改后的作品：""".formatted(task, lastDraft.get(), critique);
        if (critique.isBlank()) {
            lastDraft.set("");
        }
        String output = llm.complete(prompt);
        lastDraft.set(output.strip());
        log("[Generator 产出]\n" + output);
        return output.strip();
    }

    /** Reflector：批改并给出建议；满意时在批语末尾输出 ACCEPT。 */
    private String critique(String task, String draft) {
        return llm.complete("""
                你是一位严格的评审。请对下面的作品进行批改，指出存在的问题并给出具体修改建议。
                如果作品已经足够好，请输出「ACCEPT」表示通过。

                任务要求：%s
                作品：%s
                批语：""".formatted(task, draft));
    }

    // generate() 内部缓存最新稿件，供下一轮修改提示词引用
    private final ThreadLocal<String> lastDraft = ThreadLocal.withInitial(() -> "");

    private static void log(String message) {
        System.out.println(message);
    }
}
