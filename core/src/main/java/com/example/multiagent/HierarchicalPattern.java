package com.example.multiagent;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Hierarchical 模式（多 Agent 架构选型之三）：<b>树状的层层下派</b>。
 * 总监拆给组长，组长拆给工程师——每层只做两件事：要么自己「完成」，
 * 要么「委派」给下一层，委派链路逐级留痕。
 *
 * <p>什么时候需要它：Agent 数量上了规模、或存在天然的汇报关系
 * （如软件交付里的 总监→组长→工程师）。Supervisor 是一层协调，
 * Hierarchical 是 N 层——层级每多一层，上下文和控制的损失就多一分，
 * 所以<b>每跳必须过护栏</b>：{@link DelegationGuard} 在这里不是装饰，
 * 是层级失控前的最后一道闸。</p>
 *
 * <p>层间协议是文本约定：每层对收到的任务回复 {@code 完成：结果}
 * 或 {@code 委派：子任务}。护栏拦下时返回 {@code [护栏拦截] 理由}，
 * 由调用方决定是降级处理还是终止。</p>
 */
public final class HierarchicalPattern {

    private static final String DONE = "完成：";
    private static final String DELEGATE = "委派：";

    /** 一层：名字 + 这一层的处理函数（通常是包了一层的 LLM 调用）。 */
    public record Level(String name, Function<String, String> lead) {
    }

    private HierarchicalPattern() {
    }

    /**
     * 沿层级下派：逐层问「完成还是委派」，委派前过护栏。
     *
     * @param task 原始任务，交给第一层
     * @param levels 从上到下的层级列表，最后一个不能再委派
     * @param guard 委派护栏，每次「委派：」放行前过一遍
     * @param trace 过程留痕回调
     * @return 最先收到的 {@code 完成：} 的内容；被护栏拦下时返回
     *         {@code [护栏拦截] <理由>}；最后一层仍未完成时抛异常。
     */
    public static String run(String task, List<Level> levels, DelegationGuard guard,
                             BiConsumer<String, String> trace) {
        String current = task;
        for (int i = 0; i < levels.size(); i++) {
            Level level = levels.get(i);
            String out = level.lead().apply(current);
            trace.accept(level.name(), out);
            if (out.startsWith(DONE)) {
                return out.substring(DONE.length());
            }
            if (!out.startsWith(DELEGATE)) {
                throw new IllegalStateException(level.name()
                        + " 的回复既不是「完成：」也不是「委派：」：" + out);
            }
            if (i == levels.size() - 1) {
                throw new IllegalStateException(level.name() + " 是最后一层，不能再委派：" + out);
            }
            String subTask = out.substring(DELEGATE.length());
            String next = levels.get(i + 1).name();
            Optional<DelegationGuard.Violation> blocked = guard.allow(level.name(), next);
            if (blocked.isPresent()) {
                trace.accept("护栏", blocked.get().rule() + " → " + blocked.get().reason());
                return "[护栏拦截] " + blocked.get().reason();
            }
            guard.record(level.name(), next);
            trace.accept("委派", level.name() + " → " + next + "：" + subTask
                    + "（链路 " + guard.chainView() + "）");
            current = subTask;
        }
        throw new IllegalStateException("层级用尽但没有收到「完成：」");
    }
}
