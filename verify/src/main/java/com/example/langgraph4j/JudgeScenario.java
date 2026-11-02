package com.example.langgraph4j;

import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.ReflectionGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.util.Map;

/**
 * judge 节点演示：顶满最大轮数后，稿子交给图里的 judge 终裁。
 *
 * <p>真实痛点来自评估：reflector 太严、始终不说 ACCEPT 时，明明达标的好稿子
 * 会被「顶满轮数」直接交差甚至判负。现在顶满轮数 → 条件边 routed 到 judge
 * 节点，按意思终裁（verdict 通道写 JUDGE_PASS / JUDGE_FAIL）。
 *
 * <p>三个案例（generator/reflector 走剧本，judge 是真实 JdkHttpLlm）：
 * <ul>
 *   <li>case 1：稿子本身好，reflector 故意一直不放行 → judge 终裁放行（捞回来）</li>
 *   <li>case 2：稿子跑题，reflector 也不放行 → judge 终裁不放行</li>
 *   <li>case 3：稿子跑题，但 reflector 太好说话直接 ACCEPT → judge 复核打回</li>
 * </ul>
 */
public class JudgeScenario {

    public static void main(String[] args) throws Exception {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  judge 节点：复核 + 终裁双职责（剧本 generator/reflector × 真实 judge）");
        System.out.println("=".repeat(66));

        String task = "为一款冷萃咖啡液写一句广告 slogan，要让人一眼看出：低温萃取、花了时间慢慢萃出来的";

        // case 1：稿子达标，但 reflector 是个永不放行的严格评审
        System.out.println("""
                ── case 1：好稿子 + 永不放行的 reflector ──
                   预期：顶满 maxRounds=2 → judge 终裁放行（JUDGE_PASS）
                """);
        runCase("judge-case1", task, new ScriptedLlm()
                        .says("十二小时低温慢萃，把时间的味道融进每一滴。")
                        .says("建议：还差点意思。评分 6/10。")
                        .says("十二小时低温慢萃，把时间的味道融进每一滴。")
                        .says("建议：还是不够精炼。评分 6/10。"),
                new JdkHttpLlm());

        // case 2：稿子跑题（说的是热咖啡、提神），reflector 同样不放行
        System.out.println("""
                ── case 2：跑题稿子 + 永不放行的 reflector ──
                   预期：顶满 maxRounds=2 → judge 终裁不放行（JUDGE_FAIL）
                """);
        runCase("judge-case2", task, new ScriptedLlm()
                        .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                        .says("建议：没有体现低温工艺。评分 5/10。")
                        .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                        .says("建议：方向不对。评分 5/10。"),
                new JdkHttpLlm());

        // case 3：稿子跑题，但 reflector 是个老好人，每轮都 ACCEPT → judge 复核打回
        System.out.println("""
                ── case 3：跑题稿子 + 太好说话的 reflector ──
                   预期：reflector 放行 → judge 复核打回 → 顶满轮数（JUDGE_REJECT）
                """);
        runCase("judge-case3", task, new ScriptedLlm()
                        .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                        .says("语言精炼、气势十足，符合要求。ACCEPT")
                        .says("热气腾腾的现磨咖啡，一口下去立刻精神百倍！")
                        .says("确实不错。ACCEPT"),
                new JdkHttpLlm());

        // langgraph4j 内部的异步执行器是非 daemon 线程，演示结束后显式退出
        System.exit(0);
    }

    private static void runCase(String threadId, String task,
                                ScriptedLlm scripted, JdkHttpLlm judge) throws Exception {
        var graph = new ReflectionGraph(scripted, 2, judge).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());

        var finalState = graph.invoke(
                GraphInput.args(Map.<String, Object>of("task", task)),
                RunnableConfig.builder().threadId(threadId).build());

        finalState.ifPresent(s -> {
            String verdict = s.<String>value("verdict").orElse("");
            String meaning = switch (verdict) {
                case "ACCEPT" -> "reflector 放行，judge 复核确认";
                case "JUDGE_PASS" -> "reflector 未放行，judge 终裁放行";
                case "JUDGE_FAIL" -> "reflector 未放行，judge 终裁也不放行";
                case "JUDGE_REJECT" -> "reflector 放行了，但 judge 复核打回，顶满轮数，未通过";
                default -> "顶满轮数，无人放行";
            };
            System.out.println("\n  [定稿] " + s.<String>value("draft").orElse("")
                    + "（共 " + s.<Integer>value("round").orElse(0) + " 轮，终裁=" + verdict + "：" + meaning + "）");
        });
    }
}
