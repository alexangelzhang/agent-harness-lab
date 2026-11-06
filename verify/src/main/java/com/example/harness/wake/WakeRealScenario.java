package com.example.harness.wake;

import java.util.ArrayList;
import java.util.List;

/**
 * 等待即订阅真实冒烟（{@code WakeScenario} 的真实对照组，2026-11-06）。
 *
 * <p>板子的机械（订阅、终态、预算）剧本已断言全绿；这条只验真实面：
 * <b>主 agent 收到 WakeMessage 渲染的系统通知后，终答用得上结果本体</b>——
 * 叫醒带的是答案不是「再查一次」的许可，这条对真实模型也成立才算数。
 * 机制面压缩重放 + 一次真实调用。</p>
 *
 * <p>运行：{@code mvn exec:java@wakereal -pl verify}，需 LLM_API_KEY。</p>
 */
public class WakeRealScenario {

    public static void main(String[] args) {
        com.example.harness.config.AgentConfig.load().requireApiKey();
        com.example.langgraph4j.llm.LlmClient llm = new com.example.langgraph4j.llm.JdkHttpLlm();
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("等待即订阅真实冒烟：叫醒消息真实模型接不接得住");
        System.out.println(line);

        boolean ok = true;

        // 机制面压缩重放：挂订阅 → 推进 → 终态 → 恰好一次叫醒
        TaskBoard board = new TaskBoard(3);
        String task = board.launch("打包生产镜像", 10);
        List<String> inbox = new ArrayList<>();
        board.await(task, m -> inbox.add(m.toSystemMessage()));
        for (int i = 0; i < 5; i++) {
            board.tick();
        }
        board.complete(task, "镜像 img-9f3a 已推送");
        ok &= report("机制面：订阅零轮询、叫醒恰好一次",
                board.pollCount(task) == 0 && inbox.size() == 1);
        System.out.println("── 注入主会话的系统通知：" + inbox.get(0));

        // 真实面：主 agent 带着通知产出终答
        String answer = llm.complete(
                "你是等待方主 agent。你之前把「打包生产镜像」挂成订阅去干别的了，"
                        + "现在收到这条系统通知：\n" + inbox.get(0) + "\n"
                        + "请基于通知里的结果给出最终处理方案，一两句话，"
                        + "不要假设你还需等待或另查结果。");
        System.out.println("── 主 agent 终答：" + abbreviate(answer));
        ok &= report("终答用上了叫醒结果本体（img-9f3a 在场）",
                answer.contains("img-9f3a"));

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：叫醒带结果，真实模型的终答直接消费。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static String abbreviate(String s) {
        String one = s.replace("\n", " ").strip();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
