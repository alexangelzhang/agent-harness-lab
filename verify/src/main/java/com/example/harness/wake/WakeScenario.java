package com.example.harness.wake;

import java.util.ArrayList;
import java.util.List;

/**
 * 等待即订阅 vs 轮询烧钱 的对照剧本（OmO 研读落地，2026-11-05）。
 *
 * <p>三幕：</p>
 * <ul>
 *   <li><b>轮询组</b>：agent 等一个要 5 格才好的构建，每格 poll 一次——
 *       烧 5 次查询，其中超上限的 2 次连真实状态都拿不到；</li>
 *   <li><b>订阅组</b>：同一个构建，agent 挂上订阅就收工，终态由任务板
 *       推进收件箱——零轮询，叫醒恰好一次，重复上报不重复叫醒；</li>
 *   <li><b>异常与兜底</b>：失败也叫醒（带错误）、超时兜底叫醒、
 *       终态后挂的订阅立即补投。</li>
 * </ul>
 *
 * <p>运行：{@code mvn exec:java@wake -pl verify}（离线剧本，无需 key）。</p>
 */
public class WakeScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("等待即订阅：等构建/部署是挂订阅，不是轮询");
        System.out.println(line);

        boolean ok = true;

        // ── 第一幕：轮询组——每格查一次，烧轮次还碰护栏
        System.out.println("── 第一幕：轮询组");
        TaskBoard boardA = new TaskBoard(3);
        String buildA = boardA.launch("打包生产镜像", 10);
        int buildTicks = 5;
        String lastSeen = "";
        for (int t = 1; t <= buildTicks; t++) {
            lastSeen = boardA.poll(buildA); // agent 每格烧一次查询
            boardA.tick();
            if (t == buildTicks) {
                boardA.complete(buildA, "镜像 img-9f3a 已推送");
            }
        }
        System.out.println("  构建耗时 " + buildTicks + " 格，agent 轮询了 "
                + boardA.pollCount(buildA) + " 次，其中被护栏拦下 "
                + boardA.pollViolations(buildA) + " 次");
        System.out.println("  第 5 次查询拿到的是：" + abbreviate(lastSeen));
        ok &= report("轮询组烧了 " + buildTicks + " 次查询（每次都问一次）",
                boardA.pollCount(buildA) == buildTicks);
        ok &= report("超上限的轮询被护栏拦下（拿不到真实状态）",
                boardA.pollViolations(buildA) == buildTicks - 3
                        && lastSeen.contains("挂订阅"));
        System.out.println();

        // ── 第二幕：订阅组——挂上就收工，终态推进收件箱
        System.out.println("── 第二幕：订阅组");
        TaskBoard boardB = new TaskBoard(3);
        String buildB = boardB.launch("打包生产镜像", 10);
        List<String> inbox = new ArrayList<>(); // 等待方会话的系统消息收件箱
        boardB.await(buildB, msg -> inbox.add(msg.toSystemMessage()));
        System.out.println("  agent 挂完订阅就收工，轮询次数：" + boardB.pollCount(buildB));
        for (int t = 1; t <= buildTicks; t++) {
            boardB.tick(); // 等待期间 agent 一格都不在场
        }
        boardB.complete(buildB, "镜像 img-9f3a 已推送");
        boardB.complete(buildB, "重复上报"); // 基础设施手抖重复上报
        boardB.fail(buildB, "又报失败"); // 终态先到先定
        System.out.println("  收件箱收到 " + inbox.size() + " 条：" + inbox.get(0));
        ok &= report("订阅组全程零轮询", boardB.pollCount(buildB) == 0);
        ok &= report("叫醒恰好一次（重复上报/反悔都被吞）", inbox.size() == 1
                && boardB.wakeCount(buildB) == 1);
        ok &= report("叫醒消息带结果本体（不用再查）",
                inbox.get(0).contains("img-9f3a"));
        System.out.println();

        // ── 第三幕：异常与兜底
        System.out.println("── 第三幕：异常与兜底");
        TaskBoard boardC = new TaskBoard(3);
        List<WakeMessage> wakes = new ArrayList<>();

        String deploy = boardC.launch("灰度部署", 10);
        boardC.await(deploy, wakes::add);
        boardC.fail(deploy, "健康检查 3/10 通过，已回滚");
        ok &= report("失败也叫醒（带错误，不干等）", wakes.size() == 1
                && wakes.get(0).reason() == WakeMessage.Reason.FAILED
                && wakes.get(0).detail().contains("回滚"));

        String stress = boardC.launch("全链路压测", 2);
        boardC.await(stress, wakes::add);
        boardC.tick();
        boardC.tick();
        boardC.tick(); // 超过期限 1 格
        ok &= report("超时兜底叫醒（不许干等到天荒地老）", wakes.size() == 2
                && wakes.get(1).reason() == WakeMessage.Reason.TIMEOUT);

        List<WakeMessage> lateWakes = new ArrayList<>();
        boardC.await(deploy, lateWakes::add); // 终态后才挂的订阅
        ok &= report("终态后挂订阅立即补投（晚到不等下一场）", lateWakes.size() == 1
                && lateWakes.get(0).reason() == WakeMessage.Reason.FAILED);

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "全绿：轮询烧 5 次查询还碰护栏，订阅零轮询一次叫醒拿到结果。"
                : "!! 有断言未过");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    private static String abbreviate(String s) {
        return s.length() <= 48 ? s : s.substring(0, 48) + "…";
    }

    private static boolean report(String label, boolean ok) {
        System.out.println((ok ? "  ✓ " : "  !! ") + label);
        return ok;
    }
}
