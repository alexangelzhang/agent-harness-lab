package com.example.workflow;

import com.example.harness.Budget;
import com.example.harness.RunRecord;
import com.example.harness.RunStore;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 动态工作流演示：模拟"模型现场写 harness"（出处见 {@link Workflow}）。
 *
 * <p>与 {@code com.example.langgraph4j} 静态多智能体图的区别：那里的编排是编译期
 * 画死的节点和边；这里每个场景只给一个 {@link Workflow} 引擎，子智能体由模型
 * 按任务现生成。剧本模式用每个场景独立的 ScriptedLlm 队列模拟"模型决定起谁、
 * 起几个"（场景间剧本不混）。真实模式把剧本换成 JdkHttpLlm，验证六种模式的
 * 提示词协议在真实输出下是否成立。</p>
 *
 * <p>覆盖文章的六种模式：分类并执行、扇出并综合、对抗式验证、生成并筛选、
 * 锦标赛（两两对比全程用全新裁判）、循环直到完成（停止条件驱动，不预设轮数，
 * 带轮数上限防真实模型无限找茬）。末尾打印子智能体总数与 LLM 调用数，
 * 对应文章的 token 预算意识。</p>
 *
 * <p>跑法：</p>
 * <ul>
 *   <li>{@code mvn exec:java@workflow}——剧本模式，六个场景全跑；</li>
 *   <li>{@code mvn exec:java@workflow -Dexec.args="real [n]"}——真实模型，
 *       n 为场景号 1~6（缺省全跑；分场景跑是因为真实调用慢，单次命令有超时）。</li>
 * </ul>
 */
public class DynamicWorkflowDemo {

    /** 循环场景的轮数上限：真实模型的审查员可能每轮都"发现新问题"，防止无限跑；
     *  取 3 是真实模式单次命令时间预算内能跑完的上限（每轮 2 次真实调用）。 */
    private static final int MAX_ROUNDS = 3;

    private static int totalSpawned;
    private static int totalCalls;
    private static long startedAt;
    private static RunStore store;

    public static void main(String[] args) {
        boolean real = args.length > 0 && args[0].equals("real");
        int pick = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        startedAt = System.nanoTime();
        // 与 archive demo 共用同一份审计底账：动态编排每次运行也留痕
        store = new RunStore(Path.of("runs", "runs.jsonl"));

        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("动态工作流：模型运行时现写 harness（Claude Code 六模式）");
        System.out.println("编排不预先画死，子智能体按任务现生成、上下文彼此隔离");
        System.out.println("模式：" + (real ? "真实模型（JdkHttpLlm）" : "剧本（ScriptedLlm）")
                + (pick > 0 ? "，只跑场景 " + pick : ""));
        System.out.println(line);

        if (real) {
            if (System.getenv("LLM_API_KEY") == null) {
                System.err.println("缺少 LLM_API_KEY 环境变量");
                System.exit(1);
            }
            runReal(pick);
        } else {
            runScripted(pick);
        }

        System.out.println();
        System.out.println(line);
        System.out.printf("收尾：共生成子智能体 %d 个，LLM 调用 %d 次，耗时 %ds%n",
                totalSpawned, totalCalls, (System.nanoTime() - startedAt) / 1_000_000_000);
        System.out.println("子智能体数 ≈ token 消耗：动态编排不是免费的，");
        System.out.println("按文章的建议，只给复杂且价值高的任务上多智能体。");
        System.out.println(line);
        System.exit(0);
    }

    // ---- 剧本模式：每个场景一个独立 ScriptedLlm 队列 ----

    private static void runScripted(int pick) {
        if (pick == 0 || pick == 1) {
            Metered m = scripted(llm -> llm
                    .says("类型=文案")
                    .says("文案成稿：早上赶时间，撕开一袋，三十秒喝到一杯正经冷萃。"));
            archiveRun("wf-scripted-s1", "分类并执行：给新品冷萃咖啡液写一句推广语", m,
                    () -> scenarioClassifyAndExecute(m, "给新品冷萃咖啡液写一句推广语"));
        }
        if (pick == 0 || pick == 2) {
            Metered m = scripted(llm -> llm
                    .says("创意 8/10：口号有记忆点，落在真实早晨场景上。")
                    .says("可信度 6/10：有具体场景，但没有数据支撑，别当真事宣传。")
                    .says("合规 9/10：没有夸大词，可以用。")
                    .says("综合结论：方向可用。补一句真实配料信息再发，可信度短板靠它补。"));
            archiveRun("wf-scripted-s2", "扇出并综合：三维评审一句推广语", m,
                    () -> scenarioFanOutAndSynthesize(m, "早上赶时间，撕开一袋，三十秒喝到一杯正经冷萃。"));
        }
        if (pick == 0 || pick == 3) {
            Metered m = scripted(llm -> llm
                    .says("初稿：这杯咖啡液用了好豆子，口感醇厚，值得一试。")
                    .says("不通过，两处硬伤：『好豆子』『醇厚』都是空话，没有依据；"
                            + "『值得一试』是主观吹捧，换成具体事实。")
                    .says("修订稿：配料只有咖啡和水，一袋能冲一杯，放包里不洒。"));
            archiveRun("wf-scripted-s3", "对抗式验证：出稿 → 对抗审查 → 修订", m,
                    () -> scenarioAdversarialVerify(m));
        }
        if (pick == 0 || pick == 4) {
            Metered m = scripted(llm -> llm
                    .says("想法1：办公室冰箱常备，下午困了随取随喝。")
                    .says("想法2：健身完想喝点凉的，顺手一袋。")
                    .says("想法3：露营不想带手冲装备，带两袋省事。")
                    .says("最优=想法1：高频刚需、卖点直接；想法2人群窄，想法3一年用不了几次。"));
            archiveRun("wf-scripted-s4", "生成并筛选：三发散 + 标准筛选", m,
                    () -> scenarioGenerateAndFilter(m));
        }
        if (pick == 0 || pick == 5) {
            Metered m = scripted(llm -> llm
                    .says("直白派：冷萃咖啡液，加水就是一杯咖啡。")
                    .says("场景派：早上来不及手冲？撕开一袋就是一杯。")
                    .says("对比派：咖啡店三十块一杯，它一袋几块钱。")
                    .says("省心派：不用磨豆不用洗壶，撕开就能喝。")
                    .says("胜者=2：场景派有具体早晨场景，直白派只是复述产品。")
                    .says("胜者=2：省心派卖点清楚；对比派先扬对手，气势弱了。")
                    .says("胜者=1：场景派更具体、更能记住，赢。"));
            archiveRun("wf-scripted-s5", "锦标赛：四候选两两对决", m,
                    () -> scenarioTournament(m));
        }
        if (pick == 0 || pick == 6) {
            Metered m = scripted(llm -> llm
                    .says("发现2处：预算没写上限，负责人没定人。")
                    .says("修订版：预算上限 5 万，负责人张三，周五前确认。")
                    .says("无新发现"));
            archiveRun("wf-scripted-s6", "循环直到完成：审到无新发现停", m,
                    () -> scenarioLoopUntilDone(m));
        }
        if (pick == 0) {
            scenarioBudgetGuard();
        }
    }

    /** 预算护栏演示：同一循环剧本，LLM 调用预算只给 2 次——第二次修复起不来，转人工。 */
    private static void scenarioBudgetGuard() {
        header("模式六 + 预算护栏", "同一循环剧本，但 LLM 调用预算只给 2 次");
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.says("发现2处：预算没写上限，负责人没定人。")
                .says("修订版：预算上限 5 万，负责人张三，周五前确认。")
                .says("无新发现");
        CountingLlm meter = new CountingLlm(scripted);
        // 动态编排会起多少子智能体是模型现场决定的，比静态图更需要油表
        Budget budget = Budget.calls(meter::calls, 2);
        Metered m = new Metered(new Workflow(meter, budget), meter);
        archiveRun("wf-scripted-budget", "循环直到完成（LLM 调用预算 2 次）", m,
                () -> scenarioLoopUntilDone(m));
    }

    // ---- 真实模型：场景号分片跑（单次命令有超时，且每场景成本独立可见） ----

    private static void runReal(int pick) {
        if (pick == 0 || pick == 1) {
            Metered m = realMeter();
            archiveRun("wf-real-s1", "分类并执行：给新品冷萃咖啡液写一句推广语", m,
                    () -> scenarioClassifyAndExecute(m, "给新品冷萃咖啡液写一句推广语"));
        }
        if (pick == 0 || pick == 2) {
            Metered m = realMeter();
            archiveRun("wf-real-s2", "扇出并综合：三维评审一句推广语", m,
                    () -> scenarioFanOutAndSynthesize(m, "早上赶时间，撕开一袋，三十秒喝到一杯正经冷萃。"));
        }
        if (pick == 0 || pick == 3) {
            Metered m = realMeter();
            archiveRun("wf-real-s3", "对抗式验证：出稿 → 对抗审查 → 修订", m,
                    () -> scenarioAdversarialVerify(m));
        }
        if (pick == 0 || pick == 4) {
            Metered m = realMeter();
            archiveRun("wf-real-s4", "生成并筛选：三发散 + 标准筛选", m,
                    () -> scenarioGenerateAndFilter(m));
        }
        if (pick == 0 || pick == 5) {
            Metered m = realMeter();
            archiveRun("wf-real-s5", "锦标赛：四候选两两对决", m,
                    () -> scenarioTournament(m));
        }
        if (pick == 0 || pick == 6) {
            Metered m = realMeter();
            archiveRun("wf-real-s6", "循环直到完成：审到无新发现停", m,
                    () -> scenarioLoopUntilDone(m));
        }
        if (pick == 0 || pick == 7) {
            scenarioRealBudgetGuard();
        }
    }

    /** 真实模式预算护栏：锦标赛四候选各占 1 次调用，裁判预算只给 4 次——半决赛起不来，转人工。 */
    private static void scenarioRealBudgetGuard() {
        header("模式五 + 预算护栏（真实模型）", "四候选各占 1 次调用，预算只够生成、不够评判");
        CountingLlm meter = new CountingLlm(new JdkHttpLlm());
        Budget budget = Budget.calls(meter::calls, 4);
        Metered m = new Metered(new Workflow(meter, budget), meter);
        archiveRun("wf-real-budget", "锦标赛（LLM 调用预算 4 次）", m,
                () -> scenarioTournament(m));
    }

    // ---- 六个模式（剧本/真实共用的协议逻辑） ----

    /** 场景① 分类并执行：分类器定类型，路由给对应专员。 */
    static void scenarioClassifyAndExecute(Metered m, String task) {
        header("模式一：分类并执行", "分类器判断任务类型，分派给不同智能体或采用不同行为");
        String type = m.wf().agent("分类器", "判断任务类型（文案/代码/数据/研究），只回类型：\n" + task);
        System.out.println("分类器判定：" + oneLine(type));

        String route;
        String output;
        if (type.contains("文案")) {
            route = "文案专员";
            output = m.wf().agent("文案专员", "你是文案专员，写一句推广语：\n" + task);
        } else {
            route = "通用处理";
            output = m.wf().agent("通用处理", "处理任务：\n" + task);
        }
        System.out.println("路由 → " + route);
        System.out.println("产出 → " + oneLine(output));
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    /** 场景② 扇出并综合：三个维度各一个评审员，综合器做屏障合并。 */
    static void scenarioFanOutAndSynthesize(Metered m, String slogan) {
        header("模式二：扇出并综合", "步骤拆开各用一个干净上下文，综合步骤是屏障：等全部到齐");
        List<String> reviews = m.wf().parallel("评审员",
                "从创意角度评审这句推广语：" + slogan,
                "从可信度角度评审这句推广语：" + slogan,
                "从合规角度评审这句推广语：" + slogan);
        for (int i = 0; i < reviews.size(); i++) {
            System.out.printf("评审员%d → %s%n", i + 1, oneLine(reviews.get(i)));
        }

        // 屏障：三份评审全部到齐，才起综合器
        String summary = m.wf().agent("综合器", "合并以下三份评审，给一句可执行结论：\n"
                + String.join("\n", reviews));
        System.out.println("综合   → " + oneLine(summary));
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    /** 场景③ 对抗式验证：生成者出稿，对抗者照评分标准挑毛病，修复者改稿。 */
    static void scenarioAdversarialVerify(Metered m) {
        header("模式三：对抗式验证", "对生成结果另起一个智能体，按准则做对抗式审查");
        String draft = m.wf().agent("撰稿人", "给冷萃咖啡液写一句推广语");
        System.out.println("初稿   → " + oneLine(draft));

        // 关键：对抗者不是让撰稿人自己反省，而是全新上下文、只看准则和稿子
        String verdict = m.wf().agent("对抗审查员",
                "你是挑剔的审查员。逐条对照准则检查下面这句文案，"
                        + "只挑硬伤（空话/无依据/夸大），没有问题才放行：\n" + draft);
        System.out.println("对抗   → " + oneLine(verdict));

        String fixed = m.wf().agent("修复者", "按审查意见改稿，只陈述可验证的事实：\n" + verdict);
        System.out.println("修订   → " + oneLine(fixed));
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    /** 场景④ 生成并筛选：先发散出多个想法，再按标准筛选去重，只留最好的。 */
    static void scenarioGenerateAndFilter(Metered m) {
        header("模式四：生成并筛选", "生成多个想法，按标准筛选去重，只回质量最高的");
        List<String> ideas = m.wf().parallel("点子手",
                "想一个冷萃咖啡液的使用场景，一句话",
                "换一个完全不同的角度想一个使用场景，一句话",
                "再换一个角度，想一个使用场景，一句话");
        for (String idea : ideas) {
            System.out.println("候选   → " + oneLine(idea));
        }

        String best = m.wf().agent("筛选员", "按高频刚需、卖点直白两条标准筛选去重，只留最优：\n"
                + String.join("\n", ideas));
        System.out.println("筛选   → " + oneLine(best));
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    /** 场景⑤ 锦标赛：四个候选两两对决，每场用全新裁判，比较式判断比打分可靠。 */
    static void scenarioTournament(Metered m) {
        header("模式五：锦标赛", "N 个候选互相竞争，两两对比，每场比较都用全新裁判");
        // 生成 N 个候选：同一任务，不同方法
        List<String> candidates = m.wf().parallel("参赛者",
                "用『直白陈述』风格给冷萃咖啡液写一句推广语",
                "用『生活场景』风格给冷萃咖啡液写一句推广语",
                "用『价格对比』风格给冷萃咖啡液写一句推广语",
                "用『省事省心』风格给冷萃咖啡液写一句推广语");
        String[] tags = {"A", "B", "C", "D"};
        for (int i = 0; i < candidates.size(); i++) {
            System.out.println("候选" + tags[i] + " → " + oneLine(candidates.get(i)));
        }

        // 两两对决，每场都是全新裁判（没有单个上下文需要容纳全部候选）
        int semi1 = judge(m.wf(), candidates.get(0), candidates.get(1));
        int semi2 = judge(m.wf(), candidates.get(2), candidates.get(3));
        System.out.printf("半决赛1（A vs B）→ 胜者=%s%n", tags[semi1]);
        System.out.printf("半决赛2（C vs D）→ 胜者=%s%n", tags[semi2 + 2]);

        String finalist1 = candidates.get(semi1);
        String finalist2 = candidates.get(semi2 + 2);
        int champion = judge(m.wf(), finalist1, finalist2);
        System.out.println("冠军   → " + oneLine(champion == 0 ? finalist1 : finalist2));
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    /** 场景⑥ 循环直到完成：停止条件（无新发现）驱动，不预设轮数（带轮数上限）。 */
    static void scenarioLoopUntilDone(Metered m) {
        header("模式六：循环直到完成", "工作量未知，持续循环生成智能体直到满足停止条件");
        String doc = "促销活动方案 v1";
        int round = 0;
        boolean done = false;
        while (round < MAX_ROUNDS && !done) {
            round++;
            // 每轮审查都是全新上下文，只看当前稿子和准则；要求简短回复以压住真实调用耗时
            long t0 = System.nanoTime();
            String audit = m.wf().agent("审查员", "审查下面这份方案还有没有问题。"
                    + "没有问题就原样回复『无新发现』四个字；有问题最多列三条、每条一行，不要分析过程：\n"
                    + doc);
            System.out.printf("第%d轮审查（%ds）→ %s%n", round,
                    (System.nanoTime() - t0) / 1_000_000_000, oneLine(audit));
            if (audit.contains("无新发现")) {
                done = true; // 停止条件达成，不预设固定轮数
            } else {
                t0 = System.nanoTime();
                doc = m.wf().agent("修复员", "把这些问题修掉，输出修订版方案。"
                        + "保持同样简短（不超过 5 行），不要解释过程：\n" + audit);
                System.out.printf("修复（%ds）→ %s%n",
                        (System.nanoTime() - t0) / 1_000_000_000, oneLine(doc));
            }
        }
        System.out.println(done
                ? "第" + round + "轮停止：满足停止条件（无新发现）"
                : "达到轮数上限 " + MAX_ROUNDS + "，强制收工（防真实模型无限找茬）");
        // 计数与落盘由 archiveRun 统一处理（含预算中断场景）
    }

    // ---- 内部工具 ----

    /** 引擎 + 计数器。 */
    private record Metered(Workflow wf, CountingLlm meter) {
    }

    /** 锦标赛单场对决：全新裁判只看两个候选，回『胜者=1/2』。
     *  @return 胜方下标：0 为 left，1 为 right（不认识的回复默认 1，理由已打印可人工复核） */
    private static int judge(Workflow wf, String left, String right) {
        String verdict = wf.agent("裁判",
                "你是裁判。比较下面两句推广语哪句更好，回『胜者=1』或『胜者=2』加一句理由：\n"
                        + "1. " + left + "\n2. " + right);
        System.out.println("  裁判   → " + oneLine(verdict));
        return verdict.contains("胜者=1") ? 0 : 1;
    }

    /** 造一个装好剧本的 Workflow，并用 CountingLlm 计数。 */
    private static Metered scripted(Consumer<ScriptedLlm> script) {
        ScriptedLlm scripted = new ScriptedLlm();
        script.accept(scripted);
        CountingLlm meter = new CountingLlm(scripted);
        return new Metered(new Workflow(meter), meter);
    }

    /** 真实模型的 Workflow。 */
    private static Metered realMeter() {
        CountingLlm meter = new CountingLlm(new JdkHttpLlm());
        return new Metered(new Workflow(meter), meter);
    }

    /** 场景标题。 */
    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("-".repeat(66));
        System.out.println("【" + title + "】" + subtitle);
    }

    /** 累计一个场景的子智能体数与调用数并打印。 */
    private static void tally(Metered m) {
        totalSpawned += m.wf().spawned();
        totalCalls += m.meter().calls();
        System.out.printf("→ 本子智能体 %d 个 / LLM 调用 %d 次%n", m.wf().spawned(), m.meter().calls());
    }

    /**
     * 跑一个场景并落盘审计档案：捕获终态（DONE / BUDGET_EXHAUSTED / ERROR），
     * 轨迹转成 RunRecord.Step（verdictKind=AGENT，reason 记子智能体名字），
     * 追加进与 archive demo 共用的 runs/runs.jsonl。
     */
    private static void archiveRun(String runId, String task, Metered m, Runnable body) {
        long t0 = System.nanoTime();
        String terminal = "DONE";
        try {
            body.run();
        } catch (Workflow.BudgetExhausted e) {
            terminal = "BUDGET_EXHAUSTED";
            System.out.println("→ " + e.getMessage() + "（终态 " + terminal + "，已发生的调用照记）");
        } catch (RuntimeException e) {
            terminal = "ERROR";
            System.out.println("→ 运行出错：" + oneLine(e.getMessage()) + "（终态 " + terminal + "）");
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        tally(m);
        List<Workflow.AgentCall> trace = m.wf().trace();
        List<RunRecord.Step> steps = new ArrayList<>(trace.size());
        for (int i = 0; i < trace.size(); i++) {
            Workflow.AgentCall call = trace.get(i);
            steps.add(new RunRecord.Step(i + 1, oneLine(call.output()), "AGENT", call.name(),
                    call.durationMs()));
        }
        try {
            store.append(new RunRecord(runId, "workflow", task, terminal, List.copyOf(steps),
                    m.meter().calls(), durationMs, System.currentTimeMillis()));
            System.out.println("→ 档案落盘 " + runId + "（终态 " + terminal + "）");
        } catch (IOException e) {
            throw new UncheckedIOException("运行档案落盘失败", e);
        }
    }

    /** 多行输出压成一行，超长截断，保证日志可读。 */
    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
