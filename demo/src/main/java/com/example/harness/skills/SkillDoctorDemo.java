package com.example.harness.skills;

import com.example.agent.tool.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能库体检（对标 Anthropic /skill-doctor）：定期扫描技能库的三个健康维度，
 * 把「该清理的膨胀」显形出来，而不是让技能库只进不出、越积越乱。
 *
 * <p>体检三维度（全部离线可算，不依赖真实模型）：</p>
 * <ol>
 *   <li><b>使用频率</b>：每个技能近一段时间被加载的次数（demo 用传入的
 *       loadCount 表模拟生产埋点）。0 次 = 僵尸技能；低于阈值 = 低频观察。</li>
 *   <li><b>内容重复度</b>：promptSnippet 按 3-gram Jaccard 相似度两两比对，
 *       高相似的一对是「说同一件事」的合并候选——提示词膨胀直接吃上下文窗口。</li>
 *   <li><b>触发撞车</b>：trigger 互为包含的两个技能会在加载时抢同一个请求，
 *       属于信号设计缺陷。</li>
 * </ol>
 *
 * <p>demo 技能库故意埋了三种典型病灶：一个从未加载的僵尸技能、一对内容高度
 * 重叠的重复技能、（撞车维度上）一个 trigger 过宽的旧技能。硬断言要求体检
 * 能把它们全部揪出来，同时不冤枉健康技能。</p>
 *
 * <p>运行：{@code mvn exec:java@skilldoctor}。</p>
 */
public class SkillDoctorDemo {

    /** 低频观察阈值：近一段时间加载次数低于这个数就要盯一下。 */
    private static final int LOW_USAGE = 5;

    /** 3-gram Jaccard 相似度达到这个值就判为内容重复。 */
    private static final double DUPLICATE_SIMILARITY = 0.6;

    private static int bad;

    public static void main(String[] args) {
        header("技能库体检 /skill-doctor", "使用频率 + 内容重复度 + 触发撞车 → 膨胀候选清单");

        List<Skill> library = sampleLibrary();
        Map<String, Integer> loadCount = sampleLoadCounts();

        printLibrary(library, loadCount);

        List<String> zombies = findZombies(library, loadCount);
        List<String> lowUsage = findLowUsage(library, loadCount, zombies);
        List<DuplicatePair> duplicates = findDuplicates(library);
        List<String> collisions = findTriggerCollisions(library);

        printReport(zombies, lowUsage, duplicates, collisions);
        assertFindings(library, zombies, duplicates, collisions, loadCount);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：技能库要定期体检——僵尸清掉、重复合并、撞车收窄，");
            System.out.println("      提示词才不至于越滚越大，模型选择才不至于越来越糊涂。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 样例技能库（故意埋病灶） ---------------------------------------------------

    /** 造一个 6 技能的库：2 个健康高频、1 个低频、1 个僵尸、1 对内容重复。 */
    private static List<Skill> sampleLibrary() {
        List<Tool> none = List.of();
        List<Tool> orderTools = List.of(
                new Tool("query_order", "查订单详情", q -> "订单" + q),
                new Tool("query_shipping", "查配送时效", q -> "预计送达"));
        List<Tool> refundTools = List.of(
                new Tool("refund_apply", "发起退款", q -> "已受理"));

        return List.of(
                new Skill("order-ops", "订单", orderTools, """
                        【订单处理规范】用户问订单相关内容时：查订单用 query_order，
                        拿到商品与状态；用户问「什么时候到」时，必须再查 query_shipping
                        拿预计送达时间，两项一起答复，不要查了一半就收口。"""),
                new Skill("shipping-track", "物流", orderTools, """
                        【物流跟踪规范】用户问包裹走到哪了：用 query_shipping 查当前
                        节点与预计送达，告知用户具体日期，不要只答「在路上」。"""),
                new Skill("refund-policy", "退款", refundTools, """
                        【退款规范】用户要退款：先确认订单状态，再用 refund_apply 发起；
                        到账时效是 3-5 个工作日，主动告知用户。"""),
                new Skill("invoice-issue", "发票", none, """
                        【发票规范】用户要开发票：支持电子发票，下单后在订单页申请即可。"""),
                // —— 病灶 1：order-faq-v1 与 order-ops 内容高度重叠（旧版没删干净）
                new Skill("order-faq-v1", "订单问题", orderTools, """
                        【订单常见问题】用户问订单相关的事：查订单用 query_order，
                        拿到商品与状态；用户问「什么时候到」时，还要查 query_shipping
                        拿预计送达时间，两项一起答复，别答一半就收口。"""),
                // —— 病灶 2：sms-notify-legacy 半年没人加载过（功能早被站内信取代）
                new Skill("sms-notify-legacy", "短信", none, """
                        【短信通知规范】用户要短信提醒：用 sms_bind 绑定手机号，
                        物流节点变化时发短信通知。"""));
    }

    /** 模拟生产埋点：每个技能的近期加载次数。 */
    private static Map<String, Integer> sampleLoadCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("order-ops", 46);
        counts.put("shipping-track", 38);
        counts.put("refund-policy", 21);
        counts.put("invoice-issue", 3);
        counts.put("order-faq-v1", 2);
        counts.put("sms-notify-legacy", 0);
        return counts;
    }

    // ---- 三个体检维度 ---------------------------------------------------------------

    /** 维度 1a：僵尸技能——登记在册但近期一次都没被加载过。 */
    private static List<String> findZombies(List<Skill> library, Map<String, Integer> loadCount) {
        List<String> zombies = new ArrayList<>();
        for (Skill s : library) {
            if (loadCount.getOrDefault(s.name(), 0) == 0) {
                zombies.add(s.name());
            }
        }
        return zombies;
    }

    /** 维度 1b：低频技能——不是僵尸，但近期加载极少，值得人工看一眼是否还有用。 */
    private static List<String> findLowUsage(List<Skill> library, Map<String, Integer> loadCount,
                                             List<String> zombies) {
        List<String> low = new ArrayList<>();
        for (Skill s : library) {
            int n = loadCount.getOrDefault(s.name(), 0);
            if (n > 0 && n < LOW_USAGE && !zombies.contains(s.name())) {
                low.add(s.name() + "（" + n + " 次）");
            }
        }
        return low;
    }

    /** 维度 2：内容重复——promptSnippet 的 3-gram Jaccard 两两比对。 */
    private static List<DuplicatePair> findDuplicates(List<Skill> library) {
        List<DuplicatePair> pairs = new ArrayList<>();
        for (int i = 0; i < library.size(); i++) {
            for (int j = i + 1; j < library.size(); j++) {
                double sim = jaccard(trigrams(library.get(i).promptSnippet()),
                        trigrams(library.get(j).promptSnippet()));
                if (sim >= DUPLICATE_SIMILARITY) {
                    pairs.add(new DuplicatePair(library.get(i).name(), library.get(j).name(), sim));
                }
            }
        }
        return pairs;
    }

    /** 维度 3：触发撞车——一个技能的 trigger 包含另一个的（加载时两个会抢同一请求）。 */
    private static List<String> findTriggerCollisions(List<Skill> library) {
        List<String> collisions = new ArrayList<>();
        for (int i = 0; i < library.size(); i++) {
            for (int j = i + 1; j < library.size(); j++) {
                String a = library.get(i).trigger();
                String b = library.get(j).trigger();
                if (a.contains(b) || b.contains(a)) {
                    collisions.add(library.get(i).name() + "（\"" + a + "\"）↔ "
                            + library.get(j).name() + "（\"" + b + "\"）");
                }
            }
        }
        return collisions;
    }

    // ---- 文本相似度 -----------------------------------------------------------------

    private record DuplicatePair(String left, String right, double similarity) {
    }

    /** 字符 3-gram 集合（中文按字切，足够衡量「说同一件事」）。 */
    private static Set<String> trigrams(String text) {
        String clean = text.replaceAll("\\s+", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= clean.length(); i++) {
            grams.add(clean.substring(i, i + 3));
        }
        return grams;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / union.size();
    }

    // ---- 报告与断言 -----------------------------------------------------------------

    private static void printLibrary(List<Skill> library, Map<String, Integer> loadCount) {
        System.out.println("\n── 技能库现状（" + library.size() + " 个技能）──");
        for (Skill s : library) {
            int n = loadCount.getOrDefault(s.name(), 0);
            System.out.println("  " + padRight(s.name(), 20) + padRight("trigger=\"" + s.trigger() + "\"", 18)
                    + "近期待加载 " + n + " 次，" + s.tools().size() + " 个工具");
        }
    }

    private static void printReport(List<String> zombies, List<String> lowUsage,
                                    List<DuplicatePair> duplicates, List<String> collisions) {
        System.out.println("\n── 体检报告 ──");
        System.out.println("  ① 僵尸技能（0 加载，建议直接下线）："
                + (zombies.isEmpty() ? "无" : String.join("、", zombies)));
        System.out.println("  ② 低频技能（<" + LOW_USAGE + " 加载，人工复核去留）："
                + (lowUsage.isEmpty() ? "无" : String.join("、", lowUsage)));
        System.out.println("  ③ 内容重复（3-gram Jaccard ≥ " + DUPLICATE_SIMILARITY + "，建议合并）：");
        if (duplicates.isEmpty()) {
            System.out.println("     无");
        }
        for (DuplicatePair p : duplicates) {
            System.out.printf("     %s ↔ %s（相似度 %.2f）%n", p.left(), p.right(), p.similarity());
        }
        System.out.println("  ④ 触发撞车（trigger 互为包含，建议收窄）："
                + (collisions.isEmpty() ? "无" : String.join("；", collisions)));
    }

    private static void assertFindings(List<Skill> library, List<String> zombies,
                                       List<DuplicatePair> duplicates, List<String> collisions,
                                       Map<String, Integer> loadCount) {
        System.out.println("\n── 断言：病灶要全揪出来，健康技能不冤枉 ──");
        check("僵尸技能被揪出：sms-notify-legacy（0 加载）",
                zombies.contains("sms-notify-legacy"));
        Set<String> duplicateNames = new HashSet<>();
        duplicates.forEach(p -> {
            duplicateNames.add(p.left());
            duplicateNames.add(p.right());
        });
        check("内容重复被揪出：order-ops ↔ order-faq-v1 进合并候选",
                duplicateNames.contains("order-ops") && duplicateNames.contains("order-faq-v1"));
        check("低频技能被点名：invoice-issue（3 次）",
                findLowUsage(library, loadCount, zombies).stream().anyMatch(s -> s.startsWith("invoice-issue")));
        check("触发撞车被点名：「订单」vs「订单问题」",
                collisions.stream().anyMatch(c -> c.contains("order-ops") && c.contains("order-faq-v1")));
        check("健康高频技能 shipping-track 不被误判为僵尸",
                !zombies.contains("shipping-track") && !duplicateNames.contains("shipping-track"));
        check("用途不同的 refund-policy 不被误判为重复",
                !duplicateNames.contains("refund-policy"));
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
