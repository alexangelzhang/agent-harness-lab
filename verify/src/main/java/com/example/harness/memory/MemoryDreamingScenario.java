package com.example.harness.memory;

import java.util.List;
import java.util.Set;

/**
 * 离线记忆整理：残差写入 → 证据纠错与版本保护 → Wiki 表示 →
 * 有界索引 → 按后续需要和使用评分。
 *
 * <p>运行：{@code mvn exec:java@memorydream}。全程使用确定性规则，不调用模型。</p>
 */
public final class MemoryDreamingScenario {

    private static final long DAY_MS = 24L * 3600 * 1000;

    private MemoryDreamingScenario() {
    }

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("记忆 Dreaming：留下残差，按证据整理，按需取回");
        System.out.println(line);

        Fixture fixture = scenarioResidualWrite();
        boolean correctionOk = scenarioCorrectionAndRevision(fixture.store(), fixture.dreamer());
        boolean wikiOk = scenarioWiki(fixture);
        boolean budgetOk = scenarioContextBudget();
        boolean predictionOk = scenarioPrediction(fixture.note());

        boolean ok = fixture.ok() && correctionOk && wikiOk && budgetOk && predictionOk;
        System.out.println();
        System.out.println(line);
        System.out.println(ok
                ? "收尾：五幕断言全绿；原始记录仍是证据，索引和 Wiki 都只是导航。"
                : "!! 记忆 Dreaming 闭环没走通");
        System.out.println(line);
        System.exit(ok ? 0 : 1);
    }

    /** 第一幕：当前状态重新读环境，长期库只收不可重观测的经验。 */
    private static Fixture scenarioResidualWrite() {
        header("第一幕：残差写入", "当前金币重新查，失败经验才留下");
        MemoryStore store = new MemoryStore();
        MemoryTranscript transcript = new MemoryTranscript(List.of(
                "现场状态：当前金币 30，下一次读取可能变化。",
                "用户复查：出口在东侧，旧记录有误。",
                "连续三次失败后调整节奏，第四次通过。",
                "原始记录继续保留，Wiki 只负责导航，正文按需检索。"));
        MemoryWiki wiki = new MemoryWiki(store, transcript, 0.8);
        MemoryDreamer dreamer = new MemoryDreamer(store,
                new ResidualMemoryGate(60_000), transcript, wiki);

        MemoryDreamer.Decision state = dreamer.createNote(Memory.Scope.PROJECT,
                "示例项目", "当前金币 30", "现场状态", List.of("金币"), 0.9,
                Memory.KnowledgeState.OBSERVED, true, DAY_MS);
        MemoryDreamer.Decision residual = dreamer.createNote(Memory.Scope.PROJECT,
                "示例项目", "连续三次失败后调整节奏", "运行复盘", List.of("重试"), 0.9,
                Memory.KnowledgeState.VERIFIED, false, DAY_MS);
        System.out.println("  状态候选：" + state.reason());
        System.out.println("  残差候选：" + residual.reason() + " → " + residual.memory().id());

        boolean ok = !state.accepted() && residual.accepted() && store.all().size() == 1;
        System.out.println("  断言：" + (ok ? "通过，状态被拦且残差入库" : "失败"));
        return new Fixture(ok, store, dreamer, residual.memory());
    }

    /** 第二幕：日志有反证才能改，旧版本不能覆盖新结果。 */
    private static boolean scenarioCorrectionAndRevision(MemoryStore store,
                                                          MemoryDreamer dreamer) {
        header("第二幕：证据纠错与版本冲突", "没证据不改，拿旧版本也不改");
        MemoryDreamer.Decision created = dreamer.createNote(Memory.Scope.PROJECT,
                "示例项目", "出口在西侧", "旧巡检", List.of("出口"), 0.8,
                Memory.KnowledgeState.OBSERVED, false, DAY_MS);
        String id = created.memory().id();

        boolean missingRejected = !dreamer.correct(id, 0,
                "出口在南侧", "日志里不存在的反证").accepted();
        boolean unchangedAfterMissing = store.get(id).content().equals("出口在西侧")
                && store.get(id).revision() == 0;
        MemoryDreamer.Decision corrected = dreamer.correct(id, 0,
                "出口在东侧", "出口在东侧");
        boolean staleRejected = !dreamer.correct(id, 0,
                "出口在南侧", "出口在南侧").accepted();
        Memory current = store.get(id);
        System.out.println("  无反证：拒绝，原文与版本不变");
        System.out.println("  有反证：" + corrected.reason() + " → " + current.content()
                + " / " + current.knowledgeState() + " / 版本 " + current.revision());
        System.out.println("  旧版本再写：拒绝，当前内容仍是「" + current.content() + "」");

        MemoryDreamer.Decision inferred = dreamer.createNote(Memory.Scope.PROJECT,
                "示例项目", "失败后可能应调整节奏", "模型推断", List.of("节奏"), 0.4,
                Memory.KnowledgeState.INFERRED, false, DAY_MS);
        boolean promoteWithoutEvidence = !dreamer.promote(inferred.memory().id(), 0,
                "没有这段确认").accepted();
        MemoryDreamer.Decision promoted = dreamer.promote(inferred.memory().id(), 0,
                "连续三次失败后调整节奏");
        System.out.println("  推断上提：无证据拒绝；有证据后 "
                + store.get(inferred.memory().id()).status());

        return missingRejected && unchangedAfterMissing && corrected.accepted()
                && staleRejected && current.content().equals("出口在东侧")
                && current.revision() == 1 && promoteWithoutEvidence && promoted.accepted();
    }

    /** 第三幕：Wiki 是来源明确的表示层，断链只能指向已有页面。 */
    private static boolean scenarioWiki(Fixture fixture) {
        header("第三幕：Wiki 表示与断链修复", "导航可以压缩，证据不能丢");
        List<String> sourceIds = fixture.store().all().stream().map(Memory::id).toList();
        MemoryWiki.Decision index = fixture.dreamer().buildRepresentation("index",
                "现场经验索引", sourceIds, List.of());
        MemoryWiki.Decision detail = fixture.dreamer().buildRepresentation("detail",
                "按主题导航，正文仍按需检索", sourceIds, List.of("missing"));
        MemoryWiki.Decision badRepair = fixture.dreamer().repairLink(
                "detail", "missing", "still-missing");
        MemoryWiki.Decision repaired = fixture.dreamer().repairLink(
                "detail", "missing", "index");
        System.out.println("  建页：" + index.reason() + "；" + detail.reason());
        System.out.println("  不存在的重定向目标：" + badRepair.reason());
        System.out.println("  修复后：" + repaired.reason());

        return index.accepted() && detail.accepted() && !badRepair.accepted()
                && repaired.accepted();
    }

    /** 第四幕：顶层只带索引，行数超限时整行省略并留痕。 */
    private static boolean scenarioContextBudget() {
        header("第四幕：聚焦注入", "顶层放指针，正文等检索");
        MemoryStore store = new MemoryStore();
        store.add(Memory.Scope.PROJECT, "示例项目", "出口在东侧", "巡检", List.of("出口"));
        store.add(Memory.Scope.PROJECT, "示例项目", "失败后调整节奏", "复盘", List.of("重试"));
        store.add(Memory.Scope.PROJECT, "示例项目", "发布前先跑全量回归", "规范", List.of("发布"));
        RecallPipeline pipeline = new RecallPipeline(new MemoryMetrics(), new MemoryJanitor(),
                new MemoryContextBudget(2, 1024));
        RecallPipeline.RecallResult result = pipeline.recall("继续处理出口 重试 发布",
                List.of(), store, KnowledgeBase.NONE, List.of());
        result.contextLines().forEach(line -> System.out.println("  " + line));
        result.notes().stream().filter(note -> note.contains("顶层注入预算"))
                .forEach(note -> System.out.println("  " + note));

        boolean noBody = !result.contextBlock().contains("出口在东侧")
                && !result.contextBlock().contains("失败后调整节奏")
                && !result.contextBlock().contains("发布前先跑全量回归");
        return result.contextLines().size() == 2 && noBody
                && result.contextBlock().contains("[长期索引]")
                && result.notes().stream().anyMatch(note -> note.contains("整行省略 1 条"));
    }

    /** 第五幕：写入两条、后来用一条；需要两项、当时写下一项。 */
    private static boolean scenarioPrediction(Memory residual) {
        header("第五幕：预测评分", "文件数量不算收益，后续使用才算");
        MemoryPredictionBench.Episode episode = new MemoryPredictionBench.Episode(
                Set.of(residual.id(), "mem-unused"), Set.of(residual.id(), "mem-missing"),
                Set.of(residual.id()));
        MemoryPredictionBench.Score score = new MemoryPredictionBench()
                .evaluate(List.of(episode));
        System.out.println("  写入 " + score.writtenCount() + " 条，实际使用 "
                + score.usedCount() + " 条 → precision=" + score.precision());
        System.out.println("  后续需要 " + score.neededCount() + " 项，当时已写 "
                + score.neededAndWrittenCount() + " 项 → recall=" + score.recall());
        return score.precision() == 0.5 && score.recall() == 0.5;
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }

    private record Fixture(boolean ok, MemoryStore store, MemoryDreamer dreamer, Memory note) {
    }
}
