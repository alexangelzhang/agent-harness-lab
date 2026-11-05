package com.example.harness.memory.kibitzer;

import com.example.agent.llm.LlmClient;
import com.example.harness.memory.Memory;
import com.example.harness.memory.MemoryStore;
import com.example.harness.security.InjectionGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Kibitzer：记忆召回的常驻裁判，一个主会话挂一个（OmO 研读落地，
 * 见 docs/omo_orchestration_kibitzer.md）。
 *
 * <p>现有召回管道是同步的：触发 → 召回 → 融合 → 注入，主 loop 当场等结果。
 * Kibitzer 把「值不值得提醒」从管道里拆出来放到旁观席——主 loop 的事件
 * （prompt / 工具调用 / 工具结果）以 {@link KibitzerEvent} 的形式喂给它，
 * 它不挡路、不等待，判完了才用一条 nudge 拍主 agent 的肩。</p>
 *
 * <p>五条不变量（场景可直接断言）：</p>
 * <ol>
 *   <li><b>按闹铃醒</b>：事件触到「还没判定过」的已存记忆才花一次模型调用；
 *       没触到就零消耗（{@link #wakes()} 不涨）；</li>
 *   <li><b>醒来自证</b>：一次 wake 内最多 {@value #MAX_READS_PER_WAKE} 次
 *       只读探查（翻记忆库找佐证），超出即停；</li>
 *   <li><b>零写入</b>：依赖是 {@link MemoryReader} 只读视图，
 *       写方法在编译期就不存在；</li>
 *   <li><b>唯一动作是 nudge</b>：产出只有一句 {@code recalled memory: <hint>}，
 *       交给主会话水槽，侧车自己不碰任何文件和记忆；</li>
 *   <li><b>失败退避</b>：裁判模型调用炸了不当场判定——这条记忆保持
 *       「未判定」，下一条相关事件来了再试。</li>
 * </ol>
 *
 * <p>上下文塞满时的重播种用 {@link #reseedSummary()}：只带「已投递/已拒绝」
 * 的清单重建，不背整个事件流。</p>
 */
public final class Kibitzer {

    /** 一次 wake 的只读探查预算（OmO 原文：eight tool calls per wake）。 */
    public static final int MAX_READS_PER_WAKE = 8;

    /** 裁判 prompt 的输入面防线：事件文本（尤其工具结果）是攻击者能碰的
     *  内容，进 prompt 前先过注入扫描——命中句整句脱敏，防外部内容
     *  操纵裁判往主会话塞伪造 nudge。 */
    private static final InjectionGuard GUARD = new InjectionGuard();

    private final MemoryReader reader;
    private final LlmClient judge;
    private final Consumer<String> nudgeSink;
    /** 已判定过的记忆 id：投递和拒绝都算，同一条记忆不判第二次。 */
    private final Set<String> judged = new LinkedHashSet<>();
    private final List<String> delivered = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    private int wakes;
    private int failures;

    /**
     * @param reader    记忆库的只读视图（{@link MemoryReader#of} 包出来的）
     * @param judge     裁判模型——便宜小模型的位置，剧本里是另一个 ScriptedLlm
     * @param nudgeSink  nudge 的去处（主会话的提示流）
     */
    public Kibitzer(MemoryReader reader, LlmClient judge, Consumer<String> nudgeSink) {
        this.reader = reader;
        this.judge = judge;
        this.nudgeSink = nudgeSink;
    }

    /**
     * 主会话事件流入口。唤醒闸门：事件触到未判定的已存记忆才醒，
     * 否则直接返回——不花模型调用，也绝不主动吵醒空闲会话。
     */
    public void onEvent(KibitzerEvent event) {
        List<MemoryStore.Hit> fresh = reader.search(event.text()).stream()
                .filter(h -> !judged.contains(h.memory().id()))
                .toList();
        if (fresh.isEmpty()) {
            return; // 没触到新记忆：零消耗
        }
        wake(event, fresh.get(0).memory());
    }

    /**
     * 一次 wake：自证（预算内翻记忆库找佐证）→ 交裁判模型裁决 →
     * NUDGE 投递 / 其余视为 SKIP。一次只判分数最高的那条，
     * 其余新记忆保持未判定，等下次被触到再说。
     */
    private void wake(KibitzerEvent event, Memory candidate) {
        wakes++;
        // 自证：用记忆内容的词反查库里有没有打架/佐证的条目，全程只读、计预算
        int reads = 1; // 唤醒那次检索也算一次读
        List<String> corroboration = new ArrayList<>();
        for (String term : candidate.tags()) {
            if (reads >= MAX_READS_PER_WAKE) {
                break;
            }
            reads++;
            for (MemoryStore.Hit h : reader.search(term)) {
                if (!h.memory().id().equals(candidate.id())) {
                    corroboration.add(h.memory().content());
                }
            }
        }
        String prompt = "你是记忆侧车的裁判。主会话刚发生一条事件，触到了一条已存记忆。\n"
                + "判断这条记忆是否值得现在提醒主 agent：只对当前事件有实际帮助才提醒，"
                + "否则放过它，别打断主 agent。\n"
                + "事件（" + event.kind() + "）：" + GUARD.sanitize(event.text()) + "\n"
                + "触到的记忆：" + candidate.content() + "\n"
                + (corroboration.isEmpty() ? "库里没有其他相关条目。\n"
                        : "库里的相关条目：" + String.join("；", corroboration) + "\n")
                + "只回一行：值得提醒就回 NUDGE: <一句话提示>，不值得就回 SKIP。";
        String verdict;
        try {
            verdict = judge.complete(prompt).trim();
        } catch (RuntimeException e) {
            // 退避：裁判没开口就不算判过，下一条相关事件来了再醒
            failures++;
            return;
        }
        judged.add(candidate.id());
        if (verdict.startsWith("NUDGE:")) {
            String hint = verdict.substring("NUDGE:".length()).trim();
            delivered.add(hint);
            nudgeSink.accept("recalled memory: " + hint);
        } else {
            rejected.add(candidate.content());
        }
    }

    /** 累计醒来的次数（花了多少次裁判模型调用）。 */
    public int wakes() {
        return wakes;
    }

    /** 裁判模型调用失败的累计次数（这些记忆仍未判定）。 */
    public int failures() {
        return failures;
    }

    /** 已投递的 nudge 提示。 */
    public List<String> delivered() {
        return List.copyOf(delivered);
    }

    /** 已拒绝的记忆原文。 */
    public List<String> rejected() {
        return List.copyOf(rejected);
    }

    /**
     * 重播种素材：侧车上下文塞满时，只带这份「已投递/已拒绝」清单重建，
     * 不背整个事件流——已判定的记忆 id 也在里头，重建后不会重判。
     */
    public String reseedSummary() {
        return "已判定 " + judged.size() + " 条记忆：投递 " + delivered.size()
                + " 条「" + String.join("；", delivered) + "」，拒绝 "
                + rejected.size() + " 条「" + String.join("；", rejected) + "」";
    }
}
