package com.example.langgraph4j;

import com.example.langgraph4j.llm.LlmClient;
import com.example.langgraph4j.llm.RateLimitedLlm;
import com.example.langgraph4j.llm.RetryingLlm;
import com.example.langgraph4j.llm.ScriptedLlm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流与背压 场景：真实 LLM 是稀缺资源（额度 + 网关配额），并发一上量
 * （多 Agent 并行 fan-out、批量评估 k 次 trial）必须先有限流再谈别的。
 *
 * <p>两幕，全剧本离线可跑：</p>
 * <ol>
 *   <li><b>并发闸</b>：6 个线程同时打模型，{@link RateLimitedLlm} 卡
 *       并发上限 2——观测到的同时在飞调用必须 ≤2（背压 = 超出的调用方
 *       原地排队，不是出错重试）；</li>
 *   <li><b>间隔闸</b>：同一客户端串行打 5 次，最小间隔 100ms——总耗时
 *       必须 ≥400ms；不限间隔的对照组明显更快。</li>
 * </ol>
 *
 * <p>真实接线：{@code RetryingLlm(RateLimitedLlm(new JdkHttpLlm(), 4, 200))}
 * ——限流在内重试在外，重试风暴也冲不破并发闸。HTTP 连接池侧
 * {@link com.example.langgraph4j.llm.JdkHttpLlm} 已改为全 JVM 共享一个
 * {@code HttpClient}（连接复用），不再每实例一池。</p>
 *
 * <p>运行：{@code scripts/dev.sh ratelimit}。</p>
 */
public class RateLimitScenario {

    private static int bad;

    public static void main(String[] args) throws Exception {
        header("限流与背压", "并发上限（信号量背压）+ 最小调用间隔 —— 真实 LLM 是稀缺资源");

        actConcurrencyCap();
        actInterval();

        String line = "=".repeat(66);
        System.out.println();
        System.out.println(line);
        if (bad == 0) {
            System.out.println("收尾：并发一上量，先有限流再谈别的——");
            System.out.println("      背压是调用方排队，不是网关替你报错。");
        } else {
            System.out.println("!! 有 " + bad + " 处断言未通过");
        }
        System.out.println(line);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ---- 幕 1：并发闸 ----------------------------------------------------------------

    private static void actConcurrencyCap() throws Exception {
        System.out.println("\n── 幕 1：6 个线程同时打模型，并发上限 = 2 ──");

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // 带 30ms 工作量的剧本大脑，让并发重叠可观测
        LlmClient brain = prompt -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            sleep(30);
            inFlight.decrementAndGet();
            return "答：" + prompt;
        };
        LlmClient gated = new RateLimitedLlm(brain, 2, 0);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                String task = "问题" + i;
                Callable<String> call = () -> gated.complete(task);
                futures.add(pool.submit(call));
            }
            List<String> answers = new ArrayList<>();
            for (Future<String> f : futures) {
                answers.add(f.get(30, TimeUnit.SECONDS));
            }
            boolean allAnswered = answers.stream().allMatch(a -> a != null && !a.isBlank());
            System.out.println("  观测到的并发峰值 = " + peak.get() + "（上限 2，共 6 个并发调用方）");
            check("并发峰值被闸在 2", peak.get() == 2);
            check("6 个调用全部拿到答复（排队不是丢请求）", allAnswered);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 幕 2：间隔闸 ----------------------------------------------------------------

    private static void actInterval() {
        System.out.println("\n── 幕 2：最小调用间隔 = 100ms，串行打 5 次 ──");

        LlmClient instant = new RateLimitedLlm(new ScriptedLlm().says("ok", "ok", "ok", "ok", "ok"), 0, 0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            instant.complete("q" + i);
        }
        long freeMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  不限间隔：5 次共 " + freeMs + "ms");

        LlmClient gated = new RateLimitedLlm(new ScriptedLlm().says("ok", "ok", "ok", "ok", "ok"), 0, 100);
        t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            gated.complete("q" + i);
        }
        long throttledMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  间隔 100ms：5 次共 " + throttledMs + "ms（4 个间隔 ≥400ms 才算闸住）");

        check("限间隔后 5 次 ≥400ms", throttledMs >= 400);
        check("限间隔版比不限间隔版慢（间隔闸真在等）", throttledMs > freeMs);
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
