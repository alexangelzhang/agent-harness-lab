package com.example.langgraph4j.eval;

import com.example.langgraph4j.llm.CountingLlm;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 评估 Harness：同一任务跑 k 次（每次重新编译图 + 独立 threadId，互不污染），
 * 统计《AI Agents: The Definitive Guide》Ch8 的核心指标：
 *
 * <ul>
 *   <li><b>Pass@k</b>：k 次运行中通过判定器的比例（BLEU/ROUGE 的替代品——只看任务完成率）</li>
 *   <li><b>步数</b>：平均 / 最少 / 最多，惩罚空转（与图的 recursionLimit 护栏直接对应）</li>
 *   <li><b>LLM 调用数</b>：成本代理指标（CountingLlm 接入时统计）</li>
 *   <li><b>轨迹冗余度</b>：步数 / 去重节点数，识别在环里空转的轨迹</li>
 * </ul>
 */
public final class EvalHarness {

    /** 一次试验的输入：图（每次重新编译）、初始输入、可选的计数 LLM。 */
    public interface Trial<S extends AgentState> {
        CompiledGraph<S> graph();

        Map<String, Object> inputs();

        /** 可选：接入计数 LLM 时返回它，报告里会多一列 LLM 调用数。 */
        default CountingLlm llm() {
            return null;
        }
    }

    public record Report(String name, int k, List<Trajectory> trajectories) {

        public long passCount() {
            return trajectories.stream().filter(Trajectory::passed).count();
        }

        public double passRate() {
            return trajectories.isEmpty() ? 0 : (double) passCount() / trajectories.size();
        }

        /** 打印逐次轨迹 + 汇总，对照书中 Ch8 的多维度量矩阵。 */
        public void print() {
            System.out.println("\n  ┌─ 评估报告：" + name + "（k=" + k + "）" + "─".repeat(Math.max(2, 50 - name.length())));
            for (Trajectory t : trajectories) {
                String verdict = t.errorMessage().isPresent() ? "ERROR" : (t.passed() ? "PASS" : "FAIL");
                System.out.printf("  │ [trial %d] %-5s 步数=%d 冗余度=%.1f LLM调用=%d 耗时=%dms%n",
                        t.trialIndex(), verdict, t.steps(), t.redundancy(), t.llmCalls(), t.durationMs());
                System.out.println("  │   路径: " + String.join(" → ", t.nodePath()));
                if (t.recursionLimitHit()) {
                    System.out.println("  │   ⚠ 触发 recursionLimit 护栏（图的步数上限，Ch8 步数惩罚的硬版本）");
                }
                t.errorMessage().ifPresent(e -> System.out.println("  │   错误: " + e));
                t.answer().ifPresent(a -> System.out.println("  │   答案: "
                        + (a.length() > 60 ? a.substring(0, 60) + "…" : a)));
            }
            int[] steps = trajectories.stream().mapToInt(Trajectory::steps).toArray();
            long limitHits = trajectories.stream().filter(Trajectory::recursionLimitHit).count();
            System.out.printf("  ├─ Pass@%d = %d/%d (%.0f%%)%n", k, passCount(), k, passRate() * 100);
            System.out.printf("  │   步数: 平均=%.1f 最少=%d 最多=%d  平均LLM调用=%.1f  recursionLimit命中=%d%n",
                    trajectories.stream().mapToInt(Trajectory::steps).average().orElse(0),
                    Arrays.stream(steps).min().orElse(0),
                    Arrays.stream(steps).max().orElse(0),
                    trajectories.stream().mapToInt(Trajectory::llmCalls).average().orElse(0),
                    limitHits);
            System.out.println("  └" + "─".repeat(58));
        }
    }

    /**
     * 跑 k 次评估。
     *
     * @param name            评估场景名（报告标题 / threadId 前缀）
     * @param k               运行次数（Pass@k 的 k）
     * @param trials          每次试验的工厂（必须返回全新编译的图与脚本）
     * @param answerExtractor 从终态提取答案文本（用于展示）
     * @param passCheck       通过判定（基于终态；用 state.value(...) 读取通道即可）
     */
    public static <S extends AgentState> Report evaluate(
            String name, int k,
            Supplier<Trial<S>> trials,
            Function<S, String> answerExtractor,
            Predicate<S> passCheck) {
        return evaluate(name, k, 1, trials, answerExtractor, passCheck);
    }

    /**
     * 跑 k 次评估，试验编号从 {@code startIndex} 开始——真实 LLM 评估耗时长，
     * 可以按编号分片多次调用（甚至跨进程），最后由调用方汇总 Pass@k。
     */
    public static <S extends AgentState> Report evaluate(
            String name, int k, int startIndex,
            Supplier<Trial<S>> trials,
            Function<S, String> answerExtractor,
            Predicate<S> passCheck) {

        List<Trajectory> trajectories = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            int trialIndex = startIndex + i;
            trajectories.add(runOne(name, trialIndex, trials.get(), answerExtractor, passCheck));
        }
        return new Report(name, k, trajectories);
    }

    /**
     * 并行版：k 次试验同时开跑（每次仍各自重新编译图 + 独立 threadId）。
     * 真实 LLM 单次调用动辄几十秒，串行 k=5 是分钟级——并行把墙钟压下来，
     * 但并发必须配限流（{@code RateLimitedLlm}），否则 k 个推理请求同时
     * 砸向网关，配额和额度双杀。试验间零共享（各自的图/脚本/计数器），
     * 并行不改变 Pass@k 的语义。
     *
     * @param executor 调用方给的线程池（尺寸 = 期望并发度；配额闸在 LLM 层）
     */
    public static <S extends AgentState> Report evaluateParallel(
            String name, int k, int startIndex, java.util.concurrent.ExecutorService executor,
            Supplier<Trial<S>> trials,
            Function<S, String> answerExtractor,
            Predicate<S> passCheck) {

        List<java.util.concurrent.Future<Trajectory>> futures = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            int trialIndex = startIndex + i;
            futures.add(executor.submit(
                    () -> runOne(name, trialIndex, trials.get(), answerExtractor, passCheck)));
        }
        List<Trajectory> trajectories = new ArrayList<>();
        for (var future : futures) {
            try {
                trajectories.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                trajectories.add(errorTrajectory(-1, "评估线程被中断"));
            } catch (java.util.concurrent.ExecutionException e) {
                trajectories.add(errorTrajectory(-1,
                        "trial 线程内异常：" + (e.getCause() == null ? e : e.getCause()).getMessage()));
            }
        }
        trajectories.sort(java.util.Comparator.comparingInt(Trajectory::trialIndex));
        return new Report(name, k, trajectories);
    }

    /** 单次试验：跑图 → 收轨迹。串行/并行两版共用。 */
    private static <S extends AgentState> Trajectory runOne(
            String name, int trialIndex, Trial<S> trial,
            Function<S, String> answerExtractor, Predicate<S> passCheck) {
        RunnableConfig config = RunnableConfig.builder()
                .threadId(name + "-trial-" + trialIndex).build();
        CountingLlm llm = trial.llm();
        var raw = new TrajectoryCollector<>(trial.graph()).run(trial.inputs(), config);
        Optional<String> answer = raw.finalState().map(answerExtractor).filter(a -> !a.isBlank());
        boolean pass = raw.errorMessage().isEmpty()
                && raw.finalState().map(passCheck::test).orElse(false);
        return new Trajectory(trialIndex, raw.nodePath(),
                llm == null ? -1 : llm.calls(), raw.durationMs(),
                answer, pass, raw.errorMessage(), raw.recursionLimitHit());
    }

    /** 试验级故障兜底：线程里炸了的 trial 变成一条 ERROR 轨迹，不拖垮整份报告。 */
    private static Trajectory errorTrajectory(int trialIndex, String message) {
        return new Trajectory(trialIndex, List.of(), -1, 0,
                Optional.empty(), false, Optional.ofNullable(message), false);
    }

    /**
     * 跑一组对抗探针并打印报告（AWS「对抗双胞胎」：该拒的没拒，探针失败）。
     * 与正向评估互补——正向看「该成的成」，探针看「该拒的拒」。
     */
    public static void probeReport(String title, List<? extends AdversarialProbe<?>> probes) {
        System.out.println("\n  ┌─ 对抗探针：" + title + "─".repeat(Math.max(2, 52 - title.length())));
        int passed = 0;
        for (var probe : probes) {
            var result = probe.run();
            if (result.denied()) {
                passed++;
            }
            System.out.printf("  │ [%s] %s — %s%n", result.denied() ? "拒-对" : "没拒!", result.name(),
                    result.actual());
        }
        System.out.printf("  ├─ 探针通过 %d/%d（该拒没拒 = 门禁失灵）%n", passed, probes.size());
        System.out.println("  └" + "─".repeat(58));
    }
}
