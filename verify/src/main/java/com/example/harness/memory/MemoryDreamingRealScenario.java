package com.example.harness.memory;

import com.example.harness.config.AgentConfig;
import com.example.harness.probe.ProtocolProbe;
import com.example.langgraph4j.llm.JdkHttpLlm;
import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.RetryingLlm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆治理的真实同题对照：纠错、遗忘各比较无记忆 / 未治理 / 治理后三组，
 * 上下文预算比较全文 / 三条索引两组。行为分类只报告，实验结构必须完整。
 *
 * <p>运行：{@code scripts/dev.sh memorydreamreal "[1-3] [runs]"}，
 * 默认每组三遍。每遍最多四轮，只有合法 {@code Finish} 才进入答案判分。</p>
 */
public final class MemoryDreamingRealScenario {

    private static final int MAX_ROUNDS = 4;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final Set<String> TOOLS = Set.of("read_memory", "Finish");
    private static final Set<String> ABSTENTIONS = Set.of("不知道", "无法确定");
    private static final Pattern ACTION = Pattern.compile(
            "\\A\\s*Action:\\s*([A-Za-z0-9_]+)\\s*\\[(.*)\\]\\s*\\z",
            Pattern.DOTALL);
    private static final MemoryAnswerBench ANSWER_BENCH = new MemoryAnswerBench();

    private static final String PROTOCOL = """
            你是记忆对照实验的答题器。可用动作只有：
            - read_memory[记忆id]：读取当前记忆中明确列出的一个 id 的完整正文
            - Finish[最终答案]：结束并提交答案
            每轮必须只输出一行，严格写成 Action: 动作名[参数]，不要 Markdown，
            不要思考过程，不要额外解释。只能依据提供的记忆和 Observation 回答，
            不能用常识补全。只能读取当前记忆区明确列出的 id，不要猜 id。
            信息不足时必须输出 Action: Finish[不知道]。
            """;

    private MemoryDreamingRealScenario() {
    }

    private record Arm(String label, String task, String expected, Set<String> forbidden,
                       Supplier<PreparedMemory> fixture) {
    }

    private record PreparedMemory(MemoryStore store, List<String> contextLines,
                                  Set<String> visibleIds, String setup) {
        private PreparedMemory {
            contextLines = List.copyOf(contextLines);
            visibleIds = Set.copyOf(visibleIds);
        }

        private String contextBlock() {
            return String.join("\n", contextLines);
        }
    }

    private record RunOutcome(MemoryAnswerBench.Category category, int calls, int reads,
                              long initialBytes, long requestBytes) {
    }

    /** 统计实际发送次数与请求字节；放在重试层内，瞬时重试也计入真实成本。 */
    private static final class MeteredLlm implements LlmClient {
        private final LlmClient delegate;
        private int calls;
        private long requestBytes;

        private MeteredLlm(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public String complete(String prompt) {
            calls++;
            requestBytes += utf8Bytes(prompt);
            return delegate.complete(prompt);
        }
    }

    public static void main(String[] args) {
        int pick;
        int runs;
        try {
            if (args.length > 2) {
                throw new IllegalArgumentException("参数过多");
            }
            pick = args.length >= 1 ? Integer.parseInt(args[0]) : 0;
            runs = args.length == 2 ? Integer.parseInt(args[1]) : 3;
            if (pick < 0 || pick > 3) {
                throw new IllegalArgumentException("分片号必须是 1、2、3，或 0（全部）");
            }
            if (runs <= 0) {
                throw new IllegalArgumentException("重复次数必须是正整数");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage() + "；用法：[1-3] [runs]，0 表示全部");
            System.exit(2);
            return;
        }

        AgentConfig config = AgentConfig.load();
        config.requireApiKey();
        System.out.println("=".repeat(66));
        System.out.println("记忆 Dreaming 真实 A/B：纠错 / 遗忘 / 上下文预算");
        System.out.println("模型：" + config.model() + "；分片：" + pick + "；每臂重复：" + runs);
        System.out.println("=".repeat(66));

        ProtocolProbe.Result preflight = ProtocolProbe.require(
                new RetryingLlm(new JdkHttpLlm()), PROTOCOL, TOOLS);
        System.out.println("[协议预检] " + preflight.reason());

        int completed = 0;
        if (pick == 0 || pick == 1) {
            completed += runExperiment("纠错：旧值是否还污染答案", correctionArms(), runs);
        }
        if (pick == 0 || pick == 2) {
            completed += runExperiment("遗忘：过期时间是否还污染答案", forgettingArms(), runs);
        }
        if (pick == 0 || pick == 3) {
            completed += runExperiment("预算：全文与三条索引的成本", budgetArms(), runs);
        }
        int expected = runs * (pick == 0 ? 8 : pick == 3 ? 2 : 3);
        if (completed != expected) {
            throw new IllegalStateException("结构断言失败：完成 " + completed + " 遍，应为 " + expected);
        }
        System.out.println("\n结构断言通过：" + completed + " 遍均以合法 Finish 收口，"
                + "每遍不超过 " + MAX_ROUNDS + " 轮；行为高低不设退出门槛。");
    }

    private static int runExperiment(String title, List<Arm> arms, int runs) {
        System.out.println("\n\n## " + title);
        int completed = 0;
        for (Arm arm : arms) {
            List<RunOutcome> outcomes = new ArrayList<>();
            System.out.println("\n### 对照臂：" + arm.label);
            for (int run = 1; run <= runs; run++) {
                outcomes.add(runOnce(arm, run));
            }
            printSummary(outcomes);
            completed += outcomes.size();
        }
        return completed;
    }

    private static RunOutcome runOnce(Arm arm, int run) {
        PreparedMemory prepared = arm.fixture().get();
        MeteredLlm meter = new MeteredLlm(new JdkHttpLlm());
        LlmClient brain = new RetryingLlm(meter);
        String memoryBlock = prepared.contextBlock();
        long initialBytes = utf8Bytes(memoryBlock);
        StringBuilder prompt = new StringBuilder(initialPrompt(arm.task(), memoryBlock));

        System.out.println("\n  第 " + run + " 遍｜" + prepared.setup());
        String answer = null;
        int reads = 0;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            String output = brain.complete(prompt.toString()).strip();
            System.out.println("    round " + round + " 原始动作：" + oneLine(output));
            Matcher matcher = ACTION.matcher(output);
            if (!matcher.matches()) {
                throw new IllegalStateException(arm.label() + " 第 " + run
                        + " 遍未按 Action 协议输出，实验中止");
            }
            String action = matcher.group(1);
            String argument = matcher.group(2).strip();
            if ("Finish".equals(action)) {
                answer = argument;
                break;
            }
            if (!"read_memory".equals(action)) {
                throw new IllegalStateException(arm.label() + " 第 " + run
                        + " 遍使用未知动作 " + action + "，实验中止");
            }
            reads++;
            String observation = readMemory(prepared, argument);
            System.out.println("    Observation：" + oneLine(observation));
            prompt.append("\n\n").append(output).append("\nObservation: ").append(observation);
        }
        if (answer == null) {
            throw new IllegalStateException(arm.label() + " 第 " + run + " 遍超过 "
                    + MAX_ROUNDS + " 轮仍未 Finish，实验中止");
        }

        MemoryAnswerBench.Category category = ANSWER_BENCH.classify(
                answer, arm.expected(), arm.forbidden(), ABSTENTIONS);
        System.out.println("    ⇒ " + categoryLabel(category)
                + "｜read_memory " + reads + " 次｜LLM " + meter.calls
                + " 次｜初始记忆 " + initialBytes + " 字节｜累计请求 "
                + meter.requestBytes + " 字节");
        return new RunOutcome(category, meter.calls, reads, initialBytes, meter.requestBytes);
    }

    private static String initialPrompt(String task, String memoryBlock) {
        return PROTOCOL + "\n=== 当前记忆 ===\n"
                + (memoryBlock.isEmpty() ? "（没有提供记忆）" : memoryBlock)
                + "\n\n=== 问题 ===\n" + task;
    }

    private static String readMemory(PreparedMemory prepared, String id) {
        if (!prepared.visibleIds().contains(id)) {
            return "id=" + id + " 不在当前可见记忆中，不能读取；没有足够信息时请 Finish[不知道]。";
        }
        Memory memory = prepared.store().get(id);
        if (memory == null) {
            throw new IllegalStateException("可见 id 在记忆库中不存在：" + id);
        }
        return fullLine(memory);
    }

    private static void printSummary(List<RunOutcome> outcomes) {
        long initialTotal = outcomes.stream().mapToLong(RunOutcome::initialBytes).sum();
        long requestTotal = outcomes.stream().mapToLong(RunOutcome::requestBytes).sum();
        int calls = outcomes.stream().mapToInt(RunOutcome::calls).sum();
        int reads = outcomes.stream().mapToInt(RunOutcome::reads).sum();
        int n = outcomes.size();
        System.out.println("  汇总｜答对 " + count(outcomes, MemoryAnswerBench.Category.CORRECT)
                + "/" + n + "｜污染 " + count(outcomes, MemoryAnswerBench.Category.CONTAMINATED)
                + "/" + n + "｜拒答 " + count(outcomes, MemoryAnswerBench.Category.ABSTAINED)
                + "/" + n + "｜其他错误 " + count(outcomes, MemoryAnswerBench.Category.WRONG)
                + "/" + n);
        System.out.println("  成本｜LLM 共 " + calls + " 次｜read_memory 共 " + reads
                + " 次｜初始记忆共 " + initialTotal + " 字节，算术平均 "
                + average(initialTotal, n) + "｜累计请求共 " + requestTotal
                + " 字节，算术平均 " + average(requestTotal, n));
    }

    private static long count(List<RunOutcome> outcomes, MemoryAnswerBench.Category category) {
        return outcomes.stream().filter(outcome -> outcome.category() == category).count();
    }

    private static String average(long total, int count) {
        return String.format(Locale.ROOT, "%.1f 字节", (double) total / count);
    }

    private static List<Arm> correctionArms() {
        String task = "虚构项目砾石的当前标识是什么？只答完整标识。";
        return List.of(
                new Arm("无记忆", task, "EAST-42", Set.of("NORTH-17"),
                        MemoryDreamingRealScenario::emptyMemory),
                new Arm("旧版本全文", task, "EAST-42", Set.of("NORTH-17"),
                        MemoryDreamingRealScenario::correctionOld),
                new Arm("证据纠错后全文", task, "EAST-42", Set.of("NORTH-17"),
                        MemoryDreamingRealScenario::correctionGoverned));
    }

    private static MemoryStore correctionStore() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "砾石", "项目砾石的当前标识是 NORTH-17",
                "砾石旧部署单", List.of("砾石", "项目标识"), 0.9, 0, List.of());
        return store;
    }

    private static PreparedMemory correctionOld() {
        return fullMemory(correctionStore(), "旧版本全文注入，未做纠错");
    }

    private static PreparedMemory correctionGoverned() {
        MemoryStore store = correctionStore();
        String evidence = "项目砾石的当前标识是 EAST-42";
        MemoryTranscript transcript = new MemoryTranscript(List.of(
                "2026-11-09 部署复核：" + evidence + "，旧值 NORTH-17 作废。"));
        MemoryDreamer dreamer = new MemoryDreamer(store, new ResidualMemoryGate(60_000),
                transcript, new MemoryWiki(store, transcript, 0.8));
        Memory old = store.all().get(0);
        MemoryDreamer.Decision decision = dreamer.correct(
                old.id(), old.revision(), evidence, evidence);
        if (!decision.accepted() || decision.memory().revision() != 1) {
            throw new IllegalStateException("纠错夹具未通过证据与版本检查");
        }
        return fullMemory(store, "MemoryDreamer 已依据 transcript 纠错，版本 0 → 1");
    }

    private static List<Arm> forgettingArms() {
        String task = "项目例会的当前时间是什么？只答完整时间。";
        return List.of(
                new Arm("无记忆", task, "周五 16:40", Set.of("周二 09:20"),
                        MemoryDreamingRealScenario::emptyMemory),
                new Arm("过期与有效全文", task, "周五 16:40", Set.of("周二 09:20"),
                        MemoryDreamingRealScenario::forgettingUngoverned),
                new Arm("清理后全文", task, "周五 16:40", Set.of("周二 09:20"),
                        MemoryDreamingRealScenario::forgettingGoverned));
    }

    private static MemoryStore forgettingStore() {
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "示例项目", "项目例会时间是周二 09:20",
                "过期会议通知", List.of("例会", "时间"), 0.9,
                MemoryStore.now() - DAY_MS, List.of());
        store.add(Memory.Scope.PROJECT, "示例项目", "项目例会时间是周五 16:40",
                "当前会议通知", List.of("例会", "当前"), 0.95, 0, List.of());
        return store;
    }

    private static PreparedMemory forgettingUngoverned() {
        return fullMemory(forgettingStore(), "未治理：过期时间与当前时间同时全文注入");
    }

    private static PreparedMemory forgettingGoverned() {
        MemoryStore store = forgettingStore();
        MemoryJanitor.Report report = new MemoryJanitor().sweep(store);
        List<Memory> active = store.all().stream()
                .filter(memory -> memory.injectableFor(List.of())).toList();
        if (report.expired() != 1 || active.size() != 1
                || !active.get(0).content().contains("周五 16:40")) {
            throw new IllegalStateException("遗忘夹具未清掉过期时间");
        }
        return fullMemory(store, active, "MemoryJanitor 清理后只注入当前时间");
    }

    private static List<Arm> budgetArms() {
        String task = "虚构项目岫岩的当前交付标识是什么？只答完整标识。";
        return List.of(
                new Arm("全部正文", task, "ZX-731", Set.of(),
                        MemoryDreamingRealScenario::budgetFull),
                new Arm("三条索引", task, "ZX-731", Set.of(),
                        MemoryDreamingRealScenario::budgetIndex));
    }

    private static MemoryStore budgetStore() {
        String[] projects = {"岫岩", "岚川", "青屿", "澄江", "望舒", "松涛",
                "临渊", "昭野", "白鹿", "栖霞", "云壤", "星桥", "鹤亭", "苍梧",
                "澜庭", "墨丘", "闻笙", "锦川"};
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < projects.length; i++) {
            boolean target = i == 0;
            String identifier = target ? "ZX-731" : "QX-" + (101 + i);
            store.add(Memory.Scope.PROJECT, projects[i],
                    "项目" + projects[i] + "的当前交付标识是 " + identifier,
                    projects[i] + "交付登记",
                    target ? List.of("岫岩", "交付标识", "已定") : List.of(projects[i]),
                    0.9, 0, List.of());
        }
        return store;
    }

    private static PreparedMemory budgetFull() {
        return fullMemory(budgetStore(), "18 条记忆全部正文注入");
    }

    private static PreparedMemory budgetIndex() {
        MemoryStore store = budgetStore();
        String task = "虚构项目岫岩的当前交付标识是什么？只答完整标识。";
        RecallPipeline.RecallResult recalled = new RecallPipeline(new MemoryMetrics(),
                new MemoryJanitor(), new MemoryContextBudget(3, 16 * 1024))
                .recall(task, List.of(), store, KnowledgeBase.NONE, List.of());
        if (recalled.contextLines().size() != 3) {
            throw new IllegalStateException("预算夹具应产生三条索引，实际为 "
                    + recalled.contextLines().size());
        }
        Set<String> visible = visibleIds(store, recalled.contextLines());
        boolean targetVisible = visible.contains(store.all().get(0).id());
        return new PreparedMemory(store, recalled.contextLines(), visible,
                "RecallPipeline + MemoryContextBudget 注入三条索引；目标在索引中：" + targetVisible);
    }

    private static PreparedMemory emptyMemory() {
        return fullMemory(new MemoryStore(), "没有项目事实，无可见 id");
    }

    private static PreparedMemory fullMemory(MemoryStore store, String setup) {
        return fullMemory(store, store.all(), setup);
    }

    private static PreparedMemory fullMemory(MemoryStore store, List<Memory> memories,
                                             String setup) {
        List<String> lines = memories.stream().map(MemoryDreamingRealScenario::fullLine).toList();
        return new PreparedMemory(store, lines, visibleIds(store, lines), setup);
    }

    private static Set<String> visibleIds(MemoryStore store, List<String> contextLines) {
        Set<String> ids = new LinkedHashSet<>();
        for (Memory memory : store.all()) {
            String marker = "id=" + memory.id() + " |";
            if (contextLines.stream().anyMatch(line -> line.contains(marker))) {
                ids.add(memory.id());
            }
        }
        return ids;
    }

    private static String fullLine(Memory memory) {
        return "id=" + memory.id() + " | 正文=" + memory.content() + " | 来源=" + memory.source();
    }

    private static long utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String categoryLabel(MemoryAnswerBench.Category category) {
        return switch (category) {
            case CORRECT -> "CORRECT（答对）";
            case CONTAMINATED -> "CONTAMINATED（含禁用值）";
            case ABSTAINED -> "ABSTAINED（拒答）";
            case WRONG -> "WRONG（其他错误）";
        };
    }

    private static String oneLine(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
