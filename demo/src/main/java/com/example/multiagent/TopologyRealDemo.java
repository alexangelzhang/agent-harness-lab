package com.example.multiagent;

import com.example.harness.RunRecord;
import com.example.harness.RunStore;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.DelegationGuard.Config;
import com.example.multiagent.HierarchicalPattern.Level;
import com.example.multiagent.SupervisorPattern.Expert;
import com.example.multiagent.SwarmPattern.Specialist;
import com.example.multiagent.TopologyAdvisor.TaskProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多 Agent 拓扑真实模型对照组（{@code TopologyDemo} 剧本版的真实版，
 * 2026-10-31）。
 *
 * <p>剧本版证明「机制对」；这个 demo 证明「真实模型填进来，机制还对」——
 * 决策树推荐是确定性的，真实模型只负责<b>把自然语言任务翻译成画像信号</b>；
 * 主管/总监/客服由真实模型扮演，专家/下层保持剧本（把变量隔离在要观察的
 * 那一处）。硬断言只校结构：推荐名、护栏状态、交接跳数、画像六信号齐不齐，
 * 模型措辞不断言。</p>
 *
 * <p>每个多 Agent 运行都经 {@link RunJournal} 追加进 {@code runs/runs.jsonl}
 * ——与剧本版、单体 loop 同一张审计底账（真实运行烧的调用和耗时一并入账）。</p>
 *
 * <p>五个场景（真实调用慢，{@code -Dexec.args="n"} 只跑第 n 个）：</p>
 * <ol>
 *   <li><b>真实画像进决策树</b>：四段任务描述交给 真实模型逐信号填空，
 *       决策树出推荐，对照预期拓扑；</li>
 *   <li><b>真实主管分派</b>：真实模型当主管拆任务派给剧本专家，
 *       校分派行格式与花名册一致性；</li>
 *   <li><b>真实总监下派</b>：真实模型当交付总监走 Hierarchical
 *       「完成：/委派：」协议，护栏记录链路；</li>
 *   <li><b>真实客服接力</b>：真实模型扮演四位客服专员，关键词路由 + 护栏
 *       掐 Swarm 接力；</li>
 *   <li><b>写密集劝退</b>：多人共写一份文档的真实画像进决策树，
 *       必须给出「单 Agent」劝退；</li>
 *   <li><b>路由对照</b>：同一批 真实模型专员、同一段对话，关键词路由
 *       （免费、可解释，但接棒者必须说中 cue 词）vs 模型路由
 *       （每次交接花一次调用，换对接棒者自由措辞的容忍度）。</li>
 * </ol>
 *
 * <p>运行：{@code mvn exec:java@topologyreal -Dexec.args="[1-6]"}，
 * 需要 {@code LLM_API_KEY}，缺了明确报错退出。</p>
 */
public class TopologyRealDemo {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    /** 「分派：专员 | 任务」协议行（group 1 = 专员名，对齐 SupervisorPattern 的解析）。 */
    private static final Pattern DISPATCH_LINE = Pattern.compile("分派[:：]\\s*([^|｜\\n]+?)\\s*[|｜]");

    private static int bad;

    public static void main(String[] args) {
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("缺少 LLM_API_KEY 环境变量，无法调用真实模型");
            System.exit(1);
        }
        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("多 Agent 拓扑真实对照组：画像 / 主管 / 总监 / 客服 / 劝退");
        System.out.println("模式：真实模型（JdkHttpLlm）" + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        if (pick == 0 || pick == 1) {
            scenarioAdvisor();
        }
        if (pick == 0 || pick == 2) {
            scenarioSupervisor();
        }
        if (pick == 0 || pick == 3) {
            scenarioHierarchical();
        }
        if (pick == 0 || pick == 4) {
            scenarioSwarm();
        }
        if (pick == 0 || pick == 5) {
            scenarioWriteDense();
        }
        if (pick == 0 || pick == 6) {
            scenarioRouterCompare();
        }

        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：真实模型填进来的信号、扮演的角色，决策树和"
                    + "护栏的行为跟剧本版一致——机制不挑模型。");
        } else {
            System.out.println("!! 有 " + bad + " 处结构校验未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 场景一：四段任务描述 → 真实模型逐信号填空 → 决策树推荐对照预期。 */
    private static void scenarioAdvisor() {
        header("场景一：真实画像进决策树", "模型翻译信号，决策树做确定性的推荐");
        CountingLlm real = new CountingLlm(new JdkHttpLlm());
        RunJournal journal = new RunJournal();
        long t0 = System.currentTimeMillis();
        record Case(String desc, String expect) {
        }
        List<Case> cases = List.of(
                new Case("每月初把销售、库存、财务三个系统的数据汇成一份固定格式的"
                        + "经营月报，流程多年没变。", "Workflow"),
                new Case("筹备一场跨部门发布会，要由一个人拿总，随时按情况调整"
                        + "技术、法务、市场的分工。", "Supervisor"),
                new Case("一个五十人的软件交付组织：交付总监管组长，组长带工程师，"
                        + "层层往下交代任务。", "Hierarchical"),
                new Case("电商客服对话：用户可能从物流查询一路聊到退款和发票，"
                        + "话题跟着用户走。", "Swarm"));
        for (Case c : cases) {
            TaskProfile profile = askProfile(real, c.desc());
            System.out.println("  任务：" + c.desc());
            System.out.println("    画像：固定工序=" + yn(profile.fixedPipeline())
                    + " 中央协调=" + yn(profile.centralCoordination())
                    + " 分层=" + yn(profile.needsLayering())
                    + " 焦点漂移=" + yn(profile.focusDrift())
                    + " 读源=" + profile.independentReadSources()
                    + " 写出=" + profile.sharedWriteTargets());
            var rec = TopologyAdvisor.recommend(profile);
            System.out.println("    决策树 → " + rec.name() + "（期待 " + c.expect() + "）");
            check("决策树推荐 " + c.expect(), rec.name().equals(c.expect()));
            journal.trace().accept("画像→" + c.expect(), "推荐 " + rec.name() + "：" + rec.reason());
        }
        archive(journal, "real-topology-advisor", "multiagent:advisor",
                "四种真实任务画像的拓扑选型", "四例推荐完成",
                real.calls(), System.currentTimeMillis() - t0);
    }

    /** 场景二：真实模型当主管，剧本专家接活，校分派格式与花名册一致。 */
    private static void scenarioSupervisor() {
        header("场景二：真实主管分派", "主管是变量，专家是剧本——隔离观察主管行为");
        CountingLlm boss = new CountingLlm(new JdkHttpLlm());
        List<Expert> roster = List.of(
                new Expert("技术专家", "技术债与依赖评估",
                        new ScriptedLlm().says("依赖三方库 12 个，2 个已停止维护")),
                new Expert("法务专家", "合规风险检查",
                        new ScriptedLlm().says("发现 2 项合规风险，需两周内整改")),
                new Expert("市场专家", "市场反应评估",
                        new ScriptedLlm().says("竞品下周发布同类功能")));
        // 协议自检探针：分派协议先验证，花名册名字为声明清单
        Set<String> names = roster.stream().map(Expert::name).collect(Collectors.toSet());
        ProtocolProbe.requireOrExit(boss, ProtocolProbe.rosterProtocol(names), DISPATCH_LINE, names);
        LlmClient synth = new ScriptedLlm().says("综合：技术债优先，合规跟进");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String out = SupervisorPattern.dispatch(
                "给新上线的产品做一份风险评估", roster, boss, synth, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  综合答复 → " + firstLine(out));
        boolean formatOk = !out.isBlank();
        check("真实主管完成拆解与综合", formatOk && boss.calls() >= 1);
        archive(journal, "real-supervisor-dispatch", "multiagent:supervisor",
                "给新上线的产品做一份风险评估（真实主管）", out,
                boss.calls() + dispatchCalls(journal, "分派→") + 1, ms);
    }

    /** 场景三：真实模型当交付总监走层级协议，剧本组长/工程师在下。 */
    private static void scenarioHierarchical() {
        header("场景三：真实总监下派", "总监回「委派：」，组长转「委派：」，工程师收「完成：」");
        DelegationGuard guard = new DelegationGuard(new Config(3, 10), "交付总监");
        CountingLlm director = new CountingLlm(new JdkHttpLlm());
        List<Level> levels = List.of(
                new Level("交付总监", t -> director.complete("你是交付总监。收到任务："
                        + t + "\n如果任务可以整体交代下去，回复「委派：<子任务>」；"
                        + "如果能直接给结果，回复「完成：<结果>」。只输出这一行。")),
                new Level("研发组长", t -> new ScriptedLlm().says("委派：写登录接口，带限流").complete(t)),
                new Level("工程师", t -> new ScriptedLlm().says("完成：登录接口已交付，含限流").complete(t)));
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String done = HierarchicalPattern.run("交付登录功能", levels, guard, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  交付结果 → " + done + "（共 " + guard.handoffs() + " 跳）");
        check("真实总监完成一次下派", guard.handoffs() == 2 && !done.contains("护栏拦截"));
        archive(journal, "real-hierarchical", "multiagent:hierarchical",
                "交付登录功能（真实总监 1 跳 + 剧本 2 层）", done, director.calls() + 2, ms);
    }

    /** 场景四：真实模型扮演四位客服，关键词路由 + 护栏掐接力。 */
    private static void scenarioSwarm() {
        header("场景四：真实客服接力", "四位专员全是真实模型，路由按关键词，护栏照掐");
        BiConsumer<String, String> trace = trace();
        CountingLlm real = new CountingLlm(new JdkHttpLlm());
        List<Specialist> roster = List.of(
                new Specialist("接入专员", "接待与分流",
                        roleBrain(real, "接入客服",
                                "如果用户的诉求属于物流/快递问题，回复里带上「物流」"
                                        + "并说明帮他转物流同事；")),
                new Specialist("物流专员", "查件与理赔",
                        roleBrain(real, "物流专员",
                                "核实后如果件丢了该走退款，回复里带上「退款」"
                                        + "并说明转退款同事；")),
                new Specialist("退款专员", "退款流程",
                        roleBrain(real, "退款专员",
                                "如果需要财务打款，回复里带上「财务」"
                                        + "并说明转财务同事；回复里不要出现「物流」"
                                        + "「快递」「包裹」这些词——那是上一棒的事，"
                                        + "提了会被路由又交回物流，两个专员互踢皮球。")),
                new Specialist("财务专员", "打款到账",
                        roleBrain(real, "财务专员（最后一棒，收工由你收口）",
                                "打款已完成。直接告知用户钱已原路退回、请留意查收即可——"
                                        + "不要提转交，回复里也不要出现「退款」「物流」"
                                        + "「快递」「包裹」这些词，否则会被路由误判成还要继续交接。")));
        var router = SwarmPattern.keywordRouter(Map.of(
                "物流专员", List.of("物流", "快递", "包裹"),
                "退款专员", List.of("退款"),
                "财务专员", List.of("财务", "打款")));
        DelegationGuard guard = new DelegationGuard(new Config(4, 5), "接入专员");
        RunJournal journal = new RunJournal();
        long t0 = System.nanoTime();
        String resolved = SwarmPattern.run("我的包裹三天没动了", roster, router, guard, trace(journal));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  终局答复 → " + firstLine(resolved)
                + "（共 " + guard.handoffs() + " 跳）");
        check("真实客服完成接力且未被护栏误拦",
                guard.handoffs() >= 2 && !resolved.contains("护栏拦截"));
        archive(journal, "real-swarm", "multiagent:swarm",
                "我的包裹三天没动了（真实客服接力）", resolved, real.calls(), ms);
    }

    /** 场景五：多人共写一份文档的画像，决策树必须劝退拆分。 */
    private static void scenarioWriteDense() {
        header("场景五：写密集劝退", "三位运营共写一份文档——默认立场是不拆");
        CountingLlm real = new CountingLlm(new JdkHttpLlm());
        RunJournal journal = new RunJournal();
        long t0 = System.currentTimeMillis();
        String desc = "三位运营同时在线编辑同一份活动方案文档：没有事先分好的"
                + "章节工序，谁想到谁改，几个人经常改到同一段落，但必须保持"
                + "全文风格统一。";
        TaskProfile profile = askProfile(real, desc);
        System.out.println("  画像：固定工序=" + yn(profile.fixedPipeline())
                + " 中央协调=" + yn(profile.centralCoordination())
                + " 分层=" + yn(profile.needsLayering())
                + " 焦点漂移=" + yn(profile.focusDrift())
                + " 读源=" + profile.independentReadSources()
                + " 写出=" + profile.sharedWriteTargets());
        var rec = TopologyAdvisor.recommend(profile);
        System.out.println("  决策树 → " + rec.name());
        System.out.println("  理由 → " + rec.reason());
        check("写密集画像被劝退（单 Agent）", rec.staySingle());
        journal.trace().accept("写密集画像", "推荐 " + rec.name() + "：" + rec.reason());
        archive(journal, "real-advisor-writedense", "multiagent:advisor",
                "多人共写文档的拓扑选型", rec.name(), real.calls(),
                System.currentTimeMillis() - t0);
    }

    /** 场景六：同批专员同段对话，关键词路由 vs 模型路由对照。 */
    private static void scenarioRouterCompare() {
        header("场景六：路由对照", "同一批 真实模型专员、同一段对话——免费但要「说对话」 vs 花调用换自由措辞");
        CountingLlm real = new CountingLlm(new JdkHttpLlm());
        List<Specialist> roster = List.of(
                new Specialist("接入专员", "接待用户，判断诉求后提出转交",
                        roleBrain(real, "接入客服",
                                "你查不了物流单号；用户的诉求属于物流/快递问题，"
                                        + "回复里带上「物流」并说明转物流同事。")),
                new Specialist("物流专员", "查件、确认丢失、提出理赔或退款建议",
                        roleBrain(real, "物流专员",
                                "核实后如果件丢了该走退款，回复里带上「退款」"
                                        + "并说明转退款同事。")),
                new Specialist("退款专员", "办理退款流程，需要打款时转财务",
                        roleBrain(real, "退款专员",
                                "如果需要财务打款，回复里带上「财务」"
                                        + "并说明转财务同事；回复里不要出现「物流」"
                                        + "「快递」「包裹」这些词——那是上一棒的事，"
                                        + "提了会被路由又交回物流，两个专员互踢皮球。")),
                new Specialist("财务专员", "打款并告知用户留意查收，收口",
                        roleBrain(real, "财务专员（最后一棒，收口）",
                                "打款已完成，直接告知用户钱已原路退回、留意查收即可——"
                                        + "不要提转交，回复里也不要出现「退款」「物流」"
                                        + "「快递」「包裹」这些词，否则会被路由误判成"
                                        + "还要继续交接。")));
        var kwRouter = SwarmPattern.keywordRouter(Map.of(
                "物流专员", List.of("物流", "快递", "包裹"),
                "退款专员", List.of("退款"),
                "财务专员", List.of("财务", "打款")));
        var llmRouter = SwarmPattern.modelRouter(real, roster);
        String task = "我的包裹三天没动了";

        System.out.println("  —— 关键词路由 —— cue 词：物流/快递/包裹 → 退款 → 财务/打款");
        DelegationGuard kwGuard = new DelegationGuard(new Config(4, 5), "接入专员");
        RunJournal journalKw = new RunJournal();
        long t0 = System.nanoTime();
        String byKw = SwarmPattern.run(task, roster, kwRouter, kwGuard, trace(journalKw));
        long msKw = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  终局 → " + firstLine(byKw) + "（" + kwGuard.handoffs() + " 跳）");
        check("关键词路由干净收工", !byKw.contains("护栏拦截"));
        archive(journalKw, "real-swarm-kwrouter", "multiagent:swarm",
                "包裹不动（关键词路由）", byKw, kwGuard.handoffs() + 1, msKw);

        System.out.println();
        System.out.println("  —— 模型路由 —— 调度决策也交给真实模型，读语义不用背词");
        DelegationGuard llmGuard = new DelegationGuard(new Config(4, 6), "接入专员");
        RunJournal journalLlm = new RunJournal();
        long t1 = System.nanoTime();
        String byLlm = SwarmPattern.run(task, roster, llmRouter, llmGuard, trace(journalLlm));
        long msLlm = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("  终局 → " + firstLine(byLlm) + "（" + llmGuard.handoffs() + " 跳）");
        check("模型路由干净收工", !byLlm.contains("护栏拦截"));
        archive(journalLlm, "real-swarm-llmrouter", "multiagent:swarm",
                "包裹不动（模型路由）", byLlm,
                2 * (llmGuard.handoffs() + 1), msLlm);
        System.out.println("  对照：关键词免费、可解释，但接棒者必须说中 cue 词；"
                + "模型路由每次交接多一次调用，换接棒者自由措辞的容忍度。"
                + "全程 " + real.calls() + " 次 LLM 调用。");
    }

    // ---- 小工具 --------------------------------------------------------------

    /** 让 真实模型把任务描述翻译成六信号画像，解析不出来就标失败。 */
    private static TaskProfile askProfile(LlmClient real, String desc) {
        String prompt = "把下面的任务描述翻译成结构化画像，逐项回答，输出六行，每行"
                + "「信号:值」。注意中央协调和分层是两回事：组织里有多级汇报"
                + "（如总监→组长→工程师）只算分层；只有需要一个人临场拆解、"
                + "分派、收拢任务时才算中央协调。各项独立判断：\n"
                + "固定工序:是|否（工序是否事先固定、顺序不变）\n"
                + "中央协调:是|否（是否需要一个人临场分工、拿总协调）\n"
                + "分层:是|否（是否存在天然的上下级汇报层级）\n"
                + "焦点漂移:是|否（对话/任务焦点会不会中途漂移）\n"
                + "读源:数字（有几个相互独立的读取信息源）\n"
                + "写出:数字（有几个产物会被多个执行方同时写、存在写冲突——"
                + "重点看写冲突而不是产出个数；多人共写同一份文档就算 1，"
                + "各写各的最后统稿才算 0）\n任务描述：" + desc;
        String raw = real.complete(prompt);
        System.out.println("    模型原始输出 → " + raw.replace('\n', ' '));
        boolean fixed = flag(raw, "固定工序");
        boolean central = flag(raw, "中央协调");
        boolean layered = flag(raw, "分层");
        boolean drift = flag(raw, "焦点漂移");
        int reads = number(raw, "读源");
        int writes = number(raw, "写出");
        boolean parsed = (fixed || central || layered || drift) && reads >= 0 && writes >= 0;
        check("画像六信号解析成功", parsed);
        return new TaskProfile(fixed, central, layered, drift,
                Math.max(reads, 0), Math.max(writes, 0));
    }

    private static boolean flag(String raw, String key) {
        Matcher m = Pattern.compile(key + "\\s*[:：]\\s*(是|否)").matcher(raw);
        return m.find() && "是".equals(m.group(1));
    }

    /** 解析不出返回 -1（调用方据此判解析失败）。 */
    private static int number(String raw, String key) {
        Matcher m = Pattern.compile(key + "\\s*[:：]\\s*(\\d+)").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 真实模型扮演某棒：角色前缀 + 交接提示，一次调用一个回复。 */
    private static LlmClient roleBrain(LlmClient real, String role, String handoffHint) {
        return prompt -> real.complete("你是客服团队里的" + role + "。" + handoffHint
                + "\n用户/上一棒说：" + prompt + "\n直接给出你的回复正文，不要解释。");
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }

    private static String yn(boolean b) {
        return b ? "是" : "否";
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }

    private static void check(String label, boolean cond) {
        if (cond) {
            System.out.println("  ✓ " + label);
        } else {
            bad++;
            System.out.println("  !! " + label);
        }
    }

    private static BiConsumer<String, String> trace() {
        return (who, what) -> System.out.println("    [" + who + "] "
                + firstLine(what));
    }

    /** 既打印又留档的 trace。 */
    private static BiConsumer<String, String> trace(RunJournal journal) {
        return (who, what) -> {
            System.out.println("    [" + who + "] " + firstLine(what));
            journal.trace().accept(who, what);
        };
    }

    /** 分派/交接步数：journal 里 who 带前缀的步数。 */
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
}
