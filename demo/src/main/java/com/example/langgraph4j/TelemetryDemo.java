package com.example.langgraph4j;

import com.example.harness.config.AgentConfig;
import com.example.harness.telemetry.MetricsRegistry;
import com.example.harness.telemetry.TelemetryLlm;
import com.example.harness.telemetry.Tracer;
import com.example.langgraph4j.eval.TrajectoryCollector;
import com.example.langgraph4j.llm.CountingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;
import com.example.langgraph4j.patterns.ReflectionGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 可观测性 demo：metrics / tracing 导出三幕，全剧本离线可跑。
 *
 * <p>底账（RunStore）答「这一次运行发生了什么」，指标和 trace 答
 * 「这一批运行整体什么样、时间花在哪」——三者同哲学：一行一条 JSONL，
 * 人可直接看，机器直接抓。</p>
 *
 * <ol>
 *   <li><b>指标装饰器</b>：{@link TelemetryLlm} 包在 ReflectionGraph 外面——
 *       调用数 / 失败数 / 耗时 / 字符量全自动攒，对照 {@link CountingLlm}
 *       证明一个不差；委托抛异常的那次计进 errors。</li>
 *   <li><b>导出</b>：{@link MetricsRegistry#exportPrometheus()} 出 Prometheus
 *       文本格式（textfile collector 直接抓）；gauge 注册「在飞调用数」，
 *       收尾归零。</li>
 *   <li><b>trace</b>：{@link Tracer} 开 span 树（一次 run 根 span + 每轮
 *       LLM 子 span），带相对时间戳事件，导出 JSONL 后用 traceId 把整条
 *       链捞回来——排障时先报表定位，再 traceId 下钻。</li>
 * </ol>
 *
 * <p>运行：{@code scripts/dev.sh telemetry}。</p>
 */
public class TelemetryDemo {

    private static int bad;

    public static void main(String[] args) throws Exception {
        header("可观测性", "metrics（Prometheus 文本导出）+ tracing（span 树 JSONL）——和底账同哲学：一行一条");
        AgentConfig config = AgentConfig.load();
        MetricsRegistry metrics = new MetricsRegistry();
        Tracer tracer = new Tracer();

        actInstrumentedGraph(metrics, tracer);
        actExport(metrics, config);
        actTrace(tracer, config);

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：先把「看见」做扎实——指标看面，trace 看点，");
            System.out.println("      底账看账；三个都是一行一条，不建数据库也成立。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 幕 1：TelemetryLlm 装饰器 ------------------------------------------------

    private static void actInstrumentedGraph(MetricsRegistry metrics, Tracer tracer) throws Exception {
        System.out.println("\n── 幕 1：ReflectionGraph 包上 TelemetryLlm，指标全自动 ──");

        // 顺利剧本：初稿 → 挑毛病 → 二稿 → reflector 放行 → judge 复核 YES（共 5 次调用）
        ScriptedLlm scripted = new ScriptedLlm()
                .says("一口下去，精神百倍")
                .says("建议：没有体现低温慢萃。评分 5/10。")
                .says("十二小时低温慢萃，时间酿出的香醇。")
                .says("语言精炼、卖点清晰。ACCEPT")
                .says("YES");
        CountingLlm counting = new CountingLlm(new TelemetryLlm(scripted, metrics, "reflection-graph"));

        var graph = new ReflectionGraph(counting, 3, counting).build().compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        var raw = new TrajectoryCollector<>(graph).run(
                Map.<String, Object>of("task", "给冷萃咖啡液写一句广告词"),
                org.bsc.langgraph4j.RunnableConfig.builder()
                        .threadId("telemetry-demo").build());

        long calls = metrics.counterValue("llm.calls{label=reflection-graph}");
        long errors = metrics.counterValue("llm.errors{label=reflection-graph}");
        long inflight = metrics.counterValue("llm.inflight{label=reflection-graph}");
        long promptChars = metrics.counterValue("llm.promptChars");
        System.out.println("  观测：calls=" + calls + " errors=" + errors
                + " 在飞(收尾应归零)=" + inflight + " promptChars=" + promptChars);
        System.out.println("  对照：CountingLlm.calls=" + counting.calls()
                + " 终态=" + raw.finalState().map(s -> s.<String>value("verdict").orElse("")).orElse("(无)"));

        check("装饰器计数与 CountingLlm 一致（一个不差）", calls == counting.calls() && calls == 5);
        check("顺利剧本零失败", errors == 0);
        check("在飞调用数收尾归零（+1/-1 配对）", inflight == 0);
        check("prompt 字符量有攒到（成本代理量）", promptChars > 0);

        // 故障剧本：委托抛异常 → errors +1，异常原样穿透
        ScriptedLlm flaky = new ScriptedLlm().says("初稿");
        TelemetryLlm watched = new TelemetryLlm(flaky, metrics, "flaky-path");
        long before = metrics.counterValue("llm.errors{label=flaky-path}");
        try {
            watched.complete("第二次调用会弹尽");
            watched.complete("弹尽即抛");
        } catch (IllegalStateException expected) {
            // 弹尽抛异常 = 观测目标本身
        }
        check("委托抛异常计入 errors 且原样穿透",
                metrics.counterValue("llm.errors{label=flaky-path}") == before + 1);
    }

    // ---- 幕 2：Prometheus 导出 + gauge ---------------------------------------------

    private static void actExport(MetricsRegistry metrics, AgentConfig config) throws Exception {
        System.out.println("\n── 幕 2：Prometheus 文本导出 + gauge（在飞调用数）──");

        // gauge 实时读计数器：值不是攒出来的，是随时读出来的
        metrics.gauge("llm.inflight.current",
                () -> metrics.counterValue("llm.inflight{label=reflection-graph}"));

        String prom = metrics.exportPrometheus();
        Path out = config.runsDir().resolve("telemetry-metrics.prom");
        Files.createDirectories(out.getParent());
        Files.writeString(out, prom);
        System.out.println("  导出 → " + out + "（" + prom.lines().count() + " 行）");
        prom.lines().forEach(l -> System.out.println("    " + l));

        check("计数器导出 _total 且带标签",
                prom.contains("llm_calls{label=\"reflection-graph\"} 5"));
        check("计时器导出 _count/_sum/_max", prom.contains("llm_duration{label=\"reflection-graph\"}_count 5")
                && prom.contains("_max"));
        check("gauge 原样导出且收尾为 0", prom.contains("llm_inflight_current 0"));
    }

    // ---- 幕 3：Tracer span 树 + JSONL 导出 ------------------------------------------

    private static void actTrace(Tracer tracer, AgentConfig config) throws Exception {
        System.out.println("\n── 幕 3：span 树（run 根 + LLM 子 span）→ JSONL，traceId 下钻 ──");

        // 模拟一次带两轮 LLM 的运行：根 span「run」下挂两条「llm-call」子 span
        try (Tracer.SpanHandle run = tracer.startSpan("reflection-run").tag("runner", "reflection-graph")) {
            run.log("图编译完成，开始 invoke");
            llmSpan(tracer, run, "generator 初稿");
            llmSpan(tracer, run, "reflector 批语");
            run.log("路由：ACCEPT → 定稿");
        }

        // 导出是追加语义（跨进程攒 trace 用），演示前先清掉上一轮的文件
        Files.deleteIfExists(config.runsDir().resolve("telemetry-traces.jsonl"));
        Path out = config.runsDir().resolve("telemetry-traces.jsonl");
        tracer.exportJsonl(out);
        List<String> lines = Files.readAllLines(out);
        System.out.println("  导出 → " + out + "（" + lines.size() + " 条 span）");

        // 用 traceId 把这条链捞回来：父一条、子两条，父子关系靠 parentId 串。
        // 注意 span 按结束顺序落盘（子先父后），根要按 parentId==null 找，不能按下标
        String traceId = tracer.spans().get(0).traceId();
        List<Tracer.Span> chain = tracer.trace(traceId);
        Tracer.Span root = chain.stream().filter(s -> s.parentId() == null).findFirst().orElseThrow();
        long roots = chain.stream().filter(s -> s.parentId() == null).count();
        long children = chain.stream().filter(s -> s.parentId() != null).count();
        System.out.println("  traceId=" + traceId.substring(0, 8) + "… 根 span=" + roots + " 子 span=" + children);
        System.out.println("  根 span 事件：" + root.logs());

        check("导出文件一行一条且条数对上", lines.size() == tracer.spanCount() && lines.size() == 3);
        check("traceId 能捞回完整链（1 根 + 2 子）", roots == 1 && children == 2);
        check("子 span 的 parentId 指向根", chain.stream().filter(s -> s.parentId() != null)
                .allMatch(s -> s.parentId().equals(root.spanId())));
        check("根 span 带事件日志（排障锚点）", root.logs().size() == 2);
    }

    private static void llmSpan(Tracer tracer, Tracer.SpanHandle parent, String name) {
        try (Tracer.SpanHandle span = tracer.startSpan(name, parent).tag("kind", "llm-call")) {
            sleep(15);
            span.log("拿到答复");
        }
    }

    // ---- 小工具 ---------------------------------------------------------------------

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
