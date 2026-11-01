package com.example.harness.sensor;

import com.example.agentloop.AgentLoop;
import com.example.agentloop.LlmActor;
import com.example.agentloop.LlmVerifier;
import com.example.agentloop.LoopResult;
import com.example.agentloop.SensorVerifier;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.util.List;

/**
 * Sensor 层演示：计算型/推断型分野 → 回流闭环 → 转向循环
 * （2026-10-31，Fowler/ThoughtWorks Harness 工程对标的落地）。
 *
 * <p>场景一「计算型感知」：占位符残留 / 改动行数红线 / 重复模式 /
 * 架构适应度对一份带毛病的「模型产出」各查一遍——毫秒级、确定性、
 * 每次跑得起；发现带修正指引（正向 prompt injection），guides 撞车
 * 原样标注。</p>
 *
 * <p>场景二「推断型 + 抽样」：LLM 语义评审显式标注贵，5 轮里只跑
 * 抽样间隔命中的轮次（quality left：贵的检查别每次提交都烧）。</p>
 *
 * <p>场景三「回流闭环」：接进 AgentLoop——初稿带 TODO，sensor 的
 * 修正指引随验收意见回流，二稿修掉收工。结构性问题不烧 LLM 验收
 * 的额度去碰运气。</p>
 *
 * <p>场景四「转向循环」：台账攒几轮发现，反复触发 ≥2 次的规则蒸馏成
 * guides 草稿（提案制），从未触发的 sensor 摆出来——质量好还是检测
 * 失灵，台账不替人回答。</p>
 *
 * <p>运行：{@code mvn exec:java@sensor}（剧本离线可跑）。</p>
 */
public class SensorDemo {

    private static final String GOAL = "交付物要过两条硬标准：没有 TODO 类占位符，正文达到任务要求。";

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Sensor 层：计算/推断分野 / 回流闭环 / 转向循环");
        System.out.println(line);

        boolean s1 = scenarioComputational();
        boolean s2 = scenarioInferentialSampling();
        boolean s3 = scenarioLoop();
        boolean s4 = scenarioSteering();

        System.out.println();
        System.out.println(line);
        System.out.println(s1 && s2 && s3 && s4
                ? "收尾：反馈和前馈对称了——guides 管行动前，sensors 管行动后，"
                        + "台账管下次行动更聪明。"
                : "!! sensor 层没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：计算型 sensor 对带毛病的产出各查一遍。 */
    private static boolean scenarioComputational() {
        header("场景一：计算型感知", "毫秒级、确定性、每次跑得起");
        String guides = "交付标准：占位符残留和 TODO 一律打回，产出里残留待办不交付。";
        SensorSuite suite = new SensorSuite(guides, 5)
                .add(new PlaceholderSensor())
                .add(new DiffSizeSensor())
                .add(new RepeatPatternSensor())
                .add(FitnessSensor.standard());
        CodeChange bad = new CodeChange("ReportService.java", """
                public class ReportService {
                    // TODO: 补异常处理
                    void export() {
                        validate(request);
                        validate(request);
                        validate(request);
                    }
                }
                """);
        SensorSuite.Report report = suite.run(bad, 1);
        System.out.println("  产出（ReportService.java）：带 TODO、三段重复、无 timeout/requestId/validate 声明");
        System.out.println("  发现 " + report.findings().size() + " 条：");
        report.findings().forEach(f -> {
            System.out.println("    [" + f.severity() + "] " + f.location()
                    + "（" + f.rule() + "）");
            System.out.println("      问题：" + f.problem());
            System.out.println("      怎么改：" + f.fix());
            if (!f.guidesNote().isEmpty()) {
                System.out.println("      ⚠ " + f.guidesNote());
            }
        });
        boolean clash = report.findings().stream().anyMatch(f -> f.guidesNote().contains("guides"));
        System.out.println("  guides 撞车标注：" + (clash ? "有（前馈已要求、反馈仍抓到）"
                : "无"));
        return report.findings().size() >= 4 && clash;
    }

    /** 场景二：推断型 sensor 抽样跑（5 轮只跑间隔命中的）。 */
    private static boolean scenarioInferentialSampling() {
        header("场景二：推断型 + 抽样", "贵的检查别每次提交都烧");
        ScriptedLlm reviewLlm = new ScriptedLlm()
                .says("FINDING: ReportService.java:3 | 语义重复：三处校验逻辑干的是同一件事 | 抽成一个 validate 方法，各处只传差异参数")
                .says("FINDING: ReportService.java:1 | 过度设计：类注释里规划了五个未来扩展点 | 只留当前用得到的，扩展点等需求真来了再加");
        SensorSuite suite = new SensorSuite("", 2)
                .add(new LlmReviewSensor(reviewLlm));
        CodeChange change = new CodeChange("ReportService.java", "void export() { validate(); }");
        System.out.println("  抽样间隔 = 2，跑 5 轮：");
        int ran = 0;
        for (int i = 1; i <= 5; i++) {
            SensorSuite.Report r = suite.run(change, i);
            boolean fired = r.triggered().containsKey("语义评审");
            ran += fired ? 1 : 0;
            System.out.println("    第 " + i + " 轮 → " + (fired
                    ? "跑了（发现 " + r.findings().size() + " 条）"
                    : "跳过（" + r.skipped() + "）"));
        }
        boolean ok = ran == 2; // 第 2、4 轮
        System.out.println("  5 轮实跑 " + ran + " 次：" + (ok ? "抽样生效" : "!! 次数不对"));
        return ok;
    }

    /** 场景三：接进 AgentLoop，修正指引回流下一轮。 */
    private static boolean scenarioLoop() {
        header("场景三：回流闭环", "结构性问题不烧 LLM 验收的额度");
        CountingLlm actorLlm = new CountingLlm(new ScriptedLlm()
                .says("初稿：本工具支持导出报表。\n// TODO: 补异常处理")
                .says("二稿：本工具支持导出报表，失败时回滚并记录日志。"));
        CountingLlm verifierLlm = new CountingLlm(new ScriptedLlm()
                .says("NO：内容方向对，但有交付标准未过")
                .says("YES：达标"));
        SensorSuite suite = new SensorSuite("", 5)
                .add(new PlaceholderSensor());
        var loop = new AgentLoop(
                new LlmActor(actorLlm),
                new SensorVerifier(new LlmVerifier(verifierLlm, GOAL),
                        suite, "Draft.txt"),
                4);
        LoopResult r = loop.run("给报表工具写一段产品说明");
        System.out.println("  第 1 轮验收意见（含 sensor 回流块）：");
        r.steps().get(0).verdict().reason().lines()
                .filter(l -> l.contains("sensor") || l.contains("怎么改"))
                .forEach(l -> System.out.println("    " + l.strip()));
        boolean fedBack = r.steps().get(0).verdict().reason().contains("怎么改");
        boolean clean = !r.finalOutput().contains("TODO");
        System.out.println("  " + (r.completed() ? "2 轮收工" : "!! 没收工")
                + "，末稿无占位符：" + clean
                + "，LLM 调用 " + (actorLlm.calls() + verifierLlm.calls()) + " 次");
        return r.completed() && fedBack && clean;
    }

    /** 场景四：台账攒报告 → 蒸馏草稿 + 从未触发清单。 */
    private static boolean scenarioSteering() {
        header("场景四：转向循环", "问题出现多次，就改进前馈和反馈");
        SensorSuite suite = new SensorSuite("", 5)
                .add(new PlaceholderSensor())
                .add(new DiffSizeSensor())
                .add(FitnessSensor.standard());
        SensorLedger ledger = new SensorLedger();
        ledger.registerSensors(suite);
        CodeChange bad = new CodeChange("A.java", "// TODO: 补测试\nvoid f() {}");
        for (int i = 1; i <= 3; i++) {
            ledger.record(suite.run(bad, i));
        }
        System.out.println("  台账：" + ledger.describe());
        List<com.example.harness.RuleDistiller.RuleDraft> drafts =
                ledger.repeatedFindings(2);
        System.out.println("  反复触发 ≥2 次 → " + drafts.size() + " 条规则草稿（提案制，人审合并）：");
        drafts.forEach(d -> {
            System.out.println("    [" + d.category() + "] " + d.rule());
            System.out.println("      证据：" + d.evidence());
        });
        List<String> silent = ledger.silentSensors();
        System.out.println("  从未触发的 sensor：" + (silent.isEmpty() ? "无"
                : silent) + "——要么真干净，要么检测失灵，值得拿已知违规探一针。");
        return !drafts.isEmpty() && silent.contains("改动行数红线");
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
