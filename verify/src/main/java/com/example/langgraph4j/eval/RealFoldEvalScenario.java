package com.example.langgraph4j.eval;

import com.example.harness.config.AgentConfig;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.RetryingLlm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 折叠对照评估 · 真实 LLM 版：剧本版 FoldEvalScenario 的同一道订单题
 * （「我的订单 1001 买的什么，什么时候到？」——需要订单 + 配送两个事实），
 * 换 JdkHttpLlm 真实跑折叠版 loop，看<b>真实模型是不是也踩原文那个坑</b>：
 * 查到订单就自己答、漏调 query_shipping、丢了送达日期。
 *
 * <p>两阶段对照（原文的两种处置）：</p>
 * <ol>
 *   <li><b>原版 Skill</b>：照抄 FoldEvalScenario 注入的订单处理规范；</li>
 *   <li><b>加固版 Skill</b>：把「问到达必须查配送」写成硬约束
 *       （不调 query_shipping 直接回答视为违规）——对应原文
 *       「要么保留 Subagent、要么把约束写进 Skill」的后半条。</li>
 * </ol>
 *
 * <p>行为结论（真实模型漏没漏调）只报告、不硬断言——那正是要观察的
 * 实验结果，拿它当门槛等于自己给自己判卷。硬断言只盯结构：每遍都在
 * 轮数内收口、工具表执行正确、调用数有账可查。</p>
 *
 * <p>运行：{@code mvn exec:java@realfoldeval [每阶段遍数，默认 3]}，
 * 需 LLM_API_KEY。</p>
 */
public class RealFoldEvalScenario {

    /** 协议说明：两个版本共用，差异只在约束句——不然测的是「模型懂不懂格式」而不是「漏不漏调」。 */
    private static final String PROTOCOL = """
            可用工具：
            - query_order[订单号]：查订单买了什么、状态
            - query_shipping[订单号]：查预计送达时间
            每轮只输出一行，两种形式：
            Action: 工具名[参数]        —— 需要查信息时
            Action: Finish[给用户的完整答复] —— 信息够了，收口答复
            收到 Observation 后再决定下一步。""";

    /** 与 FoldEvalScenario 注入的 Skill 逐字一致（剧本/真实同一口径）+ 协议说明。 */
    private static final String ORDER_SKILL_PLAIN = """
            【订单处理规范】你是客服主 Agent，直接处理订单相关问题。
            规范：查订单用 query_order；用户问的是「什么时候到」时，
            必须再查 query_shipping 拿到预计送达时间，两者一起答复。
            """ + PROTOCOL;

    /** 加固版：原文处置的后半条——把软提醒写成硬约束。 */
    private static final String ORDER_SKILL_HARD = """
            【订单处理规范】你是客服主 Agent，直接处理订单相关问题。
            规范：查订单用 query_order；用户问的是「什么时候到」时，
            必须再查 query_shipping 拿到预计送达时间，两者一起答复。
            注意：只要用户问到「什么时候到」，你必须先调用 query_shipping
            拿到日期再组织回答；不调 query_shipping 就直接回答，视为违规输出。
            """ + PROTOCOL;

    private static final String TASK = "我的订单 1001 买的什么，什么时候到？";
    private static final Pattern ACTION = Pattern.compile("(?s)Action:\\s*([a-z_]+)\\s*\\[(.*)\\]");
    private static final int MAX_ROUNDS = 4;

    private static final Map<String, Function<String, String>> TOOLS = Map.of(
            "query_order", q -> "订单" + q + "：咖啡机 ×1，已发货。",
            "query_shipping", q -> "订单" + q + "预计周四送达。");

    private static int bad;

    /** 单遍运行结果。 */
    private record RunOutcome(boolean pass, boolean shippingCalled, boolean converged, int calls) {
    }

    public static void main(String[] args) {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        String model = AgentConfig.load().model();

        header("折叠对照评估 · 真实 LLM（" + model + "）",
                "原文那道订单题真实跑一遍折叠版 loop——真实模型漏不漏调 query_shipping？");

        // 协议自检探针：实验前先确认模型在「原版 Skill」prompt 下会吐 Action，
        // 装置没搭对就 fail fast，不烧后面 2×runs 遍的真实额度
        ProtocolProbe.Result preflight = ProtocolProbe.require(new RetryingLlm(new JdkHttpLlm()),
                ORDER_SKILL_PLAIN, TOOLS.keySet());
        System.out.println("\n── 协议自检探针预检：过——" + preflight.reason());

        List<RunOutcome> plain = runPhase("阶段 1：原版 Skill（规范里「必须再查」只是软提醒）",
                ORDER_SKILL_PLAIN, runs);
        List<RunOutcome> hard = runPhase("阶段 2：加固版 Skill（「不调 query_shipping 直接回答视为违规」）",
                ORDER_SKILL_HARD, runs);

        printComparison(plain, hard);
        assertStructural(plain, hard, runs);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：真实模型在不在原文那个坑里、硬约束补不补得回来，");
            System.out.println("      上面两阶段就是答案——剧本推演得再像，也要真跑一遍才算数。");
        } else {
            System.out.println("!! 有 " + bad + " 处结构断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 单阶段：同一 Skill 文本跑 runs 遍真实 loop --------------------------------

    private static List<RunOutcome> runPhase(String title, String skill, int runs) {
        System.out.println("\n── " + title + "（" + runs + " 遍）──");
        List<RunOutcome> outcomes = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            outcomes.add(runOnce(i, skill));
        }
        return outcomes;
    }

    /** 单遍：Skill 文本进上下文，模型每轮吐 Action，工具结果回灌，4 轮封顶。 */
    private static RunOutcome runOnce(int run, String skill) {
        CountingLlm brain = new CountingLlm(new RetryingLlm(new JdkHttpLlm()));
        StringBuilder context = new StringBuilder(skill);
        boolean shippingCalled = false;
        boolean converged = false;
        String lastOutput = "";
        System.out.println("  ── 第 " + run + " 遍 ──");
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            String out = brain.complete(context + "\n\n用户问题：" + TASK);
            lastOutput = out.strip();
            System.out.println("    [round " + round + "] " + abbreviate(lastOutput));
            Matcher matcher = ACTION.matcher(out);
            if (!matcher.find()) {
                converged = true; // 没吐 Action 行就当终答（与剧本版兜底同口径）
                break;
            }
            Function<String, String> tool = TOOLS.get(matcher.group(1));
            if (tool == null) {
                converged = true; // Finish 或未知动作：参数即终答
                break;
            }
            String observation = tool.apply(matcher.group(2).trim());
            System.out.println("    [工具] " + matcher.group(1) + " → " + observation);
            if (matcher.group(1).equals("query_shipping")) {
                shippingCalled = true;
            }
            context.append("\nObservation: ").append(observation);
        }
        boolean pass = lastOutput.contains("咖啡机") && lastOutput.contains("周四");
        System.out.println("    ⇒ " + (pass ? "✓ 两个事实都在（商品 + 送达）" : "✗ 缺事实")
                + "，query_shipping " + (shippingCalled ? "调了" : "没调")
                + "，" + brain.calls() + " 次调用");
        return new RunOutcome(pass, shippingCalled, converged, brain.calls());
    }

    // ---- 对照与结构断言 -------------------------------------------------------------

    private static void printComparison(List<RunOutcome> plain, List<RunOutcome> hard) {
        System.out.println("\n── 两阶段对照 ──");
        printPhaseLine("原版 Skill", plain);
        printPhaseLine("加固 Skill", hard);

        long plainMiss = plain.stream().filter(o -> !o.pass()).count();
        long hardMiss = hard.stream().filter(o -> !o.pass()).count();
        System.out.println();
        if (plainMiss > 0) {
            System.out.println("  发现：原版 Skill 下真实模型 " + plainMiss + "/" + plain.size()
                    + " 遍漏了送达日期——和原文客服案例同一个坑。");
        } else {
            System.out.println("  发现：原版 Skill 下真实模型 " + plain.size() + "/" + plain.size()
                    + " 遍都查全了——这个模型在这条调用链上比剧本第 1 遍靠谱。");
        }
        if (hardMiss == 0 && !hard.isEmpty()) {
            System.out.println("  加固版 " + hard.size() + "/" + hard.size() + " 遍全过——"
                    + "硬约束补得回来（原文处置后半条成立）。");
        } else if (hardMiss > 0) {
            System.out.println("  加固版仍有 " + hardMiss + "/" + hard.size() + " 遍没过——"
                    + "硬约束也没兜住，这题该保留 Subagent（原文处置前半条）。");
        }
    }

    private static void printPhaseLine(String label, List<RunOutcome> outcomes) {
        long pass = outcomes.stream().filter(RunOutcome::pass).count();
        long shipping = outcomes.stream().filter(RunOutcome::shippingCalled).count();
        int calls = outcomes.stream().mapToInt(RunOutcome::calls).sum();
        System.out.println("  " + padRight(label, 12) + "通过 " + pass + "/" + outcomes.size()
                + "，调了 query_shipping " + shipping + "/" + outcomes.size()
                + "，共 " + calls + " 次调用");
    }

    /** 行为结论只报告；这里硬断言的是「实验装置本身没坏」。 */
    private static void assertStructural(List<RunOutcome> plain, List<RunOutcome> hard, int runs) {
        System.out.println("\n── 结构断言（装置没坏，行为结论上面已报告）──");
        check("两阶段各跑满 " + runs + " 遍", plain.size() == runs && hard.size() == runs);
        check("每遍都在 " + MAX_ROUNDS + " 轮内收口", plain.stream().allMatch(RunOutcome::converged)
                && hard.stream().allMatch(RunOutcome::converged));
        check("每遍至少 1 次真实调用（没白跑）",
                plain.stream().allMatch(o -> o.calls() >= 1) && hard.stream().allMatch(o -> o.calls() >= 1));
        check("通过的遍数都调过 query_shipping（账对得上）",
                plain.stream().allMatch(o -> !o.pass() || o.shippingCalled())
                        && hard.stream().allMatch(o -> !o.pass() || o.shippingCalled()));
    }

    // ---- 小工具 ---------------------------------------------------------------------

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 90) + "…";
    }

    private static String padRight(String s, int width) {
        return String.format("%-" + width + "s", s);
    }

    private static void header(String title, String subtitle) {
        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        System.out.println(title);
        System.out.println("  " + subtitle);
        System.out.println(line);
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }
}
