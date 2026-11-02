package com.example.langgraph4j.eval;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.SupervisorPattern;
import com.example.multiagent.SupervisorPattern.Expert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 折叠对照评估：Anthropic「一次只合并一个 Subagent」的评估驱动落地——
 * 同一组 Golden Examples，同一任务跑<b>架构 A（Subagent 分派版）</b>和
 * <b>架构 B（折回主 Loop：Skill 注入 + 工具表）</b>各 N 遍，对照通过率、
 * 调用成本，并把「迁移后从过变挂」的题<b>逐题显形</b>。
 *
 * <p>剧本复刻了原文的客服失败案例：任务 1 需要订单 + 配送两个事实，
 * B 版第 1 遍查到订单就自己答了（漏调配送工具、丢了交付日期），第 2 遍
 * 才两步都走——所以 B 版任务 1 是 1/2 过。结论因此不是「B 更好」，
 * 而是原文的原话：不能只看平均性能和成本，合并前评估集必须覆盖这条
 * 调用链，这道题要么保留 Subagent、要么把「必须查配送」写进 Skill。</p>
 *
 * <p>全部 ScriptedLlm 离线可跑；硬断言：A 版任务 1 全过、B 版任务 1
 * 恰好检出「从过变挂」、B 版简单任务不被误伤、B 平均调用严格少于 A。</p>
 *
 * <p>运行：{@code mvn exec:java@foldeval}。</p>
 */
public class FoldEvalScenario {

    /** Golden Example：任务 + 答案必须覆盖的检查点（端到端，不是局部指标）。 */
    private record Golden(String task, List<String> required) {
        boolean pass(String answer) {
            return required.stream().allMatch(answer::contains);
        }
    }

    /** 折叠版注入的 Skill：订单处理规范（迁移时从 Subagent 提示词整理而来）。 */
    private static final String ORDER_SKILL = """
            【订单处理规范】你是客服主 Agent，直接处理订单相关问题。
            规范：查订单用 query_order；用户问的是「什么时候到」时，
            必须再查 query_shipping 拿到预计送达时间，两者一起答复。""";

    private static final Pattern ACTION = Pattern.compile("(?s)Action:\\s*([a-z_]+)\\s*\\[(.*)\\]");
    private static final int RUNS = 2;

    private static int bad;

    public static void main(String[] args) {
        header("折叠对照评估：Subagent 版 vs 折回主 Loop 版",
                "同一组 Golden Examples 各跑 " + RUNS + " 遍——通过率 / 成本 / 从过变挂逐题显形");

        List<Golden> goldens = List.of(
                new Golden("我的订单 1001 买的什么，什么时候到？",
                        List.of("咖啡机", "周四送达")),
                new Golden("退款一般几天到账？",
                        List.of("3-5 个工作日")),
                new Golden("你们能开发票吗？",
                        List.of("电子发票")));

        Map<String, List<Boolean>> passA = new LinkedHashMap<>();
        Map<String, List<Boolean>> passB = new LinkedHashMap<>();
        Map<String, Integer> avgCallsA = new LinkedHashMap<>();
        Map<String, Integer> avgCallsB = new LinkedHashMap<>();

        for (Golden g : goldens) {
            List<Boolean> pa = new ArrayList<>();
            List<Boolean> pb = new ArrayList<>();
            int sumA = 0;
            int sumB = 0;
            for (int run = 1; run <= RUNS; run++) {
                List<CountingLlm> countersA = new ArrayList<>();
                String ansA = runSubagent(g, run, countersA);
                pa.add(g.pass(ansA));
                sumA += countersA.stream().mapToInt(CountingLlm::calls).sum();

                List<CountingLlm> countersB = new ArrayList<>();
                String ansB = runFolded(g, run, countersB);
                pb.add(g.pass(ansB));
                sumB += countersB.stream().mapToInt(CountingLlm::calls).sum();
            }
            passA.put(g.task(), pa);
            passB.put(g.task(), pb);
            avgCallsA.put(g.task(), sumA / RUNS);
            avgCallsB.put(g.task(), sumB / RUNS);
        }

        printComparison(goldens, passA, passB, avgCallsA, avgCallsB);
        assertAndConclude(goldens, passA, passB, avgCallsA, avgCallsB);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：折叠更便宜，但有一道题的调用链被合并改写了——"
                    + "先测量，一次只改一个，保留经验证有效的方案。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 架构 A：Subagent 分派版 -------------------------------------------------

    /** A 版每次调用都新造一套剧本（ScriptedLlm 队列弹尽即抛），所有大脑包 CountingLlm。 */
    private static String runSubagent(Golden g, int run, List<CountingLlm> counters) {
        ScriptedLlm supervisorScript = new ScriptedLlm();
        List<Expert> roster = new ArrayList<>();
        ScriptedLlm synthScript = new ScriptedLlm();
        if (g.task().contains("1001")) {
            supervisorScript.says("""
                    分派：订单专员 | 查订单 1001 买了什么
                    分派：配送专员 | 查订单 1001 的预计送达时间""");
            roster.add(new Expert("订单专员", "查订单内容",
                    counter(new ScriptedLlm().says("订单 1001：咖啡机 ×1，已发货。"), counters)));
            roster.add(new Expert("配送专员", "查配送时效",
                    counter(new ScriptedLlm().says("订单 1001 预计周四送达。"), counters)));
            synthScript.says("您的订单 1001 是一台咖啡机，预计周四送达。");
        } else if (g.task().contains("退款")) {
            supervisorScript.says("分派：售后专员 | 退款到账时效");
            roster.add(new Expert("售后专员", "退款政策",
                    counter(new ScriptedLlm().says("退款在 3-5 个工作日原路退回支付账户。"), counters)));
            synthScript.says("退款一般 3-5 个工作日到账。");
        } else {
            supervisorScript.says("分派：财务专员 | 发票政策");
            roster.add(new Expert("财务专员", "发票政策",
                    counter(new ScriptedLlm().says("支持开具电子发票，下单后在订单页申请。"), counters)));
            synthScript.says("支持电子发票，可在订单页申请。");
        }
        return SupervisorPattern.dispatch(g.task(), roster,
                counter(supervisorScript, counters), counter(synthScript, counters),
                (who, what) -> System.out.println("    [A/" + run + "][" + who + "] " + what));
    }

    // ---- 架构 B：折回主 Loop（Skill + 工具表） -------------------------------------

    /**
     * B 版：单循环——Skill 文本常驻系统提示词，模型每轮吐 Action，
     * 工具执行结果回同一上下文。任务 1 第 1 遍复刻原文失败案例：
     * 查到订单就直接收口，漏调 query_shipping。
     */
    private static String runFolded(Golden g, int run, List<CountingLlm> counters) {
        ScriptedLlm script = new ScriptedLlm();
        if (g.task().contains("1001")) {
            if (run == 1) {
                script.says("Action: query_order[1001]")
                        .says("Action: Finish[您的订单 1001 是一台咖啡机，已从仓库发出。]");
            } else {
                script.says("Action: query_order[1001]")
                        .says("Action: query_shipping[1001]")
                        .says("Action: Finish[订单 1001 是咖啡机，预计周四送达。]");
            }
        } else if (g.task().contains("退款")) {
            script.says("Action: Finish[退款一般 3-5 个工作日原路退回到支付账户。]");
        } else {
            script.says("Action: Finish[支持开具电子发票，下单后在订单页申请即可。]");
        }

        Map<String, Function<String, String>> tools = Map.of(
                "query_order", q -> "订单" + q + "：咖啡机 ×1，已发货。",
                "query_shipping", q -> "订单" + q + "预计周四送达。");
        LlmClient brain = counter(script, counters);

        StringBuilder context = new StringBuilder(ORDER_SKILL);
        for (int round = 1; round <= 4; round++) {
            String out = brain.complete(context + "\n\n用户问题：" + g.task());
            System.out.println("    [B/" + run + "] " + out.strip());
            Matcher matcher = ACTION.matcher(out);
            if (!matcher.find()) {
                return out.strip(); // 没有 Action 行就当终答（与 ArticleScenario 兜底同口径）
            }
            Function<String, String> tool = tools.get(matcher.group(1));
            if (tool == null) {
                return matcher.group(2).trim();
            }
            String observation = tool.apply(matcher.group(2).trim());
            System.out.println("    [B/" + run + "][工具] " + matcher.group(1) + " → " + observation);
            context.append("\nObservation: ").append(observation);
        }
        return "(4 轮未收口)";
    }

    private static CountingLlm counter(ScriptedLlm script, List<CountingLlm> counters) {
        CountingLlm c = new CountingLlm(script);
        counters.add(c);
        return c;
    }

    // ---- 对照表与结论 ---------------------------------------------------------------

    private static void printComparison(List<Golden> goldens, Map<String, List<Boolean>> passA,
                                        Map<String, List<Boolean>> passB, Map<String, Integer> avgCallsA,
                                        Map<String, Integer> avgCallsB) {
        System.out.println("\n── A/B 对照（每题跑 " + RUNS + " 遍）──");
        System.out.println("  " + padRight("题目", 26) + padRight("A 通过率", 10)
                + padRight("A 平均调用", 12) + padRight("B 通过率", 10)
                + padRight("B 平均调用", 12) + "判定");
        System.out.println("  " + "-".repeat(84));
        for (Golden g : goldens) {
            List<Boolean> pa = passA.get(g.task());
            List<Boolean> pb = passB.get(g.task());
            boolean regressed = pa.stream().allMatch(b -> b) && pb.contains(false);
            String verdict = regressed ? "⚠ 从过变挂（原文客服案例同款）"
                    : pb.stream().allMatch(b -> b) ? "✓ 平移无损" : "!! B 版不稳定";
            System.out.println("  " + padRight(shorten(g.task()), 26)
                    + padRight(rate(pa), 10) + padRight(String.valueOf(avgCallsA.get(g.task())), 12)
                    + padRight(rate(pb), 10) + padRight(String.valueOf(avgCallsB.get(g.task())), 12)
                    + verdict);
        }
    }

    private static void assertAndConclude(List<Golden> goldens, Map<String, List<Boolean>> passA,
                                          Map<String, List<Boolean>> passB, Map<String, Integer> avgCallsA,
                                          Map<String, Integer> avgCallsB) {
        Golden orderQ = goldens.get(0);
        check("A 版（Subagent）订单题 " + RUNS + " 遍全过",
                passA.get(orderQ.task()).stream().allMatch(b -> b));
        List<Boolean> pb = passB.get(orderQ.task());
        check("B 版（折叠）订单题检出从过变挂：" + rate(pb),
                pb.contains(true) && pb.contains(false));
        for (int i = 1; i < goldens.size(); i++) {
            Golden g = goldens.get(i);
            check("简单题不被合并误伤（" + shorten(g.task()) + " B 版全过）",
                    passB.get(g.task()).stream().allMatch(b -> b));
            check("B 版平均调用严格少于 A（" + avgCallsB.get(g.task()) + " < " + avgCallsA.get(g.task()) + "）",
                    avgCallsB.get(g.task()) < avgCallsA.get(g.task()));
        }
        check("B 版平均调用严格少于 A（订单题 " + avgCallsB.get(orderQ.task())
                        + " < " + avgCallsA.get(orderQ.task()) + "）",
                avgCallsB.get(orderQ.task()) < avgCallsA.get(orderQ.task()));

        System.out.println("\n── 结论 ──");
        System.out.println("  折叠版整体更便宜（没有主管分派和综合两次调用），但订单题");
        System.out.println("  第 1 遍丢了「周四送达」——和原文失败案例一模一样：主 Agent");
        System.out.println("  查到订单就自己答了。处置（原文建议）：这道题的调用链进评估集，");
        System.out.println("  要么保留配送 Subagent，要么把「问到达必须查配送」写进 Skill");
        System.out.println("  并补评估。不能只看平均——每个任务要跑多遍，逐题看。");
    }

    private static String rate(List<Boolean> passes) {
        long ok = passes.stream().filter(b -> b).count();
        return ok + "/" + passes.size();
    }

    private static String shorten(String task) {
        return task.length() <= 24 ? task : task.substring(0, 24) + "…";
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
