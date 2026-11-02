package com.example.harness.memory;

import java.util.List;

/**
 * 记忆治理演示：写入闸门 → 遗忘清理 → 五维评测
 * （2026-10-31，LLM Memory 综述解读文章的三个缺口补齐）。
 *
 * <p>场景一「写入闸门」：模型推断而非用户确认的事实（置信 &lt; 0.6）
 * 以 UNCONFIRMED 落库——检索默认不出，不会污染后续上下文；
 * 人工确认（confirm）后才转 ACTIVE。低置信记忆该挂起，不是照单全收。</p>
 *
 * <p>场景二「遗忘清理」：到期还挂着 ACTIVE 的占着检索位却永远不该被
 * 注入——Janitor 置 EXPIRED；被替代/过期的超过保留期物理清除。
 * 遗忘是第四个一等动作，不是缺陷。</p>
 *
 * <p>场景三「五维评测」：fidelity / interference / persistence /
 * efficiency / capacity 一屏出分——评测从「支持多长」转向
 * 「过去是否真的有用」。</p>
 *
 * <p>场景四「清理在岗」：遗忘接进召回管道——每 8 次召回自动跑一轮
 * sweep，到期记忆不占检索位，不靠人想起才清。</p>
 *
 * <p>运行：{@code mvn exec:java@memorybench}。</p>
 */
public class MemoryGovernanceScenario {

    public static void main(String[] args) {
        String line = "=".repeat(66);
        System.out.println(line);
        System.out.println("记忆治理：写入闸门 / 遗忘清理 / 五维评测");
        System.out.println(line);

        boolean gateOk = scenarioGate();
        boolean janitorOk = scenarioJanitor();
        boolean benchOk = scenarioBench();
        boolean autoOk = scenarioAutoSweep();

        System.out.println();
        System.out.println(line);
        boolean ok = gateOk && janitorOk && benchOk && autoOk;
        System.out.println(ok
                ? "收尾：记得住是底线，忘得掉、信得过、量得出才是治理。"
                : "!! 记忆治理闭环没走通");
        System.out.println(line);
        System.exit(0);
    }

    /** 场景一：写入闸门——低置信挂起，确认后才进检索。 */
    private static boolean scenarioGate() {
        header("场景一：写入闸门", "模型推断的，先挂起来，别直接当真事用");
        MemoryStore store = new MemoryStore();

        Memory guess = store.add(Memory.Scope.PROJECT, "支付中台",
                "网关超时时间疑似配置为 3 秒", "模型从日志推断，未经确认",
                List.of("网关", "超时"), 0.5, 0, List.of());
        System.out.println("  写入：「" + guess.content() + "」");
        System.out.println("    置信 " + guess.confidence() + "（< 0.6，推断非确认）→ 状态 "
                + guess.status());
        boolean hidden = store.search("网关 超时").isEmpty();
        System.out.println("    检索「网关 超时」→ " + (hidden ? "无结果（挂起中，不出）"
                : "!! 挂起记忆泄漏进了检索"));

        store.confirm(guess.id());
        boolean visible = !store.search("网关 超时").isEmpty();
        System.out.println("  人工确认后 → 状态 " + store.all().get(0).status()
                + "，检索 → " + (visible ? "命中（转正生效）" : "!! 确认后仍查不到"));
        return hidden && visible;
    }

    /** 场景二：遗忘清理——到期置 EXPIRED，超保留期物理清。 */
    private static boolean scenarioJanitor() {
        header("场景二：遗忘清理", "遗忘是第四个动作，不是缺陷");
        MemoryStore store = new MemoryStore();
        long now = System.currentTimeMillis();

        Memory overdue = store.add(Memory.Scope.PROJECT, "支付中台",
                "临时演练定于上周五 14:00", "演练通知",
                List.of("演练"), 0.9, now - 1, List.of());
        Memory old = store.add(Memory.Scope.PROJECT, "支付中台",
                "旧版限流阈值 200 QPS", "三个月前的配置记录",
                List.of("限流"), 0.9, 0, List.of());
        store.supersede(old.id());
        System.out.println("  库里两条该清的：");
        System.out.println("    [" + overdue.id() + "] 演练通知，有效期已过期，还挂着 ACTIVE");
        System.out.println("    [" + old.id() + "] 旧限流配置，已被替代（SUPERSEDED）");

        MemoryJanitor janitor = new MemoryJanitor(0); // 保留期 0：替代即归档
        MemoryJanitor.Report report = janitor.sweep(store);
        System.out.println("  Janitor 清理报告：" + report);

        boolean expiredOk = store.all().stream().anyMatch(m -> m.id().equals(overdue.id())
                && m.status() == Memory.Status.EXPIRED);
        boolean purgedOk = store.all().stream().noneMatch(m -> m.id().equals(old.id()));
        System.out.println("    到期项 → " + (expiredOk ? "EXPIRED（占位的清了状态）"
                : "!! 到期项没被置 EXPIRED"));
        System.out.println("    替代项 → " + (purgedOk ? "已物理清除（位置让出来）"
                : "!! 替代项还在库里"));

        MemoryJanitor.Report again = janitor.sweep(store);
        System.out.println("  再跑一遍：" + again + "（幂等，可放心定时跑）");
        return expiredOk && purgedOk;
    }

    /** 场景三：五维 benchmark 一屏出分。 */
    private static boolean scenarioBench() {
        header("场景三：五维评测", "评测从「支持多长」转向「过去是否真的有用」");
        double fidelity = MemoryBench.fidelity(10);
        boolean interference = MemoryBench.interference();
        boolean persistence = MemoryBench.persistence();
        double[] efficiency = MemoryBench.efficiency(List.of(
                "继续上次的评估方案", "1+1 等于几？"));
        boolean capacity = MemoryBench.capacity(8, 12);

        System.out.println("  fidelity（保真）      top-1 命中率      = "
                + String.format(java.util.Locale.ROOT, "%.0f%%", fidelity * 100)
                + "   （写入 10 条，逐条回查）");
        System.out.println("  interference（抗干扰）新旧并存取新      = "
                + (interference ? "通过" : "!! 未通过"));
        System.out.println("  persistence（持久）    10 次打扰后仍命中 = "
                + (persistence ? "通过" : "!! 未通过"));
        System.out.println("  efficiency（效率）     平均注入 "
                + String.format(java.util.Locale.ROOT, "%.1f", efficiency[0]) + " 条 / 任务，"
                + "触发器跳过率 "
                + String.format(java.util.Locale.ROOT, "%.0f%%", efficiency[1] * 100));
        System.out.println("  capacity（容量）       写爆限容库         = "
                + (capacity ? "通过（最久未用被淘汰，总量有界）" : "!! 未通过"));
        return fidelity == 1.0 && interference && persistence && capacity;
    }

    /** 场景四：遗忘接进召回管道——每 8 次召回自动清一轮。 */
    private static boolean scenarioAutoSweep() {
        header("场景四：清理在岗", "遗忘接进召回管道，不靠人想起才清");
        MemoryStore store = new MemoryStore();
        long now = System.currentTimeMillis();
        store.add(Memory.Scope.PROJECT, "支付中台",
                "演练改期通知（已作废）", "旧演练通知",
                List.of("演练"), 0.9, now - 1, List.of());
        store.add(Memory.Scope.PROJECT, "支付中台",
                "限流阈值当前 500 QPS", "本周配置记录",
                List.of("限流"), 0.95, 0, List.of());
        System.out.println("  库里混着一条已过期的演练通知，没人手动清理。");

        MemoryMetrics metrics = new MemoryMetrics();
        // 短保留期只是为了让归档也可见；演示主角是「自动触发」这件事
        RecallPipeline pipeline = new RecallPipeline(metrics,
                new MemoryJanitor(0));
        boolean sweptOn8th = false;
        for (int i = 1; i <= 8; i++) {
            RecallPipeline.RecallResult r = pipeline.recall(
                    "继续上次的限流配置评估", List.of(), store,
                    KnowledgeBase.NONE, List.of());
            boolean note = r.notes().stream().anyMatch(n -> n.contains("后台清理"));
            if (note) {
                sweptOn8th = i == 8;
                System.out.println("  第 " + i + " 次召回，清理自动触发：");
                r.notes().stream().filter(n -> n.contains("后台清理")
                                || n.contains("冲突抑制"))
                        .forEach(n -> System.out.println("    → " + n));
            }
        }
        boolean expired = store.all().stream().anyMatch(m -> m.status()
                == Memory.Status.EXPIRED);
        MemoryMetrics.Snapshot s = metrics.snapshot();
        System.out.println("  指标里的清理计数：到期 " + s.janitorExpired()
                + " / 归档 " + s.janitorArchived());
        System.out.println("  到期记忆 → " + (expired ? "已置 EXPIRED（检索不再出）"
                : "!! 还挂着 ACTIVE"));
        return sweptOn8th && expired && s.janitorExpired() >= 1;
    }

    private static void header(String title, String subtitle) {
        System.out.println();
        System.out.println("── " + title + " ── " + subtitle);
        System.out.println();
    }
}
