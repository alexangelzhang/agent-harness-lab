package com.example.harness.toolbase;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 写入护栏：把 {@link SlopCommentChecker} 接到 write/edit 的落盘前
 * （OmO comment-checker 研读落地，2026-11-06）。
 *
 * <p>OmO 的拦截点选择：挂在 {@code tool.execute.before/after} 上，
 * 检出 AI 腔注释就把这次工具调用打回（agent 收到带行号和类别的错误，
 * 修好再写），而不是等提交后 review 再返工——拦截越靠前，
 * 垃圾在转录里存活的时间越短。severity 两档：ERROR 拦死，
 * WARN 放行但把命中清单带给调用方。</p>
 */
public final class SlopWriteGuard {

    /** 严格档：ERROR 拦死，WARN 只提醒。 */
    public enum Severity {
        ERROR, WARN
    }

    /**
     * 一次写入闸门的结果。
     *
     * @param allowed 是否放行
     * @param findings 命中清单（放行时也可能非空——WARN 档）
     */
    public record Verdict(boolean allowed,
                          List<SlopCommentChecker.Finding> findings) {

        /** 打回时给 agent 看的错误文本：行号 + 类别 + 原文，照着改就行。 */
        public String errorMessage() {
            String detail = findings.stream()
                    .map(f -> "  第 " + f.line() + " 行 [" + f.category() + "] " + f.excerpt())
                    .collect(Collectors.joining("\n"));
            return "comment-checker 拦下这次写入：检出 " + findings.size()
                    + " 处 AI 腔注释，修好再落盘。\n" + detail;
        }
    }

    private final SlopCommentChecker checker = new SlopCommentChecker();
    private final Severity severity;

    public SlopWriteGuard(Severity severity) {
        this.severity = severity;
    }

    /** 落盘前调用：ERROR 档有命中即打回，WARN 档放行但带命中清单。 */
    public Verdict check(String path, String content) {
        List<SlopCommentChecker.Finding> findings = checker.scan(content);
        boolean allowed = findings.isEmpty() || severity == Severity.WARN;
        return new Verdict(allowed, findings);
    }
}
