package com.example.agentloop;

import com.example.harness.Budget;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.EnvException;
import com.example.langgraph4j.llm.KimiLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

/**
 * Agent loop 演示：行动 → 验收 → 带着反馈再来，直到达标或轮数耗尽。
 *
 * <p>场景 1（剧本，离线）：验收方前两轮各挑一个毛病（没体现低温 / 没体现慢），
 * 第三轮放行——验证反馈意见确实回流到了行动方的提示词里。
 * <p>场景 2（真实模型，需要 KIMI_API_KEY）：同一个任务交给 KimiLlm 跑。
 * <p>场景 3（剧本，离线）：LLM 网关先超时两次（环境故障），第三次调用成功。
 * maxTurns 只给了 1 轮修复额度——如果超时也算一轮，任务直接失败；
 * 环境故障单独计数、不占额度，所以照样达标。
 * <p>场景 4（剧本，离线）：验收方永远说不，预算耗尽转人工。maxTurns 给 10
 * 轮、预算只给 4 次 LLM 调用——第 2 轮验收完预算见底，不看轮数脸色，
 * 「预算耗尽，转人工」收场。
 */
public class AgentLoopDemo {

    private static final String TASK = "为一款冷萃咖啡液写一句广告 slogan";
    private static final String GOAL = "广告词要同时让人看出两点：一是低温萃取，二是花了时间慢慢萃。换个说法也算。";

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  Agent loop 原语 —— 行动 → 验收 → 带反馈重试");
        System.out.println("=".repeat(66));

        scriptedScenario();
        envFailureScenario();
        budgetScenario();
        if (System.getenv("KIMI_API_KEY") != null) {
            realScenario();
        } else {
            System.out.println("\n（未检测到 KIMI_API_KEY，跳过真实模型场景）");
        }
        System.exit(0);
    }

    private static void scriptedScenario() {
        System.out.println("""
                ── 场景 1（剧本）：验收方挑两轮毛病后放行 ──
                   行动方剧本：初稿只说提神 → 二稿只说低温 → 三稿两点都有
                   验收方剧本：NO：没有体现低温 → NO：没有体现慢 → YES
                   验证点：每一轮行动方的提示词里都带着上一轮的反馈意见
                """);
        var actor = new LlmActor(new ScriptedLlm()
                .says("一口下去，精神百倍，加班不犯困！")
                .says("低温冷萃，锁住咖啡本味。")
                .says("十二小时低温慢萃，把时间的味道融进每一滴。"));
        var verifier = new LlmVerifier(new ScriptedLlm()
                .says("NO：没有体现低温萃取")
                .says("NO：没有体现花了时间慢慢萃")
                .says("YES"), GOAL);
        print(new AgentLoop(actor, verifier, 4).run(TASK));
    }

    private static void envFailureScenario() {
        System.out.println("""
                ── 场景 3（剧本）：LLM 网关超时两次，不算修复轮数 ──
                   行动方剧本：超时 → 超时 → 直接给出合格稿
                   验收方剧本：YES
                   maxTurns = 1：超时若占额度，任务第一轮就耗尽；
                   环境故障单独计数（上限 3 次），不占额度，照样达标
                """);
        var actor = new LlmActor(new ScriptedLlm()
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                })
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                })
                .says("十二小时低温慢萃，把时间的味道融进每一滴。"));
        var verifier = new LlmVerifier(new ScriptedLlm().says("YES"), GOAL);
        print(new AgentLoop(actor, verifier, 1).run(TASK));
    }

    private static void budgetScenario() {
        System.out.println("""
                ── 场景 4（剧本）：验收永远说不，预算耗尽转人工 ──
                   剧本：行动方一直交稿、验收方一直 NO（剧本够烧 6 轮）
                   maxTurns = 10，预算 = 4 次 LLM 调用（actor/verifier 共用计数器）
                   第 2 轮验收完恰好 4 次调用：不看轮数脸色，预算耗尽转人工
                """);
        var shared = new CountingLlm(new ScriptedLlm()
                .says("一口下去，精神百倍，加班不犯困！")
                .says("NO：没有体现低温萃取")
                .says("低温冷萃，锁住咖啡本味。")
                .says("NO：没有体现花了时间慢慢萃")
                .says("十二小时低温慢萃，把时间的味道融进每一滴。")
                .says("NO：换一批毛病")
                .says("永远过不了的稿子")
                .says("NO：永远不行")
                .says("还是过不了")
                .says("NO：不行")
                .says("最后的稿子")
                .says("NO：最后的拒绝"));
        var actor = new LlmActor(shared);
        var verifier = new LlmVerifier(shared, GOAL);
        var loop = new AgentLoop(actor, verifier, 10, Budget.calls(shared::calls, 4));
        print(loop.run(TASK));
    }

    private static void realScenario() {
        System.out.println("""
                ── 场景 2（真实模型 KimiLlm）：验收不过就带反馈再来 ──
                """);
        var actor = new LlmActor(new KimiLlm());
        var verifier = new LlmVerifier(new KimiLlm(), GOAL);
        print(new AgentLoop(actor, verifier, 3).run(TASK));
    }

    private static void print(LoopResult r) {
        System.out.println("\n  loop 轨迹（共 " + r.turns() + " 步，" + r.stopReason() + "）：");
        for (LoopStep s : r.steps()) {
            System.out.printf("  [第 %d 轮 %dms] %s%n", s.turn(), s.durationMs(), s.output());
            String tag = switch (s.verdict().kind()) {
                case PASS -> "YES ";
                case RETRY -> "NO：";
                case ENV_FAIL -> "环境故障：";
            };
            System.out.println("    评估：" + tag + s.verdict().reason());
        }
        System.out.println("\n  [最终成果] " + r.finalOutput());
    }
}
