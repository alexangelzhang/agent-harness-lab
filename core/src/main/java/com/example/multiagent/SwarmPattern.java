package com.example.multiagent;

import com.example.langgraph4j.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Swarm 模式（多 Agent 架构选型之四）：<b>去中心化的接力</b>。
 * 没有主管：当前持有控制权的 specialist 处理对话，发现「这活儿不归我」
 * 就 handoff 给下一个，控制权一路前传，<b>不绕回中心</b>。
 *
 * <p>什么时候需要它：对话焦点会在过程中漂移的场景（客服转接最典型）——
 * 物流 → 退款 → 财务，每一棒只关心自己的一段。代价：没有全局视角，
 * 信息全靠一棒一棒带过去，所以 handoff 必须<b>留痕</b>，
 * 而且循环交接（A→B→A）必须由护栏掐断，否则会两个 Agent 互踢皮球。</p>
 */
public final class SwarmPattern {

    /** 一个接棒者：名字 + 负责什么（cue，给规则路由用）+ 它自己的模型。 */
    public record Specialist(String name, String cue, LlmClient brain) {
    }

    private SwarmPattern() {
    }

    /**
     * 规则路由：回复里命中谁的 cue 词就交给谁。第一个参数是当前持有者——
     * <b>跳过交回自己</b>：接棒者提自己领域的词（退款专员说「退款」）
     * 不是交接信号，把交回自己留给护栏的循环检测兜底。零成本、可解释，
     * 但接棒者必须「说对话」命中 cue，真实模型措辞自由时容易漏接或误接。
     */
    public static BiFunction<String, String, Optional<String>> keywordRouter(
            Map<String, List<String>> cues) {
        return (current, reply) -> {
            for (Map.Entry<String, List<String>> entry : cues.entrySet()) {
                if (entry.getKey().equals(current)) {
                    continue;
                }
                for (String cue : entry.getValue()) {
                    if (reply.contains(cue)) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
            return Optional.empty();
        };
    }

    /**
     * 模型路由：调度决策也交给 LLM——读回复的语义判断该谁接棒，<b>不用背
     * cue 词</b>，接棒者可以自由措辞。每次交接花一次调用，比规则路由贵、
     * 理由不如关键词透明（模型只说名字不说为什么），换来的是对自由文本
     * 的容忍度。解析只认花名册里的名字且跳过当前持有者，输出对不上名字
     * 一律视为收工（fail-closed 的反面：宁收工不乱交）。
     */
    public static BiFunction<String, String, Optional<String>> modelRouter(
            LlmClient routerBrain, List<Specialist> roster) {
        return (current, reply) -> {
            StringBuilder manifest = new StringBuilder();
            for (Specialist s : roster) {
                manifest.append("- ").append(s.name()).append("：").append(s.cue()).append('\n');
            }
            String prompt = "你在给客服团队做交接调度。当前由【" + current
                    + "】回复了用户/上一棒，原文：\n" + reply
                    + "\n可接棒的专员：\n" + manifest
                    + "只有当前专员明确表示要转交、且能确定转给谁时，才输出"
                    + "那个专员的名字（只输出名字，不要任何解释）；如果问题"
                    + "已经解决、或没有明确的下一棒，输出「收工」。";
            String out = routerBrain.complete(prompt).trim();
            for (Specialist s : roster) {
                if (!s.name().equals(current) && out.contains(s.name())) {
                    return Optional.of(s.name());
                }
            }
            return Optional.empty();
        };
    }

    /**
     * 接力执行：从 roster 第一个 specialist 开始，每轮用 router 看回复里
     * 是否点名下一个接棒者；点名则过护栏、留痕、交棒；没人点名即收工。
     * 不设时间盒——等价于 {@code run(..., 0)}。
     *
     * @param router 输入（当前持有者, 当前回复），返回下一个 specialist 的名字
     *               （空 = 收工）；路由应跳过持有者自己，把交回自己留给护栏兜底
     * @return 最后一棒的最终回复；被护栏拦下时返回 {@code [护栏拦截] 理由}
     */
    public static String run(String task, List<Specialist> roster,
                             BiFunction<String, String, Optional<String>> router,
                             DelegationGuard guard, BiConsumer<String, String> trace) {
        return run(task, roster, router, guard, trace, 0);
    }

    /**
     * 带时间盒的接力：整轮运行最多烧到 {@code deadlineMillis}（系统时钟毫秒），
     * 到点就不再交接、就地收工——<b>已生成的回复原样保留</b>，时间盒只影响
     * 「还要不要继续传」，不丢弃已有产出。首棒回复永远会拿到（任务至少
     * 要有一个答复），从第一次交接决策开始检查死线。
     *
     * @param deadlineMillis 死线（{@link System#currentTimeMillis} 口径）；
     *                       {@code <= 0} 表示不设时间盒
     * @return 最后一棒的最终回复；被护栏拦下时返回 {@code [护栏拦截] 理由}；
     *         时间盒到点时返回当前持有者的回复（就地收工）
     */
    public static String run(String task, List<Specialist> roster,
                             BiFunction<String, String, Optional<String>> router,
                             DelegationGuard guard, BiConsumer<String, String> trace,
                             long deadlineMillis) {
        if (roster.isEmpty()) {
            throw new IllegalArgumentException("Swarm 至少需要一个 specialist");
        }
        Map<String, Specialist> byName = roster.stream()
                .collect(Collectors.toMap(Specialist::name, s -> s));
        Specialist current = roster.get(0);
        String reply = current.brain().complete(task);
        trace.accept(current.name(), reply);
        while (true) {
            if (deadlineMillis > 0 && System.currentTimeMillis() > deadlineMillis) {
                trace.accept("时间盒", "到点就地收工——已拿到「" + current.name() + "」的回复，"
                        + "不再继续交接");
                return reply;
            }
            Optional<String> nextName = router.apply(current.name(), reply);
            if (nextName.isEmpty()) {
                return reply;
            }
            Specialist next = byName.get(nextName.get());
            if (next == null) {
                throw new IllegalStateException("路由点名的 specialist 不存在：" + nextName.get());
            }
            Optional<DelegationGuard.Violation> blocked = guard.allow(current.name(), next.name());
            if (blocked.isPresent()) {
                trace.accept("护栏", blocked.get().rule() + " → " + blocked.get().reason());
                return "[护栏拦截] " + blocked.get().reason();
            }
            guard.record(current.name(), next.name());
            trace.accept("交接", current.name() + " → " + next.name()
                    + "（第 " + guard.handoffs() + " 跳，链路 " + guard.chainView() + "）");
            reply = next.brain().complete(reply);
            trace.accept(next.name(), reply);
            current = next;
        }
    }
}
