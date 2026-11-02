package com.example.multiagent;

import com.example.harness.RunRecord;
import com.example.harness.RunStore;
import com.example.harness.toolbase.SchemaTool;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.DelegationGuard.Config;
import com.example.multiagent.HierarchicalPattern.Level;
import com.example.multiagent.SupervisorPattern.Expert;
import com.example.multiagent.SwarmPattern.Specialist;
import com.example.multiagent.TopologyAdvisor.Recommendation;
import com.example.multiagent.TopologyAdvisor.TaskProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 多 Agent 四种拓扑 + 委派护栏 + 拓扑决策器 综合演示。
 *
 * <p>对标《多 Agent 不是单 Agent 的升级版》（智能体AI）：
 * 五幕——①决策器给四种任务画像各出推荐（含写密集劝退）；
 * ②Workflow 报表流水线；③Supervisor 两种结构（平级分派 vs 专家做成工具）；
 * ④Hierarchical 两层正常下派 + 故意超深被护栏拦；⑤Swarm 客服接力 +
 * 循环交接被护栏掐断。全部 ScriptedLlm 离线可跑。</p>
 *
 * <p>每次分派/交接都经 {@link RunJournal} 落进 {@code runs/runs.jsonl}——
 * 与单体 loop / 状态图 / 动态工作流同一张审计底账，GUARD_BLOCKED 是
 * 显式终态不是异常；收尾打印本次运行按 runner/终态的汇总。</p>
 *
 * <p>运行：{@code mvn exec:java@topology}。</p>
 */
public class TopologyScenario {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    private static int bad;

    public static void main(String[] args) {
        header("多 Agent 四种拓扑：Workflow / Supervisor / Hierarchical / Swarm",
                "同一套问题，四种组织形式——选错的代价是 N 倍延迟和成本");
        long startedAt = System.currentTimeMillis();

        act1Advisor();
        act2Workflow();
        act3Supervisor();
        act4Hierarchical();
        act5Swarm();
        auditSummary(startedAt);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：四种拓扑各归其位，护栏在超深委派和循环交接时准时拦下——"
                    + "多 Agent 是组织形式的选择题，不是单 Agent 的升级版。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 第一幕：拓扑决策器 -------------------------------------------------

    private static void act1Advisor() {
        header("第一幕 · 拓扑决策器：四种画像各归哪个拓扑",
                "决策树：工序固定→Workflow / 中央协调→Supervisor / 分层→Hierarchical / 漂移→Swarm / 默认不拆");

        record Case(String label, TaskProfile profile, String expect) {
        }
        List<Case> cases = List.of(
                new Case("月度报表流水线", new TaskProfile(true, false, false, false, 1, 1),
                        "Workflow"),
                new Case("跨部门项目统筹", new TaskProfile(false, true, false, false, 3, 0),
                        "Supervisor"),
                new Case("软件交付组织", new TaskProfile(false, false, true, false, 2, 0),
                        "Hierarchical"),
                new Case("客服转接对话", new TaskProfile(false, false, false, true, 1, 0),
                        "Swarm"),
                new Case("多人共写一份文档", new TaskProfile(false, false, false, false, 2, 2),
                        "单 Agent"));
        RunJournal journal = new RunJournal();
        for (Case c : cases) {
            Recommendation r = TopologyAdvisor.recommend(c.profile());
            journal.trace().accept(c.label(), "推荐 " + r.name() + "：" + r.reason());
            System.out.println("  " + c.label());
            System.out.println("    画像：固定工序=" + c.profile().fixedPipeline()
                    + " 中央协调=" + c.profile().centralCoordination()
                    + " 分层=" + c.profile().needsLayering()
                    + " 焦点漂移=" + c.profile().focusDrift()
                    + " 读源=" + c.profile().independentReadSources()
                    + " 共享写出=" + c.profile().sharedWriteTargets());
            System.out.println("    推荐 → " + r.name() + "（" + (r.staySingle() ? "保持单 Agent" : "拆分")
                    + "）");
            System.out.println("    理由 → " + r.reason());
            check("决策器「" + c.label() + "」推荐 " + c.expect(),
                    r.name().equals(c.expect()));
        }
        archive(journal, "scenario-topology-advisor", "multiagent:advisor",
                "四种任务画像的拓扑选型", "五例推荐完成", 0, 1);
    }

    // ---- 第二幕：Workflow ---------------------------------------------------

    private static void act2Workflow() {
        header("第二幕 · Workflow：控制流写死，模型只管工序内容",
                "报表流水线：收集 → 分析 → 排版(确定性) → 校对");

        CountingLlm brains = new CountingLlm(new ScriptedLlm().says(
                "已收集 3 个数据源，共 1200 行原始数据",
                "分析结论：Q3 增长 18%，风险点在供应链交期",
                "校对完成：修正 2 处单位错误，数字与底稿一致"));
        List<WorkflowPattern.Step> steps = List.of(
                new WorkflowPattern.Step("数据收集",
                        artifact -> brains.complete("收集数据。上游：" + artifact)),
                new WorkflowPattern.Step("分析",
                        artifact -> brains.complete("分析。上游：" + artifact)),
                new WorkflowPattern.Step("排版",
                        artifact -> artifact + "\n【排版 v1】图表 2 张，表格 1 张"),
                new WorkflowPattern.Step("校对",
                        artifact -> brains.complete("校对。上游：" + artifact)));
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String report = WorkflowPattern.run("出一份 Q3 经营报表", steps, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  最终产物 → " + report);
        check("Workflow 流水线走完四道序", report.contains("校对完成"));
        archive(journal, "scenario-topology-workflow", "multiagent:workflow",
                "出一份 Q3 经营报表", report, brains.calls(), ms);
    }

    // ---- 第三幕：Supervisor（两种结构） --------------------------------------

    private static void act3Supervisor() {
        header("第三幕 · Supervisor：中央协调拿总——平级分派 vs 专家做成工具",
                "同一个任务，两种结构：专家是「下属」还是「工具」");

        System.out.println("  —— 结构 A：专家是平级 Agent，主管输出「分派：」行 ——");
        List<Expert> rosterA = List.of(
                new Expert("技术专家", "技术债与依赖", new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new Expert("法务专家", "合规风险", new ScriptedLlm().says("发现 2 项合规风险，需在两周内整改")),
                new Expert("市场专家", "市场反应", new ScriptedLlm().says("竞品下周发布同类功能，窗口期紧")));
        LlmClient bossA = new ScriptedLlm().says(
                "分派：技术专家 | 梳理技术债和依赖\n分派：法务专家 | 检查合规风险\n分派：市场专家 | 评估市场反应");
        LlmClient synthA = new ScriptedLlm().says("综合三方意见：先换掉停止维护的依赖，合规两周内整改，趁竞品发布前上线");
        RunJournal journalA = new RunJournal();
        long t0 = System.nanoTime();
        String outA = SupervisorPattern.dispatch("给新上线产品做风险评估", rosterA, bossA, synthA, trace(journalA));
        long msA = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  综合答复 → " + outA);
        check("Supervisor 平级分派收拢三方结果", outA.contains("综合三方意见"));
        archive(journalA, "scenario-topology-supervisor-dispatch", "multiagent:supervisor",
                "给新上线产品做风险评估（平级分派）", outA, dispatchCalls(journalA, "分派→") + 2, msA);

        System.out.println();
        System.out.println("  —— 结构 B：Supervisor-as-Tools，专家被包成主管的工具 ——");
        List<Expert> rosterB = List.of(
                new Expert("技术专家", "技术债与依赖", new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new Expert("法务专家", "合规风险", new ScriptedLlm().says("发现 2 项合规风险，需在两周内整改")));
        List<SchemaTool.Param> params = List.of(
                new SchemaTool.Param("问题", "string", true, "要问专家的具体问题"));
        List<SchemaTool> tools = rosterB.stream()
                .map(e -> SupervisorPattern.asTool(e, params))
                .toList();
        System.out.println("  工具契约 → " + tools.get(0).schemaManifest().replace("\n", " / "));
        LlmClient bossB = new ScriptedLlm()
                .says("CALL 技术专家：依赖梳理\nCALL 法务专家：合规检查");
        LlmClient synthB = new ScriptedLlm().says("综合：技术债优先处理，合规跟进整改");
        RunJournal journalB = new RunJournal();
        long t1 = System.nanoTime();
        String outB = SupervisorPattern.dispatchViaTools("给新上线产品做风险评估", tools, bossB, synthB, trace(journalB));
        long msB = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("  综合答复 → " + outB);
        check("Supervisor-as-Tools 结构走通", outB.contains("技术债优先"));
        archive(journalB, "scenario-topology-supervisor-tools", "multiagent:supervisor",
                "给新上线产品做风险评估（专家工具化）", outB, dispatchCalls(journalB, "CALL ") + 2, msB);
    }

    // ---- 第四幕：Hierarchical ----------------------------------------------

    private static void act4Hierarchical() {
        header("第四幕 · Hierarchical：层层下派，委派每跳都过护栏",
                "正常 2 跳交付 vs 故意套 4 层被 max-depth 拦下");

        System.out.println("  —— 正常路径：交付总监 → 研发组长 → 工程师 ——");
        DelegationGuard guard = new DelegationGuard(new Config(3, 10), "交付总监");
        CountingLlm dir = new CountingLlm(new ScriptedLlm().says("委派：实现登录模块"));
        CountingLlm lead = new CountingLlm(new ScriptedLlm().says("委派：写登录接口，带限流"));
        CountingLlm eng = new CountingLlm(new ScriptedLlm().says("完成：登录接口已交付，含限流"));
        List<Level> levels = List.of(
                new Level("交付总监", t -> dir.complete(t)),
                new Level("研发组长", t -> lead.complete(t)),
                new Level("工程师", t -> eng.complete(t)));
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String done = HierarchicalPattern.run("交付登录功能", levels, guard, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  交付结果 → " + done + "（共 " + guard.handoffs() + " 跳）");
        check("Hierarchical 两层下派后完成", done.contains("登录接口已交付"));
        check("护栏记录 2 跳", guard.handoffs() == 2);
        archive(journal, "scenario-topology-hierarchical", "multiagent:hierarchical",
                "交付登录功能（正常 2 跳）", done, dir.calls() + lead.calls() + eng.calls(), ms);

        System.out.println();
        System.out.println("  —— 越界路径：5 层套娃，max-depth=3 ——");
        DelegationGuard tight = new DelegationGuard(new Config(3, 10), "总监");
        CountingLlm d1 = new CountingLlm(new ScriptedLlm().says("委派：任务A"));
        CountingLlm d2 = new CountingLlm(new ScriptedLlm().says("委派：任务B"));
        CountingLlm d3 = new CountingLlm(new ScriptedLlm().says("委派：任务C"));
        List<Level> deep = List.of(
                new Level("总监", t -> d1.complete(t)),
                new Level("组长一", t -> d2.complete(t)),
                new Level("组长二", t -> d3.complete(t)),
                new Level("工程师一", t -> new ScriptedLlm().says("完成：做完了").complete(t)),
                new Level("工程师二", t -> new ScriptedLlm().says("完成：做完了").complete(t)));
        RunJournal journalDeep = new RunJournal();
        long t1 = System.nanoTime();
        String blocked = HierarchicalPattern.run("交付登录功能", deep, tight, trace(journalDeep));
        long msDeep = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("  拦截结果 → " + blocked);
        check("超深委派被 max-depth 拦下", blocked.contains("护栏拦截"));
        archive(journalDeep, "scenario-topology-hierarchical-deep", "multiagent:hierarchical",
                "交付登录功能（5 层套娃越界）", blocked,
                d1.calls() + d2.calls() + d3.calls(), msDeep);
    }

    // ---- 第五幕：Swarm -------------------------------------------------------

    private static void act5Swarm() {
        header("第五幕 · Swarm：去中心化接力，焦点漂移就交棒",
                "客服转接 物流→退款→财务；循环交接 A→B→A 被护栏掐断");

        System.out.println("  —— 正常接力：包裹异常 → 退款 → 财务打款 ——");
        List<Specialist> roster = List.of(
                new Specialist("接入专员", "接待与分流", new ScriptedLlm()
                        .says("您好，您的包裹三天没有物流更新，我帮您查一下")),
                new Specialist("物流专员", "查件与理赔", new ScriptedLlm()
                        .says("核实了，件在转运中心丢失，建议直接给您办理退款")),
                new Specialist("退款专员", "退款流程", new ScriptedLlm()
                        .says("这笔需要财务同事打款，我帮您转过去")),
                new Specialist("财务专员", "打款到账", new ScriptedLlm()
                        .says("已为您原路退回，请留意查收银行卡")));
        var router = SwarmPattern.keywordRouter(Map.of(
                "物流专员", List.of("物流", "快递", "包裹"),
                "退款专员", List.of("退款"),
                "财务专员", List.of("财务", "打款")));
        DelegationGuard guard = new DelegationGuard(new Config(4, 5), "接入专员");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String resolved = SwarmPattern.run("我的包裹不动了", roster, router, guard, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  终局答复 → " + resolved + "（共 " + guard.handoffs() + " 跳）");
        check("Swarm 三次接力后收工", guard.handoffs() == 3 && resolved.contains("查收"));
        archive(journal, "scenario-topology-swarm", "multiagent:swarm",
                "我的包裹不动了（客服 3 跳接力）", resolved, guard.handoffs() + 1, ms);

        System.out.println();
        System.out.println("  —— 死循环：专员A ↔ 专员B 互踢皮球 ——");
        List<Specialist> duo = List.of(
                new Specialist("专员A", "一线", new ScriptedLlm()
                        .says("这个问题我处理不了，需要专员B看看")),
                new Specialist("专员B", "二线", new ScriptedLlm()
                        .says("这个我也定不了，还是得专员A来")));
        var loopRouter = SwarmPattern.keywordRouter(Map.of(
                "专员A", List.of("专员A"),
                "专员B", List.of("专员B")));
        DelegationGuard loopGuard = new DelegationGuard(new Config(4, 5), "专员A");
        RunJournal journalLoop = new RunJournal();
        long t1 = System.nanoTime();
        String stopped = SwarmPattern.run("客户投诉升级", duo, loopRouter, loopGuard, trace(journalLoop));
        long msLoop = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("  拦截结果 → " + stopped);
        check("循环交接被护栏掐断", stopped.contains("绕圈"));
        archive(journalLoop, "scenario-topology-swarm-loop", "multiagent:swarm",
                "客户投诉升级（A↔B 循环交接）", stopped, loopGuard.handoffs() + 1, msLoop);
    }

    // ---- 小工具 --------------------------------------------------------------

    private static void header(String title, String subtitle) {
        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        System.out.println(title);
        System.out.println("  " + subtitle);
        System.out.println(line);
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }

    /** 既打印又留档的 trace：一份进屏幕，一份进 RunJournal。 */
    private static BiConsumer<String, String> trace(RunJournal journal) {
        return (who, what) -> {
            System.out.println("    [" + who + "] " + what);
            journal.trace().accept(who, what);
        };
    }

    /** 分派/工具调用次数：journal 里 who 带前缀的步数就是专家调用数。 */
    private static int dispatchCalls(RunJournal journal, String whoPrefix) {
        return (int) journal.steps().stream()
                .filter(s -> s.reason().startsWith(whoPrefix))
                .count();
    }

    /** 落成档案追加进 runs/runs.jsonl（同一张底账，不清空），打印回执。 */
    private static void archive(RunJournal journal, String runId, String runner,
                                String task, String finalAnswer, int llmCalls, long durationMs) {
        RunRecord record = journal.toRecord(runId, runner, task, finalAnswer, llmCalls, durationMs);
        try {
            Path file = STORE.append(record);
            System.out.println("  [落盘] " + runId + " → 终态 " + record.terminal()
                    + "，" + record.steps().size() + " 步，LLM " + llmCalls
                    + " 次 → " + file);
        } catch (java.io.IOException e) {
            bad++;
            System.out.println("  !! 档案落盘失败：" + e.getMessage());
        }
    }

    /** 审计汇总：本次运行落账的多 Agent 记录，按 runner/终态分。 */
    private static void auditSummary(long startedAt) {
        try {
            List<RunRecord> mine = STORE.readAll().stream()
                    .filter(r -> r.runner().startsWith("multiagent:") && r.timestamp() >= startedAt)
                    .toList();
            System.out.println();
            System.out.println("── 审计汇总（本次落账 " + mine.size() + " 条，读自 "
                    + STORE.file() + "）──");
            System.out.printf("  %-34s %-14s %-6s %-8s %s%n",
                    "runId", "终态", "步数", "LLM调用", "runner");
            System.out.println("  " + "-".repeat(76));
            for (RunRecord r : mine) {
                System.out.printf("  %-34s %-14s %-6d %-8d %s%n",
                        r.runId(), r.terminal(), r.steps().size(), r.llmCalls(), r.runner());
            }
            long blocked = mine.stream().filter(r -> r.terminal().equals("GUARD_BLOCKED")).count();
            System.out.println("  GUARD_BLOCKED " + blocked + " 条——护栏拦截是显式终态，"
                    + "审计里和跑完平起平坐，都能按 runId 回放。");
        } catch (java.io.IOException e) {
            bad++;
            System.out.println("  !! 审计汇总读档失败：" + e.getMessage());
        }
    }
}
