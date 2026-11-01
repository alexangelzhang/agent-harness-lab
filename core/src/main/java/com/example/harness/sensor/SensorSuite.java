package com.example.harness.sensor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensor 编排层（quality left：检查按成本/速度/关键度沿交付时间线分布）。
 *
 * <p>两层时机：</p>
 * <ul>
 *   <li>{@link Tier#PRE_COMMIT}：计算型——毫秒级可靠，<b>每次变更都跑</b>，
 *       尽量左移，在进评审前就把结构性问题拦下；</li>
 *   <li>{@link Tier#INTEGRATION}：推断型——慢、贵、不确定，<b>按抽样间隔跑</b>
 *       （默认每 5 次跑 1 次），放在集成后的时机，不为每次提交烧钱。</li>
 * </ul>
 *
 * <p>一致性感知（Fowler 开放问题）：guides（AGENTS.md 类前馈规则）文本
 * 可注入，发现与 guides 撞车（guides 已要求、sensor 仍发现违反）时原样
 * 标注进 {@link Finding#guidesNote()}——不替模型裁决谁对，只让冲突显形。</p>
 */
public final class SensorSuite {

    /** 检查时机：PRE_COMMIT 每次跑（计算型）；INTEGRATION 抽样跑（推断型）。 */
    public enum Tier { PRE_COMMIT, INTEGRATION }

    /** 一份运行报告：发现 + 每类 sensor 各触发几次 + 本轮被跳过的推断型。 */
    public record Report(List<Finding> findings, Map<String, Integer> triggered,
                         List<String> skipped) {

        /** 渲染成回流给下一轮模型的结构化块；无发现返回空串。 */
        public String toPromptBlock() {
            if (findings.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("sensor 发现（逐条处理）：\n");
            findings.forEach(f -> sb.append(f.toPromptBlock()).append("\n"));
            return sb.toString().stripTrailing();
        }
    }

    private final List<Sensor> preCommit = new ArrayList<>();
    private final List<Sensor> integration = new ArrayList<>();
    private final String guidesText;
    private final int inferentialEvery;

    /** guidesText 可空（不撞车标注）；inferentialEvery = 推断型抽样间隔。 */
    public SensorSuite(String guidesText, int inferentialEvery) {
        this.guidesText = guidesText == null ? "" : guidesText;
        this.inferentialEvery = Math.max(1, inferentialEvery);
    }

    public SensorSuite add(Sensor sensor) {
        (sensor.kind() == Sensor.Kind.COMPUTATIONAL ? preCommit : integration)
                .add(sensor);
        return this;
    }

    /** 跑一轮检查。runIndex 从 1 计：推断型只在 runIndex % 间隔 == 0 时跑。 */
    public Report run(CodeChange change, int runIndex) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Integer> triggered = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Sensor s : preCommit) {
            List<Finding> fs = s.check(change);
            triggered.merge(s.name(), 1, Integer::sum);
            findings.addAll(fs);
        }
        for (Sensor s : integration) {
            if (runIndex % inferentialEvery != 0) {
                skipped.add(s.name());
                continue;
            }
            List<Finding> fs = s.check(change);
            triggered.merge(s.name(), 1, Integer::sum);
            findings.addAll(fs);
        }
        annotateGuides(findings);
        return new Report(List.copyOf(findings), Map.copyOf(triggered),
                List.copyOf(skipped));
    }

    /** 全部 sensor 名（含从未触发的——报告要列）。 */
    public List<String> sensorNames() {
        List<String> names = new ArrayList<>();
        preCommit.forEach(s -> names.add(s.name()));
        integration.forEach(s -> names.add(s.name()));
        return names;
    }

    /** guides 撞车标注：finding 的问题/修正指引里出现了 guides 的关键词级
     *  重合（演示用包含关系近似），说明前馈已写过、反馈仍抓得到——缺口在
     *  「guides 没被遵守」，不在「没人知道规则」。 */
    private void annotateGuides(List<Finding> findings) {
        if (guidesText.isEmpty()) {
            return;
        }
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (guidesMention(f)) {
                findings.set(i, new Finding(f.rule(), f.severity(), f.location(),
                        f.problem(), f.fix(),
                        "项目 guides 已要求过这条（AGENTS.md 类前馈），"
                                + "sensor 仍发现违反——先查 guides 为什么没拦住，"
                                + "别只在产出上打补丁。"));
            }
        }
    }

    private boolean guidesMention(Finding f) {
        for (String kw : new String[] {f.rule(), f.problem(), f.fix()}) {
            for (String part : kw.split("[，。：:、（）\\s]+")) {
                if (part.length() >= 4 && guidesText.contains(part)) {
                    return true;
                }
            }
        }
        return false;
    }
}
