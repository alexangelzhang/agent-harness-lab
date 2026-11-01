package com.example.multiagent;

import com.example.harness.toolbase.SchemaTool;
import com.example.langgraph4j.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Supervisor 模式（多 Agent 架构选型之二）：<b>一个中央协调 Agent 拿总</b>，
 * 把任务拆开、分派给专家 Agent、再把结果收拢综合。
 *
 * <p>适合「子任务边界需要临场判断」的场景——主管每次调用都重新看全局，
 * 比写死的 Workflow 灵活。代价：主管是一层额外延迟和成本，
 * 专家越多越要警惕把它用成昂贵的路由器。</p>
 *
 * <p>文章（智能体AI《多 Agent 不是单 Agent 的升级版》）点名的关键变体是
 * {@link #dispatchViaTools}：把每个专家包成主管的<b>工具</b>而不是平级
 * Agent——主管不再「指挥」专家，只是在自己会的工具里多了一类
 * 「问专家」。结构差异在 demo 里并列演示。</p>
 *
 * <p>分派协议是文本约定：主管输出 {@code 分派：<专家> | <子任务>} 行
 * （工具变体为 {@code CALL <工具>：<问题>}），解析后逐个执行，
 * 再交给 synthesizer 综合成最终答复。</p>
 */
public final class SupervisorPattern {

    /** 一个专家：名字 + 一句话专长 + 它自己的模型。 */
    public record Expert(String name, String specialty, LlmClient brain) {
    }

    private SupervisorPattern() {
    }

    /**
     * 经典分派：主管拆任务 → 逐个派给平级专家 → 主管综合。
     *
     * <p>主管输出「分派：」行里点名的专家必须在花名册内，点错名的行
     * 记进 trace 后跳过，不中断整轮。</p>
     *
     * @param task 原始任务描述
     * @param roster 可用专家花名册（名字唯一，主管按名字点名）
     * @param supervisor 主管模型，收到任务+花名册后输出「分派：」行
     * @param synthesizer 综合模型，收到全部子结果后输出最终答复
     * @param trace 过程留痕回调（谁、做了什么/答了什么）
     * @return 综合模型产出的最终答复
     */
    public static String dispatch(String task, List<Expert> roster, LlmClient supervisor,
                                  LlmClient synthesizer, BiConsumer<String, String> trace) {
        StringBuilder manifest = new StringBuilder();
        for (Expert e : roster) {
            manifest.append("- ").append(e.name()).append("：").append(e.specialty()).append('\n');
        }
        String plan = supervisor.complete("任务：" + task + "\n可用专家：\n" + manifest
                + "请把任务拆给合适的专家，每行输出：分派：<专家> | <子任务>");
        Map<String, Expert> byName = roster.stream()
                .collect(Collectors.toMap(Expert::name, e -> e));

        StringBuilder bundle = new StringBuilder("原始任务：" + task + "\n\n各专家结果：\n");
        for (String line : plan.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("分派：")) {
                continue;
            }
            String[] parts = trimmed.substring("分派：".length()).split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            String who = parts[0].trim();
            String query = parts[1].trim();
            Expert expert = byName.get(who);
            if (expert == null) {
                trace.accept("主管", "跳过不认识的专家：" + who);
                continue;
            }
            String result = expert.brain().complete(query);
            trace.accept("分派→" + expert.name(), query + " ⇒ " + result);
            bundle.append("[").append(expert.name()).append("] ").append(result).append('\n');
        }
        return synthesizer.complete(bundle.toString() + "\n请综合成一份最终答复。");
    }

    /**
     * 并行 fan-out：主管拆完任务后，各专家<b>同时开工</b>而不是排队等，
     * 全部到齐再综合。墙钟时间从「各专家耗时之和」降到「最慢那个的耗时」。
     *
     * <p>两条生产纪律：①并发上限——线程池大小就是
     * {@code min(maxConcurrency, 子任务数)}，专家再多也不打爆调用配额；
     * ②故障隔离——单个专家调用抛异常只记一条「调用失败」进结果包，
     * 不拖死整轮（综合模型看得到哪块缺了，可以明说）。</p>
     *
     * <p>trace 在所有专家都返回之后按分派顺序统一回调——回调方
     * （如 {@link RunJournal}）大多不是线程安全的，不在 worker 线程里
     * 并发触它。</p>
     *
     * @param maxConcurrency 最大并行专家数（调用配额的安全阀）
     * @return 综合模型产出的最终答复
     */
    public static String dispatchParallel(String task, List<Expert> roster, LlmClient supervisor,
                                          LlmClient synthesizer, int maxConcurrency,
                                          BiConsumer<String, String> trace) {
        String plan = supervisor.complete("任务：" + task + "\n可用专家：\n" + manifestOf(roster)
                + "请把任务拆给合适的专家，每行输出：分派：<专家> | <子任务>");
        Map<String, Expert> byName = roster.stream()
                .collect(Collectors.toMap(Expert::name, e -> e));

        // 解析分派行成（专家, 子任务）清单，保持计划顺序
        List<String[]> subtasks = new ArrayList<>();
        for (String line : plan.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("分派：")) {
                continue;
            }
            String[] parts = trimmed.substring("分派：".length()).split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            String who = parts[0].trim();
            String query = parts[1].trim();
            if (!byName.containsKey(who)) {
                trace.accept("主管", "跳过不认识的专家：" + who);
                continue;
            }
            subtasks.add(new String[]{who, query});
        }

        int poolSize = Math.max(1, Math.min(maxConcurrency, subtasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<String[]>> futures = new ArrayList<>();
            for (String[] sub : subtasks) {
                futures.add(pool.submit(() -> {
                    Expert expert = byName.get(sub[0]);
                    try {
                        return new String[]{expert.name(), sub[1],
                                expert.brain().complete(sub[1])};
                    } catch (RuntimeException e) {
                        return new String[]{expert.name(), sub[1],
                                "[调用失败] " + e.getMessage()};
                    }
                }));
            }
            StringBuilder bundle = new StringBuilder("原始任务：" + task + "\n\n各专家结果：\n");
            for (Future<String[]> future : futures) {
                try {
                    String[] r = future.get();
                    trace.accept("分派→" + r[0], r[1] + " ⇒ " + r[2]);
                    bundle.append("[").append(r[0]).append("] ").append(r[2]).append('\n');
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待专家结果被中断", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("专家调度异常", e.getCause());
                }
            }
            return synthesizer.complete(bundle.toString() + "\n请综合成一份最终答复。");
        } finally {
            pool.shutdown();
        }
    }

    private static String manifestOf(List<Expert> roster) {
        StringBuilder manifest = new StringBuilder();
        for (Expert e : roster) {
            manifest.append("- ").append(e.name()).append("：").append(e.specialty()).append('\n');
        }
        return manifest.toString();
    }

    /**
     * Supervisor-as-Tools 变体：专家不再是平级 Agent，而是主管的
     * {@link SchemaTool}。主管的决策面从「指挥谁」收窄为「调哪个工具」，
     * 协议是 {@code CALL <工具>：<问题>}。
     *
     * <p>与 {@link #dispatch} 相同的容错约定：CALL 点名的工具必须在
     * 工具列表内，点错名的行记进 trace 后跳过。</p>
     *
     * @param task 原始任务描述
     * @param tools 可调用工具列表（通常由 {@link #asTool} 从专家包装而来）
     * @param supervisor 主管模型，收到任务+工具清单后输出 CALL 行
     * @param synthesizer 综合模型，收到全部调用结果后输出最终答复
     * @param trace 过程留痕回调
     * @return 综合模型产出的最终答复
     */
    public static String dispatchViaTools(String task, List<SchemaTool> tools, LlmClient supervisor,
                                          LlmClient synthesizer, BiConsumer<String, String> trace) {
        StringBuilder manifest = new StringBuilder();
        for (SchemaTool t : tools) {
            manifest.append(t.schemaManifest()).append('\n');
        }
        String plan = supervisor.complete("任务：" + task + "\n可调用的工具：\n" + manifest
                + "请决定调用哪些工具，每行输出：CALL <工具名>：<问题>");
        Map<String, SchemaTool> byName = tools.stream()
                .collect(Collectors.toMap(SchemaTool::name, t -> t));

        StringBuilder bundle = new StringBuilder("原始任务：" + task + "\n\n工具调用结果：\n");
        for (String line : plan.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.toUpperCase(Locale.ROOT).startsWith("CALL ")) {
                continue;
            }
            String body = trimmed.substring("CALL ".length());
            int colon = body.indexOf('：');
            if (colon < 0) {
                continue;
            }
            String who = body.substring(0, colon).trim();
            String query = body.substring(colon + 1).trim();
            SchemaTool tool = byName.get(who);
            if (tool == null) {
                trace.accept("主管", "跳过不认识的工具：" + who);
                continue;
            }
            String result = tool.observe(query);
            trace.accept("CALL " + tool.name(), query + " ⇒ " + result);
            bundle.append("[").append(tool.name()).append("] ").append(result).append('\n');
        }
        return synthesizer.complete(bundle.toString() + "\n请综合成一份最终答复。");
    }

    /**
     * 供外部（如决策器）把专家一键包成同名工具。
     *
     * @param expert 要包装的专家，工具名取其 {@code name}
     * @param params 工具的参数 schema（描述专家擅长回答什么形状的问题）
     */
    public static SchemaTool asTool(Expert expert, List<SchemaTool.Param> params) {
        return new SchemaTool(expert.name(), expert.specialty(), params,
                input -> expert.brain().complete(input));
    }

}
