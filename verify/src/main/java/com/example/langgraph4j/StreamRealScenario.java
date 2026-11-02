package com.example.langgraph4j;

import com.example.langgraph4j.llm.JdkHttpLlm;

/**
 * 流式输出真实对照组（{@code StreamScenario} 的真实版，2026-11-01）。
 *
 * <p>剧本版证明「逐块消费 / 中途中止的代码路径对」；这个场景 证明
 * 「真实模型的 SSE 流真的一块块来，且提前关流真的中止」。硬断言只校
 * 结构：块数 ≥ 1、拼接结果非空、熔断场景确实提前收工，措辞不断言。</p>
 *
 * <p>两个场景（真实调用慢，{@code -Dexec.args="n"} 只跑第 n 个）：</p>
 * <ol>
 *   <li><b>完整流式</b>：让 真实模型写一两句产品文案，逐块打印、计数、拼接，
 *       校 SSE 解析不把内容弄丢（转义口径与 complete 一致）；</li>
 *   <li><b>真实熔断</b>：同一个生成只给 30 个字的预算，超预算立即中止，
 *       校「提前关流」真实发生且拿到了可用前缀。</li>
 * </ol>
 *
 * <p>运行：{@code mvn exec:java@streamreal -Dexec.args="[1-2]"}，
 * 需要 {@code LLM_API_KEY}，缺了明确报错退出。</p>
 */
public class StreamRealScenario {

    private static int bad;

    public static void main(String[] args) {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("流式输出真实对照组：SSE 逐块接收 / 真实熔断");
        System.out.println("模式：真实模型（JdkHttpLlm）" + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        if (pick == 0 || pick == 1) {
            scenarioFullStream();
        }
        if (pick == 0 || pick == 2) {
            scenarioAbort();
        }

        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：真实 SSE 一块块到，提前关流真的停——"
                    + "流式的省，是真金白银的省。");
        } else {
            System.out.println("!! 有 " + bad + " 处结构校验未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 场景一：完整流式，逐块打印并拼接。 */
    private static void scenarioFullStream() {
        header("场景一 · 完整流式：一句话文案逐块到达");
        JdkHttpLlm llm = new JdkHttpLlm();
        StringBuilder assembled = new StringBuilder();
        int[] chunks = {0};
        long start = System.currentTimeMillis();
        boolean finished = llm.stream("用一两句话介绍一款手冲咖啡壶，中文，不要说空话",
                chunk -> {
                    chunks[0]++;
                    assembled.append(chunk);
                    System.out.println("    块 " + chunks[0] + "（累计 " + assembled.length() + " 字）← "
                            + abbreviate(chunk));
                    return true;
                });
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("  共 " + chunks[0] + " 块，" + elapsed + "ms，拼出来：「" + assembled + "」");
        check("自然生成完整结束（stream 返回 true）", finished);
        check("块数 ≥ 1", chunks[0] >= 1);
        check("拼接结果非空", !assembled.toString().isBlank());
    }

    /** 场景二：真实熔断——预算 30 字，超了立即关流。 */
    private static void scenarioAbort() {
        header("场景二 · 真实熔断：预算 30 字，超了立即关流");
        JdkHttpLlm llm = new JdkHttpLlm();
        int budget = 30;
        StringBuilder head = new StringBuilder();
        long start = System.currentTimeMillis();
        boolean finished = llm.stream("写一段 100 字左右的咖啡产季介绍，中文",
                chunk -> {
                    head.append(chunk);
                    System.out.println("    已生成 " + head.length() + " 字 ← " + abbreviate(chunk));
                    return head.length() < budget;
                });
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("  " + (finished ? "生成自然结束" : "被预算熔断提前关流") + "，拿到 "
                + head.length() + " 字可用前缀（" + elapsed + "ms）");
        check("确实提前中止（stream 返回 false）", !finished);
        check("前缀非空且不超预算太多", !head.toString().isBlank() && head.length() < budget + 200);
    }

    private static String abbreviate(String chunk) {
        String oneLine = chunk.replace('\n', '↵');
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
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
