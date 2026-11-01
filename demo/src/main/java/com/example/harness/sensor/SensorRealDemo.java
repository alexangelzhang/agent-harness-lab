package com.example.harness.sensor;

import com.example.agentloop.AgentLoop;
import com.example.agentloop.LlmActor;
import com.example.agentloop.LlmVerifier;
import com.example.agentloop.LoopResult;
import com.example.agentloop.LoopStep;
import com.example.agentloop.SensorVerifier;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.KimiLlm;

import java.util.List;

/**
 * Sensor 反馈层真实闭环：真实模型行动 → sensor 拦结构性问题 →
 * 修正指引回流 → 收工（2026-10-31，{@code SensorDemo} 剧本版的
 * 真实对照组）。
 *
 * <p>剧本版证明「机制对」；这个 demo 证明「真实模型的产出喂进来，
 * 机制还对」——sensor 抓到的占位符是真实模型真实写下的，修正指引
 * 回流后真实模型真的照改了。</p>
 *
 * <p>三个场景（真实调用慢，{@code -Dexec.args="n"} 只跑第 n 个）：</p>
 * <ol>
 *   <li><b>真实拦截</b>：Kimi 当 actor 写发布说明（任务里确有数据
 *       没拿到的部分，看模型会不会留 TODO）；SensorVerifier 在岗，
 *       拦下就把「怎么改」回流；LLM 验收只判内容标准，结构性问题
 *       不烧它的额度；</li>
 *   <li><b>推断型真实评审</b>：LlmReviewSensor 用 Kimi 对一段
 *       过度设计的代码做语义评审，打印发现；</li>
 *   <li><b>真实攒台账</b>：把场景一每一轮的真实产出过一遍计算型
 *       sensor，逐轮记进 SensorLedger——真实运行里触发过谁、谁
 *       从没响过，一屏摆出来。</li>
 * </ol>
 *
 * <p>硬断言只校结构（收工、终稿无 TODO、sensor 在岗），模型措辞
 * 不断言——真实输出有波动，机制行为必须稳定。</p>
 *
 * <p>运行：{@code mvn exec:java@sensorreal -Dexec.args="[1-3]"}，
 * 需要 {@code KIMI_API_KEY}，缺了明确报错退出。</p>
 */
public class SensorRealDemo {

    private static final String TASK = """
            给内部运维平台「夜鹰」写一段 80 字以内的发布说明，提到三个功能点：
            告警聚合、一键止血、周报自动生成。其中「一键止血」的生效数据还没
            拿到，先用 TODO 标出来提醒自己回头补，其余照常写。
            直接输出说明正文，不要标题和解释。""";
    private static final String GOAL = "交付标准：1) 三个功能点都出现；"
            + "2) 80 字以内；3) 数据没到位的部分明确标注待补充，但正文中"
            + "不许出现 TODO 字样。";

    public static void main(String[] args) {
        if (System.getenv("KIMI_API_KEY") == null) {
            System.err.println("缺少 KIMI_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("Sensor 反馈层真实闭环：真实拦截 / 推断评审 / 真实台账");
        System.out.println("模式：真实模型（KimiLlm）" + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        boolean ok = true;
        if (pick == 0 || pick == 1) {
            ok &= scenarioRealIntercept();
        }
        if (pick == 0 || pick == 2) {
            ok &= scenarioRealReview();
        }
        if (pick == 0 || pick == 3) {
            ok &= scenarioRealLedger();
        }

        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：真实产出喂进来，sensor 照旧在岗——结构性问题"
                        + "不烧 LLM 验收的额度。"
                : "!! 真实闭环结构校验没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：真实模型行动，sensor 拦 TODO，修正指引回流。 */
    private static boolean scenarioRealIntercept() {
        header("场景一：真实拦截", "TODO 是模型自己写的，改也是它自己改的");
        CountingLlm actorLlm = new CountingLlm(new KimiLlm());
        CountingLlm verifierLlm = new CountingLlm(new KimiLlm());
        SensorSuite suite = new SensorSuite("", 5)
                .add(new PlaceholderSensor())
                .add(new DiffSizeSensor("说明长度红线", 12));
        var loop = new AgentLoop(
                new LlmActor(actorLlm),
                new SensorVerifier(
                        new LlmVerifier(verifierLlm, GOAL), suite, "release-note.txt"),
                4);
        System.out.println("  任务里有数据没拿到的部分，看模型留不留 TODO。");
        LoopResult r = loop.run(TASK);
        for (LoopStep s : r.steps()) {
            boolean sensorFired = s.verdict().reason().contains("sensor 发现");
            System.out.println("  第 " + s.turn() + " 轮："
                    + (sensorFired ? "sensor 拦下（修正指引已回流）" : "sensor 未触发")
                    + " → " + s.verdict().reason().lines().findFirst().orElse(""));
        }
        System.out.println("  终稿：「" + r.finalOutput() + "」");
        boolean clean = !r.finalOutput().toUpperCase(java.util.Locale.ROOT)
                .contains("TODO");
        System.out.println("  " + (r.completed() ? r.turns() + " 轮收工" : "!! 轮数耗尽")
                + "，终稿无 TODO：" + clean
                + "，LLM 调用 " + (actorLlm.calls() + verifierLlm.calls()) + " 次");
        return r.completed() && clean;
    }

    /** 场景二：推断型 sensor 用真实模型做语义评审。 */
    private static boolean scenarioRealReview() {
        header("场景二：推断型真实评审", "语义重复/过度设计只有它能碰");
        LlmReviewSensor review = new LlmReviewSensor(new KimiLlm());
        CodeChange change = new CodeChange("OrderService.java", """
                public class OrderService {
                    public double priceA(int n) { return n > 10 ? n * 0.9 : n; }
                    public double priceB(int n) { return n > 10 ? n * 0.9 : n; }
                    public double priceC(int n) { return n > 10 ? n * 0.9 : n; }
                    // 预留：未来支持阶梯价、会员价、动态定价、跨境税率、积分抵扣
                    public void placeholder1() { }
                    public void placeholder2() { }
                    public void placeholder3() { }
                }
                """);
        List<Finding> findings = review.check(change);
        System.out.println("  待审代码：三段一模一样的价格计算 + 五个预留占位方法。");
        System.out.println("  Kimi 语义评审发现 " + findings.size() + " 条：");
        findings.forEach(f -> {
            System.out.println("    [" + f.severity() + "] " + f.location());
            System.out.println("      问题：" + f.problem());
            System.out.println("      怎么改：" + f.fix());
        });
        System.out.println("  " + (!findings.isEmpty()
                ? "推断型信号结构化成功——贵，但抓到了计算型抓不稳的。"
                : "（这轮模型认为没问题——推断型有波动，标注为 HINT 不当硬阻断。）"));
        return true; // 推断型有波动，只展示不硬断言
    }

    /** 场景三：场景一的真实产出逐轮过计算型 sensor，攒真实台账。 */
    private static boolean scenarioRealLedger() {
        header("场景三：真实攒台账", "真实运行里谁响过、谁没响过");
        CountingLlm actorLlm = new CountingLlm(new KimiLlm());
        CountingLlm verifierLlm = new CountingLlm(new KimiLlm());
        SensorSuite suite = new SensorSuite("", 5)
                .add(new PlaceholderSensor())
                .add(new DiffSizeSensor("说明长度红线", 12))
                .add(new RepeatPatternSensor("重复用词"));
        SensorLedger ledger = new SensorLedger();
        ledger.registerSensors(suite);
        var loop = new AgentLoop(
                new LlmActor(actorLlm),
                new SensorVerifier(
                        new LlmVerifier(verifierLlm, GOAL), suite, "release-note.txt"),
                4);
        LoopResult r = loop.run(TASK);
        for (LoopStep s : r.steps()) {
            ledger.record(suite.run(new CodeChange("release-note-" + s.turn() + ".txt",
                    s.output()), s.turn()));
        }
        System.out.println("  台账：" + ledger.describe());
        List<com.example.harness.RuleDistiller.RuleDraft> drafts =
                ledger.repeatedFindings(1);
        System.out.println("  触发过的规则 → guides 草稿候选：");
        drafts.forEach(d -> System.out.println("    [" + d.category() + "] " + d.rule()));
        List<String> silent = ledger.silentSensors();
        System.out.println("  从未触发的 sensor：" + (silent.isEmpty() ? "无" : silent)
                + "——要么真干净，要么检测失灵，拿已知违规探一针才知道。");
        return r.completed();
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
