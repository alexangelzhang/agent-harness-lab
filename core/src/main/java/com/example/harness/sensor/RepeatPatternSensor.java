package com.example.harness.sensor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算型 sensor：重复模式粗检——同一段非空内容出现 ≥3 次就算重复
 * （真重复代码检测用 CPD/pmd，这里是结构占位：位置给首现行号）。
 * 毫秒级，PRE_COMMIT 层每次跑。
 */
public final class RepeatPatternSensor implements Sensor {

    private static final int MIN_LEN = 12;
    private static final int MIN_OCCURRENCES = 3;

    private final String ruleName;

    public RepeatPatternSensor() {
        this("重复模式");
    }

    public RepeatPatternSensor(String ruleName) {
        this.ruleName = ruleName;
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
        Map<String, List<Integer>> seen = new LinkedHashMap<>();
        String[] lines = change.content().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String key = lines[i].strip();
            if (key.length() >= MIN_LEN) {
                seen.computeIfAbsent(key, k -> new ArrayList<>()).add(i + 1);
            }
        }
        List<Finding> out = new ArrayList<>();
        seen.forEach((text, lineNos) -> {
            if (lineNos.size() >= MIN_OCCURRENCES) {
                out.add(Finding.block(ruleName,
                        change.fileName() + ":" + lineNos.get(0),
                        "同一段内容出现 " + lineNos.size() + " 次（"
                                + lineNos + "）：「" + abbreviate(text) + "」",
                        "抽成一份：定义一次、引用多次。如果是三处恰好相似但语义不同，"
                                + "把各自的差异点写进命名里，让下次读到的人不用对比。"));
            }
        });
        return out;
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
