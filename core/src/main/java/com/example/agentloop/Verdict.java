package com.example.agentloop;

/**
 * 评估模型一轮的结论：达标了没有，没达标差在哪。
 *
 * @param done   是否达标（loop 的停止条件之一）
 * @param reason 理由。不达标时回流给下一轮行动——这是「反馈式重试」的关键，
 *               没有理由的重试只是碰运气
 * @param kind   结论的类型：达标 / 成果不达标（重试）/ 环境故障（不占修复额度）
 */
public record Verdict(boolean done, String reason, Kind kind) {

    /** 结论类型：PASS 达标收工；RETRY 成果有差距、带反馈再来；
     *  ENV_FAIL 环境出故障（LLM 超时等），不算成果问题，不占 maxTurns。 */
    public enum Kind { PASS, RETRY, ENV_FAIL }

    public static Verdict pass(String reason) {
        return new Verdict(true, reason, Kind.PASS);
    }

    public static Verdict retry(String reason) {
        return new Verdict(false, reason, Kind.RETRY);
    }

    public static Verdict envFail(String reason) {
        return new Verdict(false, reason, Kind.ENV_FAIL);
    }
}
