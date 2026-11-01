package com.example.harness.sensor;

import com.example.harness.RuleDistiller.RuleDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensor 发现台账：转向循环（steering loop）的原料留痕。
 *
 * <p>Fowler：人的工作是「每当一个问题出现多次，就改进前馈和反馈控制」。
 * 台账做这件事的地基——每条规则<b>触发过几次</b>都在账上：</p>
 * <ul>
 *   <li>{@link #repeatedFindings}：同一规则反复触发（≥ 阈值），说明
 *       前馈没拦住（guides 没写或没被遵守），蒸馏成规则草稿喂给人审
 *       ——复用 {@code RuleDistiller} 的提案制，不自动生效；</li>
 *   <li>{@link #silentSensors}：注册的 sensor 从未触发——Fowler 的开放
 *       问题「sensor 从不触发是质量好还是检测失灵」，台账不回答，只把
 *       两种可能摆出来：要么真干净，要么检查形同虚设，该写个已知违规
 *       的探针验证它还活着。</li>
 * </ul>
 */
public final class SensorLedger {

    /** 一条规则的出现记录：触发次数 + 最近一次的问题描述。 */
    private static final class Entry {
        int count;
        String lastProblem = "";
        String lastFix = "";
    }

    private final Map<String, Entry> byRule = new LinkedHashMap<>();
    private final Map<String, Integer> sensorRuns = new LinkedHashMap<>();
    private final List<String> sensorOrder = new ArrayList<>();

    /** 登记一批 sensor（从未触发的判断需要全量名单）。 */
    public void registerSensors(SensorSuite suite) {
        for (String name : suite.sensorNames()) {
            sensorOrder.add(name);
            sensorRuns.putIfAbsent(name, 0);
        }
    }

    /** 记一轮报告：sensor 各跑过一次，finding 按规则累计。 */
    public void record(SensorSuite.Report report) {
        report.triggered().forEach((name, n) ->
                sensorRuns.merge(name, n, Integer::sum));
        for (Finding f : report.findings()) {
            Entry e = byRule.computeIfAbsent(f.rule(), k -> new Entry());
            e.count++;
            e.lastProblem = f.problem();
            e.lastFix = f.fix();
        }
    }

    /** 触发 ≥ threshold 次的规则 → 转向循环的规则草稿（人审后合并）。 */
    public List<RuleDraft> repeatedFindings(int threshold) {
        List<RuleDraft> out = new ArrayList<>();
        byRule.forEach((rule, e) -> {
            if (e.count >= threshold) {
                out.add(new RuleDraft("sensor 反复触发",
                        rule + "反复出现 " + e.count + " 次（最近："
                                + e.lastProblem + "）。这条规则前馈没拦住——"
                                + "把「" + e.lastFix + "」写进项目 guides，"
                                + "或在产出流程里把对应检查提前。",
                        "sensor 台账，" + e.count + " 次触发记录"));
            }
        });
        return out;
    }

    /** 从未产生过 finding 的 sensor：要么真干净，要么检测失灵——分不清，
     *  但值得摆出来。 */
    public List<String> silentSensors() {
        List<String> out = new ArrayList<>();
        for (String name : sensorOrder) {
            if (!byRule.containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 台账现状一行话（报告用）。 */
    public String describe() {
        return "触发中的规则 " + byRule.size() + " 条，从未触发的 sensor "
                + silentSensors().size() + " 个";
    }
}
