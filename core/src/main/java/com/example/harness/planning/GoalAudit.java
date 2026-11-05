package com.example.harness.planning;

import com.example.agent.llm.LlmClient;

/**
 * 会话级目标与完成审计（OmO Goal 研读落地，见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>Todo 管「还有哪些事」，Goal 管「到底要达成什么」——清单全勾了
 * 不代表目标达成（事都办了，结果不对）。机制三段：</p>
 * <ol>
 *   <li><b>持续目标</b>：一个会话一个目标，opt-in（不设目标本类不参与）；</li>
 *   <li><b>空转续跑</b>：agent 想停而目标未经审计确认，注入续跑提示
 *       把它叫回来，最多续 {@link #maxContinuations} 次；</li>
 *   <li><b>完成审计</b>：能不能停不由 agent 自己宣布，由裁判模型对着
 *       目标 + 证据审一次——审计不过就继续续跑，过了才放行收工。</li>
 * </ol>
 *
 * <p>续跑上限是防死循环的闸：上限耗尽仍审不过，按「目标未达成」
 * 放行收工并留未竟说明，不陪跑到底。</p>
 */
public final class GoalAudit {

    /** 一次审计的结论。 */
    public record Verdict(boolean done, String reason) {
    }

    private final String goal;
    private final LlmClient auditor;
    private final int maxContinuations;
    private int continuations;
    private int auditorFailures;
    private Verdict lastVerdict;

    /**
     * @param goal              会话目标（一句话，可验证的那种）
     * @param auditor           完成审计的裁判模型
     * @param maxContinuations  审计不过时的续跑上限（OmO ulw-execute 用 8）
     */
    public GoalAudit(String goal, LlmClient auditor, int maxContinuations) {
        this.goal = goal;
        this.auditor = auditor;
        this.maxContinuations = maxContinuations;
    }

    /** 目标原文。 */
    public String goal() {
        return goal;
    }

    /**
     * agent 想收工时调用：先审计，审不过且续跑没超上限就返回续跑提示
     * （非 null = 不许停）；返回 null 表示放行（审计通过，或上限耗尽）。
     *
     * @param evidence  agent 自称干完了的证据（产物摘要、清单状态等）
     */
    public String onAttemptFinish(String evidence) {
        Verdict v = audit(evidence);
        lastVerdict = v;
        if (v.done()) {
            return null; // 审计通过，放行收工
        }
        if (v.reason().startsWith("审计员不可用")) {
            // 故障轮不占续跑预算（预算是给真实驳回的），但也不能无限陪跑：
            // 故障同样超上限才放行，lastVerdict 记着「没审过」
            auditorFailures++;
            if (auditorFailures > maxContinuations) {
                return null;
            }
            return "审计员暂不可用，本次收工未获审计。目标：" + goal
                    + "\n（审计故障第 " + auditorFailures + " 次，超 "
                    + maxContinuations + " 次才放行）稍后再试收工。";
        }
        if (continuations >= maxContinuations) {
            return null; // 续跑上限耗尽：放行，但 lastVerdict 记着未竟
        }
        continuations++;
        return "目标还没达成，不许收工。目标：" + goal + "\n审计意见："
                + v.reason() + "\n（第 " + continuations + "/" + maxContinuations
                + " 次续跑）请针对审计意见继续。";
    }

    /** 完成审计：裁判模型对着目标与证据裁决。回复 DONE: 理由 或 NOT_DONE: 理由。 */
    private Verdict audit(String evidence) {
        String prompt = "你是完成审计员。判断下面的证据是否足以证明目标已达成。"
                + "宁可错杀不可错放：证据对不上目标就拒。\n"
                + "目标：" + goal + "\n证据：" + evidence + "\n"
                + "只回一行：达成就回 DONE: <理由>，否则回 NOT_DONE: <差什么>。";
        String reply;
        try {
            reply = auditor.complete(prompt).trim();
        } catch (RuntimeException e) {
            // 审计员故障按「未达成」处理：宁可多续一轮，不放行一个没审过的收工
            return new Verdict(false, "审计员不可用（" + e.getMessage() + "）");
        }
        if (reply.startsWith("DONE:")) {
            return new Verdict(true, reply.substring("DONE:".length()).trim());
        }
        if (reply.startsWith("NOT_DONE:")) {
            return new Verdict(false, reply.substring("NOT_DONE:".length()).trim());
        }
        return new Verdict(false, "审计回复无法解析，按未达成处理：" + reply);
    }

    /** 已续跑次数（只计真实驳回后的续跑，审计故障不占预算）。 */
    public int continuations() {
        return continuations;
    }

    /** 审计员故障累计次数。 */
    public int auditorFailures() {
        return auditorFailures;
    }

    /** 最近一次审计结论（可能为 null = 还没审过）。 */
    public Verdict lastVerdict() {
        return lastVerdict;
    }

    /** 目标是否已被审计确认达成。 */
    public boolean achieved() {
        return lastVerdict != null && lastVerdict.done();
    }
}
