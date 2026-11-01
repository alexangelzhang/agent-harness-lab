package com.example.langgraph4j.llm;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 环境错误：Agent 本身没干错，是它依赖的环境出问题——LLM 网关超时、连接断开、
 * 被中断、配额耗尽这类。和「代码写错了 / 成果没达标」必须分开处理：
 * 代码错才需要反馈重试，环境错重试多少次都没用，先恢复环境（出自
 * 《Meta 如何改造工程环境，让 Agent 自己跑开发 Loop》里 Stripe Minions 的失败分流：
 * 代码或断言错误返回修正的 Agent，环境失联则先恢复环境）。
 *
 * <p>约定：Actor / Verifier / 图节点这类调用方识别出环境类故障就抛本异常
 * （或用 {@link #wrapIfEnv(RuntimeException)} 翻译异常链），执行系统
 * （AgentLoop 或 ReflectionGraph）对它单独计数、不占修复轮数额度，
 * 超过上限直接终止并标明原因——绝不把环境故障记成一轮「成果不达标」去碰运气。</p>
 */
public class EnvException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnvException(String message) {
        super(message);
    }

    public EnvException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 翻译异常链：链上出现 IO / 超时 / 中断类故障就包成 EnvException，
     * 否则原样返回。LLM 客户端常把 IOException 包在 IllegalStateException 里抛出，
     * 调用方靠这个方法做统一判定。
     */
    public static RuntimeException wrapIfEnv(RuntimeException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof IOException || c instanceof TimeoutException || c instanceof InterruptedException) {
                return new EnvException("环境故障：" + c.getMessage(), e);
            }
        }
        return e;
    }
}
