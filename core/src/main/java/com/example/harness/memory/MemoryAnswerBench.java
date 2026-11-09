package com.example.harness.memory;

import java.util.Objects;
import java.util.Set;

/** 用任务标注给真实模型终答分类，不采纳模型自评。 */
public final class MemoryAnswerBench {

    public enum Category {
        CORRECT,
        CONTAMINATED,
        ABSTAINED,
        WRONG
    }

    /**
     * 禁用值优先于其他分类；只有完整期望标识出现且没有禁用值才算答对。
     * 无记忆时的拒答单独计数，不能当成记忆机制带来的收益。
     */
    public Category classify(String answer, String expected,
                             Set<String> forbidden, Set<String> abstentionMarkers) {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(forbidden, "forbidden");
        Objects.requireNonNull(abstentionMarkers, "abstentionMarkers");
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("期望标识不能为空");
        }
        for (String value : forbidden) {
            if (!value.isEmpty() && containsCompleteIdentifier(answer, value)) {
                return Category.CONTAMINATED;
            }
        }
        if (containsCompleteIdentifier(answer, expected)) {
            return Category.CORRECT;
        }
        for (String marker : abstentionMarkers) {
            if (!marker.isEmpty() && answer.contains(marker)) {
                return Category.ABSTAINED;
            }
        }
        return Category.WRONG;
    }

    private static boolean containsCompleteIdentifier(String answer, String identifier) {
        int from = 0;
        while (from < answer.length()) {
            int start = answer.indexOf(identifier, from);
            if (start < 0) {
                return false;
            }
            int end = start + identifier.length();
            if ((start == 0 || !isIdentifierPart(answer.charAt(start - 1)))
                    && (end == answer.length() || !isIdentifierPart(answer.charAt(end)))) {
                return true;
            }
            from = start + 1;
        }
        return false;
    }

    private static boolean isIdentifierPart(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '_'
                || value == ':'
                || value == '-';
    }
}
