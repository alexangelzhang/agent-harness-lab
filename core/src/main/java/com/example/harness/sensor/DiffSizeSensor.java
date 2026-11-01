package com.example.harness.sensor;

import java.util.List;

/**
 * 计算型 sensor：改动行数红线——单文件非空行超过阈值（默认 300）
 * 报警。这是「人对 300 行函数的审美厌恶」的外部化（Fowler：agent 没有
 * 这种内化的判断，harness 把人类经验显式化）。毫秒级，每次跑。
 */
public final class DiffSizeSensor implements Sensor {

    private final String ruleName;
    private final int maxTouchedLines;

    public DiffSizeSensor() {
        this("改动行数红线", 300);
    }

    public DiffSizeSensor(String ruleName, int maxTouchedLines) {
        this.ruleName = ruleName;
        this.maxTouchedLines = maxTouchedLines;
    }

    @Override
    public String name() {
        return ruleName;
    }

    @Override
    public Kind kind() {
        return Kind.COMPUTATIONAL;
    }

    @Override
    public List<Finding> check(CodeChange change) {
        int lines = change.touchedLines();
        if (lines <= maxTouchedLines) {
            return List.of();
        }
        return List.of(Finding.block(ruleName, change.fileName(),
                "单文件非空行 " + lines + "，超过红线 " + maxTouchedLines,
                "先停下来拆分：一个文件只干一件事。把可独立测试的部分拆出去，"
                        + "主文件留编排——大改动的正确路径是拆，不是继续堆。"));
    }
}
