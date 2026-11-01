package com.example.harness;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 运行报表：把 {@code runs/runs.jsonl} 这张只进不出的审计底账<b>读出来见人</b>——
 * 终态分布、按载体（runner）分组的成本与终态、护栏拦截专项、失败模式 TOP、
 * 按天趋势。选型对比和规则蒸馏的量化输入都从这儿出，不用再手写 jq。
 *
 * <p>纯离线：只读底账不调用模型、不改档案。底账为空时给出提示直接收工
 * （不是错，是还没跑过东西）。一致性断言挂退出码：各分组的计数必须对得上
 * 总账，对不上说明报表自己算错了。</p>
 *
 * <p>运行：{@code mvn exec:java@report}。</p>
 */
public class RunReportDemo {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    private static int bad;

    public static void main(String[] args) throws Exception {
        header("运行报表：审计底账的统计视图",
                "终态分布 / 按 runner 成本 / 护栏专项 / 失败模式 TOP / 按天趋势");

        List<RunRecord> all = STORE.readAll();
        if (all.isEmpty()) {
            System.out.println("\n  底账为空——先跑几个 demo（@topology / @archive / 真实分片）再来。");
            System.out.println("  （空账不是错：报表只读，不造假数据。）");
            System.exit(0);
        }

        section1Overview(all);
        section2ByRunner(all);
        section3Guard(all);
        section4Failures(all);
        section5Daily(all);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：底账不再只进不出——哪种载体烧多少、护栏拦在哪、"
                    + "失败模式重不重复，一屏看清。");
        } else {
            System.out.println("!! 有 " + bad + " 处一致性校验未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 第一块：总览 + 终态分布 -------------------------------------------------

    private static void section1Overview(List<RunRecord> all) {
        System.out.println("\n── 总览 ──");
        LocalDate from = all.stream().map(RunReportDemo::dayOf).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = all.stream().map(RunReportDemo::dayOf).max(LocalDate::compareTo).orElseThrow();
        System.out.println("  共 " + all.size() + " 条，跨度 " + from + " → " + to);

        Map<String, Long> byTerminal = all.stream()
                .collect(Collectors.groupingBy(RunRecord::terminal, TreeMap::new, Collectors.counting()));
        long sum = byTerminal.values().stream().mapToLong(Long::longValue).sum();
        check("终态分布合计 = 总条数", sum == all.size());
        byTerminal.forEach((terminal, count) ->
                System.out.println("  " + padRight(terminal, 18) + bar(count, all.size())
                        + " " + count + "（" + count * 100 / all.size() + "%）"));
    }

    // ---- 第二块：按 runner 分组 --------------------------------------------------

    private static void section2ByRunner(List<RunRecord> all) {
        System.out.println("\n── 按载体（runner）分组：次数 / 终态 / 平均成本 ──");
        System.out.println("  " + padRight("runner", 24) + padRight("次数", 6)
                + padRight("平均调用", 10) + padRight("平均步数", 10)
                + padRight("平均耗时ms", 12) + "终态分布");
        System.out.println("  " + "-".repeat(86));
        Map<String, List<RunRecord>> byRunner = all.stream()
                .collect(Collectors.groupingBy(RunRecord::runner, TreeMap::new, Collectors.toList()));
        long sum = byRunner.values().stream().mapToLong(List::size).sum();
        check("按 runner 合计 = 总条数", sum == all.size());
        byRunner.forEach((runner, runs) -> {
            double avgCalls = runs.stream().mapToInt(RunRecord::llmCalls).average().orElse(0);
            double avgSteps = runs.stream().mapToLong(r -> r.steps().size()).average().orElse(0);
            double avgMs = runs.stream().mapToLong(RunRecord::durationMs).average().orElse(0);
            String terminals = runs.stream()
                    .collect(Collectors.groupingBy(RunRecord::terminal, TreeMap::new, Collectors.counting()))
                    .entrySet().stream().map(e -> e.getKey() + "×" + e.getValue())
                    .collect(Collectors.joining(", "));
            System.out.println("  " + padRight(runner, 24) + padRight(String.valueOf(runs.size()), 6)
                    + padRight(String.format("%.1f", avgCalls), 10)
                    + padRight(String.format("%.1f", avgSteps), 10)
                    + padRight(String.format("%.0f", avgMs), 12) + terminals);
        });
    }

    // ---- 第三块：护栏专项 ---------------------------------------------------------

    /**
     * 护栏拦截专项：多 Agent 运行里护栏步骤的 reason 记的是「护栏」（RunJournal
     * 把 trace 的 who 收进 reason），output 形如「cycle → 说人话的理由」——
     * 取「→」前的规则名聚类，就是「护栏都拦在哪」的答案。
     */
    private static void section3Guard(List<RunRecord> all) {
        System.out.println("\n── 护栏专项：拦了多少次、都拦在哪 ──");
        List<RunRecord> blocked = all.stream()
                .filter(r -> "GUARD_BLOCKED".equals(r.terminal())).toList();
        System.out.println("  GUARD_BLOCKED 终态 " + blocked.size() + " 条（占 "
                + blocked.size() * 100 / all.size() + "%）——拦截是正常终态，不是事故。");

        Map<String, Long> byRule = all.stream()
                .flatMap(r -> r.steps().stream())
                .filter(s -> "护栏".equals(s.reason()))
                .map(s -> {
                    String out = s.output();
                    int arrow = out.indexOf('→');
                    return arrow < 0 ? out : out.substring(0, arrow).trim();
                })
                .collect(Collectors.groupingBy(rule -> rule, TreeMap::new, Collectors.counting()));
        long guardSteps = byRule.values().stream().mapToLong(Long::longValue).sum();
        System.out.println("  护栏拦截步共 " + guardSteps + " 次，按规则分：");
        byRule.forEach((rule, count) ->
                System.out.println("  " + padRight(rule, 16) + bar(count, guardSteps)
                        + " " + count + " 次"));
        check("护栏规则聚类合计 = 护栏步数", byRule.values().stream().mapToLong(Long::longValue).sum() == guardSteps);
    }

    // ---- 第四块：失败模式 TOP（蒸馏输入） -------------------------------------------

    /** 这些终态都是「正常跑完」的各方言，只有其余才算失败（与蒸馏器同一张表）。 */
    private static final java.util.Set<String> GOOD_TERMINALS = RuleDistiller.GOOD_TERMINALS;

    private static void section4Failures(List<RunRecord> all) {
        System.out.println("\n── 失败模式 TOP（非正常终态的 runner×终态，规则蒸馏的输入）──");
        Map<String, Long> failures = all.stream()
                .filter(r -> !GOOD_TERMINALS.contains(r.terminal()))
                .collect(Collectors.groupingBy(
                        r -> r.runner() + " / " + r.terminal(),
                        TreeMap::new, Collectors.counting()));
        if (failures.isEmpty()) {
            System.out.println("  （没有失败记录——要么真顺，要么跑得还不够多。）");
            return;
        }
        failures.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .forEach(e -> System.out.println("  " + padRight(e.getKey(), 44) + e.getValue() + " 次"));
    }

    // ---- 第五块：按天趋势 ---------------------------------------------------------

    private static void section5Daily(List<RunRecord> all) {
        System.out.println("\n── 按天落账 ──");
        Map<LocalDate, Long> byDay = all.stream()
                .collect(Collectors.groupingBy(RunReportDemo::dayOf, TreeMap::new, Collectors.counting()));
        byDay.forEach((day, count) ->
                System.out.println("  " + day + "  " + bar(count, byDay.values().stream().mapToLong(Long::longValue).max().orElse(1))
                        + " " + count + " 条"));
    }

    // ---- 小工具 -------------------------------------------------------------------

    private static LocalDate dayOf(RunRecord r) {
        return Instant.ofEpochMilli(r.timestamp()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** 百分比条：每 10% 一格。 */
    private static String bar(long count, long total) {
        int cells = total == 0 ? 0 : (int) Math.min(20, count * 20 / Math.max(1, total));
        return "▇".repeat(cells) + "·".repeat(20 - cells);
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
