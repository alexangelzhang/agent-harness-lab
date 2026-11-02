package com.example.multiagent;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.DelegationGuard.Config;
import com.example.multiagent.SwarmPattern.Specialist;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Swarm 整轮时间盒演示：接力不是没有尽头的——给整轮运行一个死线，
 * 到点就拿着当前这棒的回复就地收工，不陪跑。
 *
 * <p>两幕对照：①不设时间盒——接入 → 物流 → 退款 → 财务 四棒接力跑完；
 * ②时间盒 500ms——每棒要磨 400ms，死线到了就不再往下传，已经拿到的
 * 回复原样保留。断言时间盒版的交接跳数严格少于完整版，且 trace 里
 * 留得下「到点就地收工」的痕迹。剧本离线可跑。</p>
 *
 * <p>时间盒的口径：只影响「还要不要继续交接」，不丢弃已有产出——
 * 首棒回复永远拿得到，用户永远有东西可看。</p>
 *
 * <p>运行：{@code mvn exec:java@swarmdeadline}。</p>
 */
public class SwarmDeadlineScenario {

    private static int bad;

    public static void main(String[] args) {
        header("Swarm 整轮时间盒：到点就地收工，已有产出不丢",
                "死线只掐「还交不交接」，不掐「手里已有的回复」");

        // 第一幕：不设时间盒，四棒跑完
        System.out.println();
        System.out.println("—— 第一幕：不设时间盒，接入 → 物流 → 退款 → 财务 跑完全程 ——");
        DelegationGuard fullGuard = new DelegationGuard(new Config(10, 10), "接入");
        long fullStart = System.currentTimeMillis();
        String fullAnswer = SwarmPattern.run("我的包裹一周没动了，想退款",
                relayRoster(), router(), fullGuard, consoleTrace(), 0);
        long fullMs = System.currentTimeMillis() - fullStart;
        System.out.println("  完整接力墙钟 " + fullMs + "ms，" + fullGuard.handoffs()
                + " 跳，终局：「" + fullAnswer + "」");
        check("四棒跑完（3 跳）", fullGuard.handoffs() == 3);
        check("收到财务的收口回复", fullAnswer.contains("已为您原路退回"));

        // 第二幕：时间盒 500ms，到点收工
        System.out.println();
        System.out.println("—— 第二幕：时间盒 500ms，每棒磨 400ms，到点就不再传 ——");
        DelegationGuard boxedGuard = new DelegationGuard(new Config(10, 10), "接入");
        long deadline = System.currentTimeMillis() + 500;
        String boxedAnswer = SwarmPattern.run("我的包裹一周没动了，想退款",
                relayRoster(), router(), boxedGuard, consoleTrace(), deadline);
        System.out.println("  时间盒版 " + boxedGuard.handoffs() + " 跳，拿到：「" + boxedAnswer + "」");
        check("交接跳数严格少于完整版（没有陪跑到底）", boxedGuard.handoffs() < fullGuard.handoffs());
        check("手里已有回复不丢（非空）", boxedAnswer != null && !boxedAnswer.isBlank());

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：时间盒让接力有尽头——到点收工，用户手里始终有答复，"
                    + "不会陪着一个失控的接力链空等。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 客服接力花名册：每棒先磨 400ms 再回话，cue 词指向下一家。 */
    private static List<Specialist> relayRoster() {
        return List.of(
                new Specialist("接入", "问清单号和诉求", sleepy(400, "单号查到了，这单是物流环节卡住，转物流同事跟进。")),
                new Specialist("物流", "查包裹轨迹", sleepy(400, "轨迹显示滞留分拨中心三天，建议直接退款，转退款专员。")),
                new Specialist("退款", "办退款", sleepy(400, "退款申请已建，原路退回需财务放行，转财务。")),
                new Specialist("财务", "放行退款", sleepy(400, "已为您原路退回，请留意查收银行卡。")));
    }

    private static BiFunction<String, String, Optional<String>> router() {
        return SwarmPattern.keywordRouter(Map.of(
                "接入", List.of(),
                "物流", List.of("物流"),
                "退款", List.of("退款"),
                "财务", List.of("财务")));
    }

    private static LlmClient sleepy(int millis, String reply) {
        return new ScriptedLlm().next(prompt -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reply;
        });
    }

    private static BiConsumer<String, String> consoleTrace() {
        return (who, what) -> System.out.println("    [" + who + "] " + what);
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
