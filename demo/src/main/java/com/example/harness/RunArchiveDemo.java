package com.example.harness;

import com.example.agentloop.AgentLoop;
import com.example.agentloop.LlmActor;
import com.example.agentloop.LlmVerifier;
import com.example.agentloop.LoopResult;
import com.example.langgraph4j.eval.TrajectoryCollector;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.EnvException;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.ReflectionGraph;
import com.example.multiagent.DelegationGuard;
import com.example.multiagent.HierarchicalPattern;
import com.example.multiagent.RunJournal;
import com.example.multiagent.SupervisorPattern;
import com.example.multiagent.SwarmPattern;
import com.example.workflow.Workflow;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 运行档案演示：同一批任务跑出四种终态（达标 / 环境故障 / 预算耗尽 / 图版放行），
 * 全部落盘 JSONL，再读回做审计汇总 + 单条轨迹回放。
 *
 * <p>底账里同时落进多 Agent 拓扑的三种运行（Supervisor 分派 / Swarm 接力 /
 * Hierarchical 超深被拦）——四种编排载体（原语 loop / 状态图 / 动态工作流 /
 * 多 Agent 拓扑）同一张底账，跨载体按 runner 分终态分布与平均成本。</p>
 *
 * <p>对应企业可观测层的底线要求：每次运行留底（runId、终态、轨迹、
 * 调用数、耗时），出了事能翻账，攒多了能看趋势。运行入口：
 * {@code mvn exec:java@archive}。</p>
 */
public class RunArchiveDemo {

    private static final String TASK = "为一款冷萃咖啡液写一句广告 slogan";
    private static final String GOAL =
            "广告词要同时让人看出两点：一是低温萃取，二是花了时间慢慢萃。换个说法也算。";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(66));
        System.out.println("  运行档案 —— 落盘 JSONL、读回审计、轨迹回放");
        System.out.println("=".repeat(66));

        RunStore store = new RunStore(Path.of("runs", "runs.jsonl"));
        store.clear();

        // ── 跑 10 次：loop 三种终态 + 图版两种 + 动态工作流两种 + 多 Agent 三种 ──
        runLoopSuccess(store);
        runLoopEnvAbort(store);
        runLoopBudget(store);
        runGraphAccept(store);
        runGraphBudget(store);
        runWorkflowDone(store);
        runWorkflowBudget(store);
        runMaSupervisor(store);
        runMaSwarm(store);
        runMaHierarchicalBlocked(store);

        // ── 读回：审计汇总（按终态分组）──
        List<RunRecord> all = store.readAll();
        System.out.println("\n── 审计汇总（读自 " + store.file() + "，共 " + all.size() + " 条）──");
        System.out.printf("  %-18s %-6s %-10s %-10s %s%n", "终态", "次数", "LLM调用", "平均耗时", "runId");
        System.out.println("  " + "-".repeat(60));
        Map<String, List<RunRecord>> byTerminal = all.stream()
                .collect(Collectors.groupingBy(RunRecord::terminal, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<RunRecord>> e : byTerminal.entrySet()) {
            List<RunRecord> rs = e.getValue();
            long avgMs = rs.stream().mapToLong(RunRecord::durationMs).sum() / rs.size();
            int calls = rs.stream().mapToInt(RunRecord::llmCalls).sum();
            String ids = rs.stream().map(RunRecord::runId).collect(Collectors.joining(", "));
            System.out.printf("  %-18s %-6d %-10d %-10d %s%n", e.getKey(), rs.size(), calls, avgMs, ids);
        }

        // ── 跨载体汇总：四种编排载体（原语 loop / 状态图 / 动态工作流 / 多 Agent）同一张底账 ──
        System.out.println("\n── 跨载体汇总（按 runner 分组）──");
        System.out.printf("  %-24s %-6s %-26s %-10s %-10s %s%n",
                "载体", "次数", "终态分布", "平均调用", "平均耗时ms", "runId");
        System.out.println("  " + "-".repeat(84));
        Map<String, List<RunRecord>> byRunner = all.stream()
                .collect(Collectors.groupingBy(RunRecord::runner, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<RunRecord>> e : byRunner.entrySet()) {
            List<RunRecord> rs = e.getValue();
            String terminals = rs.stream().collect(Collectors.groupingBy(RunRecord::terminal,
                            TreeMap::new, Collectors.counting())).entrySet().stream()
                    .map(t -> t.getKey() + "×" + t.getValue()).collect(Collectors.joining(", "));
            long avgCalls = rs.stream().mapToInt(RunRecord::llmCalls).sum() / rs.size();
            long avgMs = rs.stream().mapToLong(RunRecord::durationMs).sum() / rs.size();
            String ids = rs.stream().map(RunRecord::runId).collect(Collectors.joining(", "));
            System.out.printf("  %-24s %-6d %-26s %-10d %-10d %s%n",
                    e.getKey(), rs.size(), terminals, avgCalls, avgMs, ids);
        }

        // ── 回放：挑一条预算耗尽的 loop 运行，逐步重演 ──
        Optional<RunRecord> pick = store.find("demo-loop-budget");
        if (pick.isPresent()) {
            RunRecord r = pick.get();
            System.out.println("\n── 轨迹回放：" + r.runId() + "（" + r.runner()
                    + "，终态 " + r.terminal() + "，" + r.steps().size() + " 步）──");
            for (RunRecord.Step s : r.steps()) {
                System.out.printf("  [第 %d 步 %dms] %s%n", s.turn(), s.durationMs(),
                        s.output().isEmpty() ? "（无产出）" : s.output());
                System.out.println("    " + s.verdictKind() + (s.reason().isEmpty() ? "" : "：" + s.reason()));
            }
        }

        System.out.println("\n  结论：五种终态显式落盘、按 runId 可定位——审计底账成立。");
        System.out.println("  四种编排载体（原语 loop / 状态图 / 动态工作流 / 多 Agent 拓扑）");
        System.out.println("  的运行落同一张底账，跨载体按 runner 可分终端态分布与平均成本，");
        System.out.println("  选型对比有据可查——多 Agent 的 GUARD_BLOCKED 与单体的");
        System.out.println("  BUDGET_EXHAUSTED 一样，都是账上的一等公民。");
        System.out.println("  下一步接外循环：按终态统计失败模式，沉淀成规则（cross-task 学习）。");
        System.exit(0);
    }

    /** loop 达标：两轮批改后通过。 */
    private static void runLoopSuccess(RunStore store) throws Exception {
        var actorLlm = new CountingLlm(new ScriptedLlm()
                .says("一口下去，精神百倍，加班不犯困！")
                .says("十二小时低温慢萃，把时间的味道融进每一滴。"));
        var verifierLlm = new CountingLlm(new ScriptedLlm()
                .says("NO：没有体现低温萃取和慢萃").says("YES"));
        long t0 = System.nanoTime();
        LoopResult r = new AgentLoop(new LlmActor(actorLlm),
                new LlmVerifier(verifierLlm, GOAL), 4).run(TASK);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(RunRecord.ofLoop("demo-loop-success", TASK, r,
                actorLlm.calls() + verifierLlm.calls(), ms));
        System.out.println("  [落盘] demo-loop-success → " + r.stopReason() + " → " + f);
    }

    /** loop 环境故障超限：网关反复超时，ENV_ABORTED。 */
    private static void runLoopEnvAbort(RunStore store) throws Exception {
        var actorLlm = new CountingLlm(new ScriptedLlm()
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                })
                .next(p -> {
                    throw new EnvException("LLM 网关超时");
                }));
        var verifierLlm = new CountingLlm(new ScriptedLlm().says("YES"));
        long t0 = System.nanoTime();
        // 上限 2 次：两次超时即终止
        LoopResult r = new AgentLoop(new LlmActor(actorLlm),
                new LlmVerifier(verifierLlm, GOAL), null, 3, 2).run(TASK);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(RunRecord.ofLoop("demo-loop-env", TASK, r,
                actorLlm.calls() + verifierLlm.calls(), ms));
        System.out.println("  [落盘] demo-loop-env → " + r.stopReason() + " → " + f);
    }

    /** loop 预算耗尽：验收永远 NO，预算 4 次调用见底转人工。 */
    private static void runLoopBudget(RunStore store) throws Exception {
        var shared = new CountingLlm(new ScriptedLlm()
                .says("一口下去，精神百倍，加班不犯困！")
                .says("NO：没有体现低温萃取")
                .says("低温冷萃，锁住咖啡本味。")
                .says("NO：没有体现慢慢萃")
                .says("永远过不了的稿子")
                .says("NO：永远不行"));
        long t0 = System.nanoTime();
        LoopResult r = new AgentLoop(new LlmActor(shared),
                new LlmVerifier(shared, GOAL), 10, Budget.calls(shared::calls, 4)).run(TASK);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(RunRecord.ofLoop("demo-loop-budget", TASK, r,
                shared.calls(), ms));
        System.out.println("  [落盘] demo-loop-budget → " + r.stopReason() + " → " + f);
    }

    /** 图版达标：reflector 批一轮后 ACCEPT。 */
    private static void runGraphAccept(RunStore store) throws Exception {
        var llm = new CountingLlm(new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：没有体现低温慢萃。评分 6/10。")
                .says("十二小时低温慢萃，唤醒每一刻。")
                .says("语言精炼、卖点清晰。ACCEPT"));
        var graph = new ReflectionGraph(llm, 5).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        long t0 = System.nanoTime();
        var raw = new TrajectoryCollector<ReflectionGraph.ReflectionState>(graph).run(
                Map.of("task", TASK), RunnableConfig.builder().threadId("archive-graph-ok").build());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String verdict = raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse("");
        Path f = store.append(RunRecord.ofGraph("demo-graph-accept", TASK,
                raw.nodePath(), verdict, raw.errorMessage(), llm.calls(), ms));
        System.out.println("  [落盘] demo-graph-accept → 终裁=" + verdict + " → " + f);
    }

    /** 图版预算耗尽：评审一直不通过，4 次调用见底 BUDGET_EXHAUSTED。 */
    private static void runGraphBudget(RunStore store) throws Exception {
        var llm = new CountingLlm(new ScriptedLlm()
                .says("冷萃咖啡液，一口清醒一整天。")
                .says("建议：没有体现低温慢萃。评分 5/10。")
                .says("十二小时低温慢萃，唤醒每一刻。")
                .says("建议：还是差点意思。评分 6/10。")
                .says("继续修改的稿子")
                .says("建议：继续不行"));
        var graph = new ReflectionGraph(llm, 10, null,
                ReflectionGraph.DEFAULT_MAX_ENV_FAILURES, Budget.calls(llm::calls, 4))
                .build().compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        long t0 = System.nanoTime();
        var raw = new TrajectoryCollector<ReflectionGraph.ReflectionState>(graph).run(
                Map.of("task", TASK), RunnableConfig.builder().threadId("archive-graph-budget").build());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String verdict = raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse("");
        Path f = store.append(RunRecord.ofGraph("demo-graph-budget", TASK,
                raw.nodePath(), verdict, raw.errorMessage(), llm.calls(), ms));
        System.out.println("  [落盘] demo-graph-budget → 终裁=" + verdict + " → " + f);
    }

    /** 动态工作流达标：分类并执行（分类器 → 文案专员），两个干净上下文。 */
    private static void runWorkflowDone(RunStore store) throws Exception {
        var llm = new CountingLlm(new ScriptedLlm()
                .says("类型=文案")
                .says("文案成稿：早上赶时间，撕开一袋，三十秒喝到一杯正经冷萃。"));
        Workflow wf = new Workflow(llm);
        long t0 = System.nanoTime();
        String type = wf.agent("分类器", "判断任务类型（文案/代码/数据/研究），只回类型：" + TASK);
        String output = type.contains("文案")
                ? wf.agent("文案专员", "你是文案专员，写一句推广语：" + TASK)
                : wf.agent("通用处理", "处理任务：" + TASK);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(workflowRecord("demo-workflow-done", TASK, wf, llm, "DONE", ms));
        System.out.println("  [落盘] demo-workflow-done → " + output + " → " + f);
    }

    /** 动态工作流预算耗尽：审查通过但修复超预算，BUDGET_EXHAUSTED。 */
    private static void runWorkflowBudget(RunStore store) throws Exception {
        var llm = new CountingLlm(new ScriptedLlm()
                .says("发现2处：预算没写上限，负责人没定人。")
                .says("修订版：预算上限 5 万，负责人张三。"));
        // 预算 1 次调用：审查完即见底，修复起不来
        Workflow wf = new Workflow(llm, Budget.calls(llm::calls, 1));
        long t0 = System.nanoTime();
        String terminal = "DONE";
        try {
            wf.agent("审查员", "审查这份方案还有没有问题：促销活动方案 v1");
            wf.agent("修复员", "把这些问题修掉：预算没写上限，负责人没定人。");
        } catch (Workflow.BudgetExhausted e) {
            terminal = "BUDGET_EXHAUSTED";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(workflowRecord("demo-workflow-budget", TASK, wf, llm, terminal, ms));
        System.out.println("  [落盘] demo-workflow-budget → 终态=" + terminal + " → " + f);
    }

    /** 多 Agent Supervisor：主管分派两位剧本专家后综合。 */
    private static void runMaSupervisor(RunStore store) throws Exception {
        List<SupervisorPattern.Expert> roster = List.of(
                new SupervisorPattern.Expert("技术专家", "技术债与依赖",
                        new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new SupervisorPattern.Expert("法务专家", "合规风险",
                        new ScriptedLlm().says("发现 2 项合规风险，需两周内整改")));
        LlmClient boss = new ScriptedLlm()
                .says("分派：技术专家 | 梳理技术债\n分派：法务专家 | 检查合规风险");
        LlmClient synth = new ScriptedLlm().says("综合：先还技术债，合规两周内整改");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SupervisorPattern.dispatch("评估新功能上线风险", roster, boss, synth, journal.trace());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int dispatched = (int) journal.steps().stream()
                .filter(s -> s.reason().startsWith("分派→")).count();
        Path f = store.append(journal.toRecord("demo-ma-supervisor", "multiagent:supervisor",
                "评估新功能上线风险", out, dispatched + 2, ms));
        System.out.println("  [落盘] demo-ma-supervisor → " + out.lines().findFirst().orElse("")
                + " → " + f);
    }

    /** 多 Agent Swarm：客服 3 跳接力干净收工。 */
    private static void runMaSwarm(RunStore store) throws Exception {
        List<SwarmPattern.Specialist> roster = List.of(
                new SwarmPattern.Specialist("接入专员", "接待与分流", new ScriptedLlm()
                        .says("您好，您的包裹三天没有物流更新，我帮您查一下")),
                new SwarmPattern.Specialist("物流专员", "查件与理赔", new ScriptedLlm()
                        .says("核实了，件在转运中心丢失，建议直接给您办理退款")),
                new SwarmPattern.Specialist("退款专员", "退款流程", new ScriptedLlm()
                        .says("这笔需要财务同事打款，我帮您转过去")),
                new SwarmPattern.Specialist("财务专员", "打款到账", new ScriptedLlm()
                        .says("已为您原路退回，请留意查收银行卡")));
        var router = SwarmPattern.keywordRouter(Map.of(
                "物流专员", List.of("物流", "快递", "包裹"),
                "退款专员", List.of("退款"),
                "财务专员", List.of("财务", "打款")));
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(4, 5), "接入专员");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SwarmPattern.run("我的包裹不动了", roster, router, guard, journal.trace());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(journal.toRecord("demo-ma-swarm", "multiagent:swarm",
                "我的包裹不动了（客服 3 跳接力）", out, guard.handoffs() + 1, ms));
        System.out.println("  [落盘] demo-ma-swarm → " + guard.handoffs() + " 跳收工 → " + f);
    }

    /** 多 Agent Hierarchical：超深委派被 max-depth 拦下，GUARD_BLOCKED 入账。 */
    private static void runMaHierarchicalBlocked(RunStore store) throws Exception {
        DelegationGuard guard = new DelegationGuard(new DelegationGuard.Config(2, 10), "总监");
        List<HierarchicalPattern.Level> levels = List.of(
                new HierarchicalPattern.Level("总监", t -> new ScriptedLlm()
                        .says("委派：任务A").complete(t)),
                new HierarchicalPattern.Level("组长", t -> new ScriptedLlm()
                        .says("委派：任务B").complete(t)),
                new HierarchicalPattern.Level("工程师", t -> new ScriptedLlm()
                        .says("完成：做完了").complete(t)));
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = HierarchicalPattern.run("交付新功能", levels, guard, journal.trace());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Path f = store.append(journal.toRecord("demo-ma-hierarchical-blocked",
                "multiagent:hierarchical", "交付新功能（3 层套娃越界）", out, 2, ms));
        System.out.println("  [落盘] demo-ma-hierarchical-blocked → " + out.lines().findFirst().orElse("")
                + " → " + f);
    }

    /** Workflow 轨迹 → RunRecord（verdictKind=AGENT，reason 记子智能体名字）。 */
    private static RunRecord workflowRecord(String runId, String task, Workflow wf,
                                            CountingLlm llm, String terminal, long durationMs) {
        List<Workflow.AgentCall> trace = wf.trace();
        List<RunRecord.Step> steps = new ArrayList<>(trace.size());
        for (int i = 0; i < trace.size(); i++) {
            Workflow.AgentCall call = trace.get(i);
            steps.add(new RunRecord.Step(i + 1, call.output(), "AGENT", call.name(), call.durationMs()));
        }
        return new RunRecord(runId, "workflow", task, terminal, List.copyOf(steps),
                llm.calls(), durationMs, System.currentTimeMillis());
    }
}
