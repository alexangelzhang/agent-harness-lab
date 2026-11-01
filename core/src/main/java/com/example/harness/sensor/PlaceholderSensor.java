package com.example.harness.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 计算型 sensor：占位符残留检查（TODO / FIXME / XXX 进了产出
 * 就是没干完）。毫秒级、确定性，PRE_COMMIT 层每次跑。
 */
public final class PlaceholderSensor implements Sensor {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\b(TODO|FIXME|XXX)\\b[:：]?(.*)");

    private final String ruleName;

    public PlaceholderSensor() {
        this("占位符残留");
    }

    public PlaceholderSensor(String ruleName) {
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
        List<Finding> out = new ArrayList<>();
        String[] lines = change.content().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var m = PLACEHOLDER.matcher(lines[i]);
            if (m.find()) {
                out.add(Finding.block(ruleName,
                        change.fileName() + ":" + (i + 1),
                        "产出里残留 " + m.group(1)
                                + (m.group(2).isBlank() ? "" : "（" + m.group(2).strip() + "）"),
                        "交付前把这条占位处理掉：要么做完，要么明确写「不做，原因是 X」，"
                                + "不许裸占位符进终稿。"));
            }
        }
        return out;
    }
}
