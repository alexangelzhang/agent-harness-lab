package com.example.multiagent;

import com.example.harness.RunRecord;
import com.example.harness.RunStore;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.multiagent.SupervisorPattern.Expert;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Supervisor 并行 fan-out 演示：主管拆完任务，各专家<b>同时开工</b>，
 * 对比串行派单的墙钟时间，并演练单专家故障不拖死整轮的隔离。
 *
 * <p>三幕：①串行基线——三个专家各磨 300ms，墙钟 ≈ 900ms；②并行 fan-out
 * ——同样三活同时开工，墙钟 ≈ 300ms，断言快出一大截；③故障隔离——一个
 * 专家抛异常，结果包记「[调用失败]」，综合照常出稿。专家用
 * {@link ScriptedLlm} 的 lambda 预置「慢」，离线可跑、毫秒级。</p>
 *
 * <p>第四幕（{@code -Dexec.args="real"}，需 LLM_API_KEY）：真实 真实模型主管
 * 走 dispatchParallel，接线 {@code CountingLlm(RetryingLlm(RateLimitedLlm(...)))}
 * ——并发闸限流在内、重试在外；开跑前先过 {@link ProtocolProbe} 分派协议
 * 预检（专员名单写进 prompt）。</p>
 *
 * <p>并行结果经 {@link RunJournal} 落进 {@code runs/runs.jsonl}，
 * runner 记 {@code multiagent:supervisor-parallel}——与串行版同一张底账，
 * 成本对比有据可查。</p>
 *
 * <p>运行：{@code scripts/dev.sh parallel [real]}。</p>
 */
public class ParallelFanoutScenario {

    private static final RunStore STORE = new RunStore(Path.of("runs", "runs.jsonl"));

    /** 「分派：专员 | 任务」协议行（与 SupervisorRealScenario 同款）。 */
    private static final java.util.regex.Pattern DISPATCH_LINE =
            java.util.regex.Pattern.compile("分派[:：]\\s*([^|｜\\n]+?)\\s*[|｜]");

    private static int bad;

    public static void main(String[] args) {
        header("Supervisor 并行 fan-out：专家同时开工，墙钟只算最慢的那个",
                "并发上限是调用配额的安全阀；单专家故障只记一条，不拖死整轮");

        // 第一幕：串行基线
        System.out.println();
        System.out.println("—— 第一幕：串行派单基线，三个专家各磨 300ms ——");
        String task = "给新品手冲套装写上市文案";
        CountingLlm serialSupervisor = new CountingLlm(supervisorBrain());
        CountingLlm serialSynth = new CountingLlm(new ScriptedLlm()
                .says("串行综合：三点卖点齐了，收工。"));
        long serialStart = System.currentTimeMillis();
        String serialAnswer = SupervisorPattern.dispatch(task, sleepyRoster(300),
                serialSupervisor, serialSynth, consoleTrace());
        long serialMs = System.currentTimeMillis() - serialStart;
        System.out.println("  串行墙钟 " + serialMs + "ms，综合出稿：「" + serialAnswer + "」");
        check("串行墙钟 ≥ 900ms（三棒各 300ms 相加）", serialMs >= 900);

        // 第二幕：并行 fan-out
        System.out.println();
        System.out.println("—— 第二幕：并行 fan-out，三活同时开工 ——");
        RunJournal journal = new RunJournal();
        CountingLlm parallelSupervisor = new CountingLlm(supervisorBrain());
        CountingLlm parallelSynth = new CountingLlm(new ScriptedLlm()
                .says("并行综合：三点卖点齐了，收工。"));
        long parallelStart = System.currentTimeMillis();
        String parallelAnswer = SupervisorPattern.dispatchParallel(task, sleepyRoster(300),
                parallelSupervisor, parallelSynth, 4, consoleTrace(journal));
        long parallelMs = System.currentTimeMillis() - parallelStart;
        System.out.println("  并行墙钟 " + parallelMs + "ms，综合出稿：「" + parallelAnswer + "」");
        check("并行墙钟 < 串行墙钟的一半（同时开工）", parallelMs * 2 < serialMs);
        check("并行结果照常出稿", parallelAnswer.contains("并行综合"));
        check("调用数与串行一致（省的是时间不是调用）",
                parallelSupervisor.calls() + parallelSynth.calls() == 2
                        && journal.steps().stream().filter(s -> s.reason().startsWith("分派→")).count() == 3);
        archive(journal, "scenario-parallel-fanout", "multiagent:supervisor-parallel",
                task, parallelAnswer, parallelSupervisor.calls() + parallelSynth.calls() + 3,
                parallelMs);

        // 第三幕：故障隔离
        System.out.println();
        System.out.println("—— 第三幕：一个专家当场抛异常，整轮照常出稿 ——");
        ScriptedLlm flaky = new ScriptedLlm().next(prompt -> {
            throw new IllegalStateException("接口超时");
        });
        List<Expert> mixed = List.of(
                new Expert("卖点策划", "提炼卖点", new ScriptedLlm().says("卖点：便携、稳温、好洗")),
                new Expert("渠道分析", "选首发渠道", flaky),
                new Expert("定价建议", "给价格带", new ScriptedLlm().says("建议 299-399 元")));
        String recovered = SupervisorPattern.dispatchParallel(task, mixed,
                supervisorBrain(), new ScriptedLlm().says("综合：卖点和定价齐了，渠道分析缺席，文案里明说。"),
                4, consoleTrace());
        System.out.println("  出稿：「" + recovered + "」");
        check("故障被隔离成一条记录，综合照常出稿", recovered.contains("渠道分析缺席"));

        if (args.length > 0 && args[0].equals("real")) {
            actRealBoss(task);
        }

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：并行的收益是墙钟，不是调用数；坏一个专家，"
                    + "其余照常交卷——这才像生产环境的样子。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    /** 第四幕：真实 真实模型主管并行分派（剧本专家接活），限流接线 + 协议预检。 */
    private static void actRealBoss(String task) {
        System.out.println();
        System.out.println("—— 第四幕：真实 真实模型主管并行分派（限流在内重试在外）——");
        if (System.getenv("LLM_API_KEY") == null) {
            System.err.println("  缺少 LLM_API_KEY，第四幕跳过");
            bad++;
            return;
        }
        List<Expert> roster = sleepyRoster(50);
        java.util.Set<String> names = roster.stream()
                .map(Expert::name).collect(java.util.stream.Collectors.toSet());

        // 协议自检探针：分派格式先验证，专员名单写进 prompt（不自造名字的教训）
        CountingLlm boss = new CountingLlm(
                new com.example.langgraph4j.llm.RetryingLlm(
                        new com.example.langgraph4j.llm.RateLimitedLlm(
                                new com.example.langgraph4j.llm.JdkHttpLlm(), 4, 200)));
        ProtocolProbe.requireOrExit(boss, ProtocolProbe.rosterProtocol(names), DISPATCH_LINE, names);

        CountingLlm synth = new CountingLlm(new ScriptedLlm()
                .says("综合：真实主管的分派齐了，按这个出稿。"));
        RunJournal journal = new RunJournal();
        long t0 = System.currentTimeMillis();
        String answer = SupervisorPattern.dispatchParallel(task, roster,
                boss, synth, 4, consoleTrace(journal));
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  出稿：「" + answer + "」");
        long dispatched = journal.steps().stream()
                .filter(s -> s.reason().startsWith("分派→")).count();
        boolean noSkip = journal.steps().stream().noneMatch(s -> s.reason().equals("主管"));
        System.out.println("  分派 " + dispatched + " 条，主管调用 " + boss.calls() + " 次，墙钟 " + ms + "ms");
        check("真实主管分派行全部命中花名册", dispatched >= 2 && noSkip);
        check("综合收拢非空", !answer.isBlank());
        archive(journal, "real-parallel-fanout", "multiagent:supervisor-parallel",
                task, answer, boss.calls() + (int) dispatched + 1, ms);
    }

    /** 主管剧本：把任务拆给三个人。 */
    private static ScriptedLlm supervisorBrain() {
        return new ScriptedLlm().says("""
                分派：卖点策划 | 提炼三个核心卖点
                分派：渠道分析 | 建议首发渠道
                分派：定价建议 | 给价格带""");
    }

    /** 三个专家，每个接单先磨 millis 毫秒（模拟慢调用），再交卷。 */
    private static List<Expert> sleepyRoster(int millis) {
        return List.of(
                new Expert("卖点策划", "提炼卖点", sleepy(millis, "卖点：便携、稳温、好洗")),
                new Expert("渠道分析", "选首发渠道", sleepy(millis, "首发：天猫+小红书")),
                new Expert("定价建议", "给价格带", sleepy(millis, "建议 299-399 元")));
    }

    private static LlmClient sleepy(int millis, String reply) {
        return new ScriptedLlm().next(prompt -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reply;
        });
    }

    private static BiConsumer<String, String> consoleTrace() {
        return (who, what) -> System.out.println("    [" + who + "] " + what);
    }

    private static BiConsumer<String, String> consoleTrace(RunJournal journal) {
        return (who, what) -> {
            System.out.println("    [" + who + "] " + what);
            journal.trace().accept(who, what);
        };
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
}
