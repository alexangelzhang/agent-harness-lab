package com.example.harness.multiagent.team;

import java.util.List;
import java.util.Optional;

/**
 * Team Mode 真实冒烟（{@code TeamScenario} 的真实对照组，2026-11-06）。
 *
 * <p>黑板机制剧本已断言全绿；这条把真实模型放进队员位：builder 收到
 * 领班的站内信（共享信箱里 poll 出来的真实消息），按格式要求回一封
 * 完成报告。真实面要回答的问题：队员按黑板协议回话，格式与内容
 * 能不能直接被黑板另一侧消费——横向通信不是各说各话。</p>
 *
 * <p>一次真实调用。运行：{@code mvn exec:java@teamreal -pl verify}，需 LLM_API_KEY。</p>
 */
public class TeamRealScenario {

    public static void main(String[] args) {
        com.example.harness.config.AgentConfig.load().requireApiKey();
        com.example.langgraph4j.llm.LlmClient llm = new com.example.langgraph4j.llm.JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Team Mode 真实冒烟：真模型坐队员位，黑板协议通不通");
        System.out.println(line);

        boolean ok = true;
        Team team = new Team("结算重构", List.of("planner", "builder"),
                4, 4096, 16, n -> Optional.empty());

        // 领班派活：任务上板 + 站内信说清要求
        long task = team.tasks().create("写结算重构完成报告");
        team.mailbox().send("planner", "builder",
                "请认领并完成 task-" + task + "：给「结算重构」写完成报告，"
                        + "回信严格两行格式——摘要：一句话；风险：一句话。");
        team.tasks().claim(task, "builder");
        System.out.println("── 任务上板并认领：" + team.tasks().list());

        // 队员 poll 到信，真模型按格式回信
        List<TeamMailbox.Message> inbox = team.mailbox().poll("builder");
        String letter = llm.complete(
                "你是队员 builder，在 Team Mode 的共享信箱里收到队友 planner 的站内信：\n"
                        + inbox.get(0).body() + "\n"
                        + "请直接回信（不要寒暄、不要复述这封信），严格按两行格式：\n"
                        + "摘要：……\n风险：……");
        System.out.println("── builder 回信：" + abbreviate(letter));

        ok &= report("回信含摘要与风险两行（结构断言）",
                letter.contains("摘要") && letter.contains("风险"));
        ok &= report("没把信原文复述回来（黑板不是复读机）",
                !letter.contains("请认领并完成"));

        // 回信进黑板、任务勾掉、一页状态对账
        team.mailbox().send("builder", "planner", letter);
        ok &= report("认领者完成任务", team.tasks().complete(task, "builder"));
        Team.Status status = team.status();
        System.out.println("── team_status：任务挂/领/完 = " + status.pending() + "/"
                + status.claimed() + "/" + status.done()
                + "，信箱 " + status.mailboxTotal() + " 封");
        ok &= report("一页状态对得上（1 完 2 信）",
                status.done() == 1 && status.mailboxTotal() == 2);

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：真模型按黑板协议回话，信箱两侧接得上、任务闭环。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
