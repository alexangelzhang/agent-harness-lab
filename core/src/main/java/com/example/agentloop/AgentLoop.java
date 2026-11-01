package com.example.agentloop;

import com.example.harness.Budget;
import com.example.langgraph4j.llm.EnvException;

/**
 * Agent loop 原语：行动 → 评估 → 不达标就带着反馈再来，直到达标或轮数耗尽。
 *
 * <p>对应 Addy Osmani「loop engineering」里的 goal 原语：循环本体不负责聪明，
 * 负责把「行动、检验、反馈回流」这套骨架跑严实。三个角色都是函数接口，
 * 随便换实现——LLM、规则、脚本都可以：</p>
 * <ul>
 *   <li>{@link Actor}：干活的。每轮拿到任务、历史和上一轮的反馈意见，产出一个结果；</li>
 *   <li>{@link Verifier}：验收的。每轮对结果下结论（达标 / 差在哪），理由回流下一轮；</li>
 *   <li>{@link StopCondition}（可选）：确定性终点。想要「测试全过」「分数 ≥ 90」
 *       这种硬指标时用它，比让模型自己判断「够好了吧」可靠得多。</li>
 * </ul>
 *
 * <p>失败分两路（出自《Meta 如何改造工程环境，让 Agent 自己跑开发 Loop》里
 * Stripe Minions 的失败分流）：</p>
 * <ul>
 *   <li><b>成果问题</b>（Verifier 判 NO / 行动抛普通异常）：记一轮失败尝试，
 *       理由回流下一轮，吃 maxTurns 的修复额度；</li>
 *   <li><b>环境故障</b>（行动/验收抛 {@link EnvException}，如 LLM 网关超时）：重试没用，
 *       不占 maxTurns，单独计数，攒够 maxEnvFailures 次直接终止，
 *       结果里标明是环境原因——绝不把超时当成「成果不达标」去碰运气。</li>
 * </ul>
 *
 * <p>预算（可选，{@link Budget}）：生产级的油表——不只看轮数，还看烧了多少。
 * 预算耗尽不看 maxTurns 的脸色，立刻「预算耗尽，转人工」收场，
 * 和达标收工 / 轮数耗尽 / 环境故障超限并列成第四种终态。</p>
 *
 * <p>护栏内置：maxTurns 一到立刻收工，绝不留无限循环的空子。
 * 全程逐轮记录轨迹（{@link LoopStep}），花多少轮、每轮多久、反馈是什么，
 * 跑完都能翻。</p>
 */
public final class AgentLoop {

    /** 干活的：一轮产出一个结果（一段文案、一份代码、一个工具调用结果）。 */
    public interface Actor {
        String act(LoopContext ctx) throws Exception;
    }

    /** 验收的：对本轮结果下结论；理由会回流给下一轮行动。 */
    public interface Verifier {
        Verdict verify(LoopContext ctx, String actionOutput) throws Exception;
    }

    /** 确定性终点（可选）：返回 true 立即收工，不看 Verifier 的脸色。 */
    public interface StopCondition {
        boolean met(LoopContext ctx);
    }

    /** 环境故障的默认容忍次数（不占 maxTurns 的修复额度）。 */
    public static final int DEFAULT_MAX_ENV_FAILURES = 3;

    private final Actor actor;
    private final Verifier verifier;
    private final StopCondition stopCondition;
    private final int maxTurns;
    private final int maxEnvFailures;
    private final Budget budget;

    public AgentLoop(Actor actor, Verifier verifier, int maxTurns) {
        this(actor, verifier, null, maxTurns, DEFAULT_MAX_ENV_FAILURES, null);
    }

    public AgentLoop(Actor actor, Verifier verifier, int maxTurns, Budget budget) {
        this(actor, verifier, null, maxTurns, DEFAULT_MAX_ENV_FAILURES, budget);
    }

    public AgentLoop(Actor actor, Verifier verifier, StopCondition stopCondition, int maxTurns) {
        this(actor, verifier, stopCondition, maxTurns, DEFAULT_MAX_ENV_FAILURES, null);
    }

    public AgentLoop(Actor actor, Verifier verifier, StopCondition stopCondition,
                     int maxTurns, int maxEnvFailures) {
        this(actor, verifier, stopCondition, maxTurns, maxEnvFailures, null);
    }

    public AgentLoop(Actor actor, Verifier verifier, StopCondition stopCondition,
                     int maxTurns, int maxEnvFailures, Budget budget) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns 至少为 1");
        }
        if (maxEnvFailures < 1) {
            throw new IllegalArgumentException("maxEnvFailures 至少为 1");
        }
        this.actor = actor;
        this.verifier = verifier;
        this.stopCondition = stopCondition;
        this.maxTurns = maxTurns;
        this.maxEnvFailures = maxEnvFailures;
        this.budget = budget;
    }

    public LoopResult run(String task) {
        LoopContext ctx = new LoopContext(task);
        int attempts = 0;
        int envFailures = 0;
        while (attempts < maxTurns) {
            long start = System.nanoTime();
            try {
                String output = actor.act(ctx);
                Verdict verdict = verifier.verify(ctx, output);
                attempts++;
                ctx.record(new LoopStep(ctx.turn(), output, verdict, elapsedMs(start)));
                if (verdict.done() || (stopCondition != null && stopCondition.met(ctx))) {
                    return LoopResult.success(ctx.steps());
                }
                if (budgetExhausted()) {
                    return LoopResult.budgetExhausted(ctx.steps(),
                            "预算耗尽，转人工（" + budget.describe() + "）");
                }
            } catch (EnvException e) {
                // 环境故障：不占修复额度，单独计数；超限直接收工并标明原因
                envFailures++;
                ctx.record(new LoopStep(ctx.turn(), "",
                        Verdict.envFail("环境错误（第 " + envFailures + "/" + maxEnvFailures + " 次）：" + e.getMessage()),
                        elapsedMs(start)));
                if (envFailures >= maxEnvFailures) {
                    return LoopResult.envAborted(ctx.steps(),
                            "环境故障达到上限 " + maxEnvFailures + " 次，终止：" + e.getMessage());
                }
                if (budgetExhausted()) {
                    return LoopResult.budgetExhausted(ctx.steps(),
                            "预算耗尽，转人工（" + budget.describe() + "）");
                }
            } catch (Exception e) {
                // 行动或评估抛错：当成一轮失败的尝试记录，理由里带上报错
                attempts++;
                ctx.record(new LoopStep(ctx.turn(), "", Verdict.retry("本轮出错：" + e.getMessage()),
                        elapsedMs(start)));
                if (budgetExhausted()) {
                    return LoopResult.budgetExhausted(ctx.steps(),
                            "预算耗尽，转人工（" + budget.describe() + "）");
                }
            }
        }
        return LoopResult.exhausted(ctx.steps());
    }

    private boolean budgetExhausted() {
        return budget != null && budget.exhausted();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
