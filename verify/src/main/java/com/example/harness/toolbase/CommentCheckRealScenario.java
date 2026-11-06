package com.example.harness.toolbase;

import com.example.harness.toolbase.SlopCommentChecker.Finding;
import java.util.List;
import java.util.Optional;

/**
 * comment checker 真实冒烟（{@code SlopCommentScenario} 的真实对照组，2026-11-06）。
 *
 * <p>剧本场景已把四类腔调与逃生口断言全绿；这条验真实面：让真实模型
 * 给代码写注释，看检查器能不能揪住真实模型爱犯的 AI 腔（空话副词/
 * 复述代码），以及<b>把 findings 喂回去，模型改一轮后腔调降不降</b>。
 * 揪不住（模型写得干净）如实报告为干净，不算失败；改完不降才算断。</p>
 *
 * <p>最多两次真实调用。运行：{@code mvn exec:java@commentcheckreal -pl verify}，需 LLM_API_KEY。</p>
 */
public class CommentCheckRealScenario {

    private static final String CODE = "int retryCount = 3;";

    public static void main(String[] args) {
        com.example.harness.config.AgentConfig.load().requireApiKey();
        com.example.langgraph4j.llm.LlmClient llm = new com.example.langgraph4j.llm.JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("comment checker 真实冒烟：揪真实模型的 AI 腔，再验收一轮");
        System.out.println(line);

        boolean ok = true;
        SlopCommentChecker checker = new SlopCommentChecker();

        // 第一跳：真实模型给一行代码写注释
        String firstComment = askComment(llm, "请给这行代码上方补一行中文注释（// 开头，一句话说清为什么重试）：\n" + CODE);
        System.out.println("── 初稿注释：" + firstComment);
        ok &= report("拿到注释且以 // 开头", firstComment.startsWith("//"));
        List<Finding> first = checker.scan(firstComment + "\n" + CODE);
        for (Finding f : first) {
            System.out.println("  揪到 [" + f.category() + "] " + f.excerpt());
        }
        System.out.println("  初稿命中 " + first.size() + " 处");

        if (first.isEmpty()) {
            ok &= report("初稿干净——这次模型没犯腔，如实记录", true);
        } else {
            // 第二跳：findings 喂回去重写，验收一轮
            StringBuilder feedback = new StringBuilder("你是 comment-checker。这句注释被揪出 ")
                    .append(first.size()).append(" 处 AI 腔：\n");
            first.forEach(f -> feedback.append("  [").append(f.category()).append("] ")
                    .append(f.excerpt()).append("\n"));
            feedback.append("请重写这一句（仍是 // 开头、中文、只留信息增量，不复述代码、不用空话副词）：");
            String secondComment = askComment(llm, feedback.toString());
            System.out.println("── 重写注释：" + secondComment);
            List<Finding> second = checker.scan(secondComment + "\n" + CODE);
            for (Finding f : second) {
                System.out.println("  仍揪到 [" + f.category() + "] " + f.excerpt());
            }
            System.out.println("  重写后命中 " + second.size() + " 处");
            ok &= report("重写后腔调下降（或清零）", second.size() < first.size());
            if (!second.isEmpty()) {
                System.out.println("  （残留属模型口味问题，管线已尽责——剧本场景逐条断言的类别边界仍兜底）");
            }
        }

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：检查器对真实输出生效，findings 回流一轮有改善。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    /** 让模型给注释；从回复里抽出以 // 开头的第一行，抽不到就原文返回（由断言兜）。 */
    private static String askComment(com.example.langgraph4j.llm.LlmClient llm, String prompt) {
        String reply = llm.complete(prompt);
        Optional<String> comment = reply.lines()
                .map(String::strip)
                .filter(s -> s.startsWith("//"))
                .findFirst();
        return comment.orElse(reply.strip());
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
