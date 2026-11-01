package com.example.harness.interaction;

/**
 * 提问渠道：把 {@link StructuredQuestion} 给人、把答案带回来。
 *
 * <p>CLI、Web 按钮卡片、飞书审批流都是 {@code Interviewer} 的实现；
 * agent 侧只面对问题结构，不关心渲染。剧本演示用 {@link ScriptedInterviewer}。</p>
 */
public interface Interviewer {

    /** 发一道题，取回答案（必须是选项 label 之一）。 */
    String ask(StructuredQuestion question);
}
