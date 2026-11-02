package com.example.langgraph4j;

import com.example.langgraph4j.llm.ScriptedLlm;

import java.util.function.Predicate;

/**
 * LLM 流式输出剧本演示：{@code LlmClient.stream} 的「逐块消费 / 中途中止」
 * 代码路径，离线就能演练。
 *
 * <p>两幕：①完整流式——一块块拼出整条回复，演示边生成边处理；
 * ②预算熔断——只给 24 个字的预算，超了立即中止，打出生成了多少、
 * 省下了多少。剧本的块是切出来的，没有真实网络延迟，但中止判断、
 * 拼接逻辑与真实 SSE 路径完全一致（真实版见 {@code StreamRealScenario}）。</p>
 *
 * <p>运行：{@code mvn exec:java@stream}。</p>
 */
public class StreamScenario {

    private static int bad;

    public static void main(String[] args) {
        header("LLM 流式输出：边生成边消费，超预算立刻熔断",
                "stream() 回调返回 false = 中止，没生成的部分不花钱");

        // 第一幕：完整流式
        System.out.println();
        System.out.println("—— 第一幕：完整流式，一块块拼出整条回复 ——");
        ScriptedLlm barista = new ScriptedLlm()
                .says("这杯是耶加雪菲，水洗处理，柑橘调性，尾段带一点茉莉花香。");
        StringBuilder assembled = new StringBuilder();
        StringBuilder live = new StringBuilder();
        boolean finished = barista.stream("介绍下今天的单品", chunk -> {
            assembled.append(chunk);
            live.append(chunk);
            System.out.println("    块 " + pad(live) + " ← " + chunk);
            return true;
        });
        check("自然生成完整结束（stream 返回 true）", finished);
        check("拼接结果与剧本台词一致", assembled.toString()
                .equals("这杯是耶加雪菲，水洗处理，柑橘调性，尾段带一点茉莉花香。"));

        // 第二幕：预算熔断
        System.out.println();
        System.out.println("—— 第二幕：预算只有 24 个字，超了立即熔断 ——");
        ScriptedLlm storyteller = new ScriptedLlm()
                .says("从前有座山，山里有座庙，庙里有个老和尚在讲故事，讲的什么呢？从前有座山……");
        int budget = 24;
        StringBuilder head = new StringBuilder();
        boolean aborted = !storyteller.stream("讲个长故事", new Predicate<>() {
            private int used;

            @Override
            public boolean test(String chunk) {
                head.append(chunk);
                used += chunk.length();
                System.out.println("    已生成 " + used + " 字 ← " + chunk);
                return used < budget; // 预算耗尽即中止
            }
        });
        int total = "从前有座山，山里有座庙，庙里有个老和尚在讲故事，讲的什么呢？从前有座山……".length();
        check("预算耗尽被中止（stream 返回 false）", aborted);
        check("只拿到预算内的前缀（" + head.length() + " 字 ≤ " + budget + " 字）", head.length() <= budget);
        System.out.println("    熔断时已有足够判断依据：「" + head + "」——剩下 "
                + (total - head.length()) + " 字不用生成，省下了。");

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：流式不是炫技——边生成边判断，该收口时一分冤枉钱不用花。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    private static String pad(StringBuilder sb) {
        return String.format("%2d 字", sb.length());
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
