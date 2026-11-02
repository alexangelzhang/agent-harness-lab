package com.example.harness.context;

import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;

/**
 * 上下文管理与成本控制演示（教程第 5 章）。
 *
 * <p>场景一「逐步裁剪」：小预算窗口跑一段剧本对话，每轮打印窗口账本，
 * 超预算时最老的 tool 输出先被裁，裁剪日志逐条可审计；外加一段超长输出
 * 演示输出上限截断。</p>
 *
 * <p>场景二「响应缓存」：同一个提示词问三遍——第一次烧调用，后两次
 * 命中缓存，CountingLlm 的账本上只有 1 次。</p>
 *
 * <p>运行：{@code mvn exec:java@context}。</p>
 */
public class ContextScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("上下文管理：用量记账、逐步裁剪、输出上限、响应缓存");
        System.out.println(line);
        scenarioTrim();
        scenarioCache();
        scenarioCompact();
        scenarioCacheTtl();
        System.out.println();
        System.out.println(line);
        System.out.println("收尾：窗口超了有两档——先摘要压缩（要点不丢），还不够再逐步裁剪；");
        System.out.println("缓存有保质期、有容量、可按谓词作废——不是 set-and-forget。");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：小预算窗口的逐步裁剪 + 输出上限。 */
    private static void scenarioTrim() {
        header("场景一：逐步裁剪", "system 与最近两条受保护，最老的 tool 输出先丢");
        // 预算压得很小（约 110 token），让裁剪第三轮就启动
        ContextWindow window = new ContextWindow(110, 60);
        window.add("system", "你是运维助手，回答要简短。");
        ScriptedLlm llm = new ScriptedLlm()
                .says("查一下订单 1001 的物流")
                .says("再看下订单 1002")
                .says("两个订单都催一下");
        String[] toolOutputs = {
                "订单1001：已发货，顺丰 SF123，预计明天送达。全程轨迹：杭州仓→上海转运→北京。",
                "订单1002：仓库处理中，预计今天傍晚出库，明早发运。备注：含一件易碎品，已加固。",
                "催单完成：1001 已通知顺丰加急；1002 已通知仓库优先出库。"};

        for (int turn = 1; turn <= 3; turn++) {
            String user = "第" + turn + "轮：" + llm.complete(window.render());
            window.add("user", user);
            String tool = toolOutputs[turn - 1];
            window.add("tool", tool);
            String model = window.recordOutput("好的，" + tool);
            ContextWindow.Stats s = window.stats();
            System.out.printf("%n-- 第%d轮后：窗口 %d 条 / %d token（预算 110），累计入 %d / 出 %d，已裁 %d 条%n",
                    turn, s.messages(), s.windowTokens(), s.totalInput(), s.totalOutput(), s.trimmed());
            if (turn == 3) {
                System.out.println("   渲染进下一轮提示词的窗口：");
                System.out.println(indent(window.render().stripTrailing()));
            }
        }
        System.out.println("\n裁剪日志（丢了什么，逐条可审计）：");
        for (String entry : window.trimLog()) {
            System.out.println("   " + entry);
        }

        // 输出上限：一段超长输出被截断记账
        String longOutput = "报".repeat(200);
        String kept = window.recordOutput(longOutput);
        System.out.println("\n输出上限（cap=60）：200 字输出入账 " + kept.length() + " 字，尾部截断。");
    }

    /** 场景二：响应缓存——重复提示词命中缓存，调用数不涨。 */
    private static void scenarioCache() {
        header("场景二：响应缓存", "同一个提示词问三遍，LLM 只被调一次");
        CountingLlm meter = new CountingLlm(new ScriptedLlm()
                .says("已发货，预计明天送达")
                .says("仓库处理中，预计今天傍晚出库"));
        CachingLlm cache = new CachingLlm(meter);

        String prompt = "查一下订单 1001 的物流";
        for (int i = 1; i <= 3; i++) {
            String reply = cache.complete(prompt);
            System.out.printf("第%d次提问 → %s（命中 %d / 未中 %d）%n",
                    i, reply, cache.hits(), cache.misses());
        }
        cache.complete("换个问题：订单 1002 呢？");
        System.out.printf("换个问题 → 未命中，真实调用（命中 %d / 未中 %d）%n", cache.hits(), cache.misses());
        System.out.printf("CountingLlm 总调用 %d 次——3 次重复提问只烧了 1 次%n", meter.calls());
    }

    /** 场景三：摘要压缩——比逐步裁剪聪明一档，要点不丢。 */
    private static void scenarioCompact() {
        header("场景三：摘要压缩", "最老的一批消息压成摘要钉进窗口，细节让位给要点");
        ContextWindow window = new ContextWindow(150, 60);
        window.add("system", "你是运维助手。");
        window.add("user", "查一下订单 1001 的物流");
        window.add("tool", "订单1001已发货，顺丰 SF123，明天送达。");
        window.add("user", "再看下订单 1002");
        window.add("tool", "订单1002仓库处理中，预计今天傍晚出库，明早发运，含一件易碎品已加固。");
        System.out.println("压缩前（" + window.stats().windowTokens() + " token）：");
        System.out.println(indent(window.render().stripTrailing()));

        ScriptedLlm summarizer = new ScriptedLlm()
                .says("用户先后查询订单 1001（已发货，明天送达）与 1002（处理中，明早发运）。");
        String summary = window.compactWith(summarizer, 4);
        System.out.println("\n压缩后（" + window.stats().windowTokens() + " token），摘要：");
        System.out.println("   " + summary);
        System.out.println(indent(window.render().stripTrailing()));
        System.out.println("\n裁剪日志：");
        for (String entry : window.trimLog()) {
            System.out.println("   " + entry);
        }
    }

    /** 场景四：缓存的保质期——TTL 过期、按谓词作废、容量淘汰。 */
    private static void scenarioCacheTtl() {
        header("场景四：缓存有保质期", "TTL 到点作废、谓词批量失效、容量挤淘汰");
        // TTL 50ms，容量 2 条
        CachingLlm cache = new CachingLlm(new ScriptedLlm()
                .says("甲的回答").says("乙的回答").says("丙的回答").says("丁的回答"), 50, 2);
        String q1 = "问题甲（含今日日期）";
        String q2 = "问题乙";
        String q3 = "问题丙";

        cache.complete(q1);
        cache.complete(q2);
        System.out.printf("问完甲、乙：命中 %d / 未中 %d / 过期 %d / 淘汰 %d，缓存 %d 条%n",
                cache.hits(), cache.misses(), cache.expired(), cache.evicted(), cache.size());

        cache.complete(q3); // 容量 2 → 挤掉最久未用的甲
        System.out.printf("再问丙（容量 2）：命中 %d / 未中 %d / 过期 %d / 淘汰 %d，缓存 %d 条——甲被挤出去%n",
                cache.hits(), cache.misses(), cache.expired(), cache.evicted(), cache.size());

        cache.complete(q3); // 命中
        sleep(60);          // 等 TTL 过期
        cache.complete(q3); // 过期 → 重新调用
        System.out.printf("TTL 50ms 后重问丙：命中 %d / 未中 %d / 过期 %d——过期单独记账%n",
                cache.hits(), cache.misses(), cache.expired());

        int invalidated = cache.invalidate(p -> p.contains("乙"));
        System.out.printf("按谓词作废含「乙」的条目：清掉 %d 条，缓存剩 %d 条%n",
                invalidated, cache.size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("-".repeat(66));
        System.out.println("【" + title + "】" + subtitle);
    }

    private static String indent(String s) {
        return "   | " + s.replace("\n", "\n   | ");
    }
}
