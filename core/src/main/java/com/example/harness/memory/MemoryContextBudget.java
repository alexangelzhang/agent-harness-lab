package com.example.harness.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 顶层记忆索引的行数与 UTF-8 字节预算；正文留给按需检索，不进索引。 */
public final class MemoryContextBudget {

    /** 实际纳入的完整行、省略行数和最终 UTF-8 字节数（含换行）。 */
    public record Result(List<String> lines, int omitted, long utf8Bytes) {
        public Result {
            lines = List.copyOf(lines);
        }
    }

    private final int maxLines;
    private final long maxUtf8Bytes;

    public MemoryContextBudget(int maxLines, long maxUtf8Bytes) {
        this.maxLines = maxLines;
        this.maxUtf8Bytes = maxUtf8Bytes;
    }

    /** 索引只暴露定位与审计字段，不带记忆正文。 */
    public String indexLine(Memory memory) {
        return "[长期索引] id=" + memory.id()
                + " | 标签=" + String.join(",", memory.tags())
                + " | 来源=" + memory.source()
                + " | 知识状态=" + memory.knowledgeState()
                + " | 版本=" + memory.revision();
    }

    /** 逐行纳入；任何一行放不下就整行省略，不截断多字节字符。 */
    public Result apply(List<String> candidates) {
        List<String> lines = new ArrayList<>();
        int omitted = 0;
        long bytes = 0;
        for (String candidate : candidates) {
            if (lines.size() >= maxLines) {
                omitted++;
                continue;
            }
            long candidateBytes = utf8Bytes(candidate) + (lines.isEmpty() ? 0 : 1);
            if (bytes + candidateBytes > maxUtf8Bytes) {
                omitted++;
                continue;
            }
            lines.add(candidate);
            bytes += candidateBytes;
        }
        return new Result(lines, omitted, bytes);
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
